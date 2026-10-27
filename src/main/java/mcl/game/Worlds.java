package mcl.game;

import mcl.util.Dirs;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 存档（世界）管理：扫描 saves 目录、读取 level.dat 摘要、备份为 zip、从 zip 导入、删除。
 *
 * <p>level.dat 是 gzip 压缩的 NBT，这里实现了一个只够用的 NBT 解析器（读到需要的那几个字段），
 * 不引入额外依赖。
 */
public final class Worlds {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");

    private Worlds() {}

    public static final class World {
        public Path dir;
        public String folder = "";
        public String name = "";
        public long lastPlayed;
        public String versionName = "";
        public String gameMode = "";
        public boolean hardcore;
        public boolean cheats;
        public long size;

        public Path iconFile() {
            Path p = dir.resolve("icon.png");
            return Files.isRegularFile(p) ? p : null;
        }

        public String lastPlayedText() {
            if (lastPlayed <= 0) return "未知";
            LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastPlayed), ZoneId.systemDefault());
            return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }

        public String subtitle() {
            StringBuilder sb = new StringBuilder();
            sb.append(gameMode.isEmpty() ? "未知模式" : gameMode);
            if (hardcore) sb.append(" · 极限");
            if (!versionName.isEmpty()) sb.append(" · ").append(versionName);
            sb.append("  ·  ").append(lastPlayedText());
            return sb.toString();
        }

        @Override
        public String toString() {
            return name + " (" + folder + ")";
        }
    }

    /** 存档所在目录（遵守版本隔离）。 */
    public static Path savesDir(Path runDir) {
        return runDir.resolve("saves");
    }

    public static List<World> list(Path runDir) {
        List<World> out = new ArrayList<>();
        Path saves = savesDir(runDir);
        if (!Files.isDirectory(saves)) return out;
        try (java.util.stream.Stream<Path> stream = Files.list(saves)) {
            for (Path d : stream.filter(Files::isDirectory).collect(java.util.stream.Collectors.toList())) {
                if (!Files.isRegularFile(d.resolve("level.dat"))) continue;
                out.add(read(d));
            }
        } catch (IOException ignored) {
        }
        out.sort((a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
        return out;
    }

    public static World read(Path dir) {
        World w = new World();
        w.dir = dir;
        w.folder = dir.getFileName().toString();
        w.name = w.folder;
        Path dat = dir.resolve("level.dat");
        try {
            Map<String, Object> root = readNbt(dat);
            Object data = root.get("Data");
            Map<?, ?> level = data instanceof Map ? (Map<?, ?>) data : root;
            Object name = level.get("LevelName");
            if (name != null) w.name = name.toString();
            Object lp = level.get("LastPlayed");
            if (lp instanceof Number) w.lastPlayed = ((Number) lp).longValue();
            Object gm = level.get("GameType");
            if (gm instanceof Number) w.gameMode = gameModeName(((Number) gm).intValue());
            Object hc = level.get("hardcore");
            if (hc instanceof Number) w.hardcore = ((Number) hc).byteValue() != 0;
            Object allow = level.get("allowCommands");
            if (allow instanceof Number) w.cheats = ((Number) allow).byteValue() != 0;
            Object ver = level.get("Version");
            if (ver instanceof Map) {
                Object vn = ((Map<?, ?>) ver).get("Name");
                if (vn != null) w.versionName = vn.toString();
            }
            Object dver = level.get("DataVersion");
            if (w.versionName.isEmpty() && dver != null) w.versionName = "DataVersion " + dver;
        } catch (Exception ignored) {
            // level.dat 损坏时仍然显示目录名，方便用户手动处理
        }
        w.size = dirSize(dir);
        return w;
    }

    private static String gameModeName(int t) {
        if (t == 1) return "创造模式";
        if (t == 2) return "冒险模式";
        if (t == 3) return "旁观模式";
        return "生存模式";
    }

    // ---------------- 备份 / 导入 ----------------

    /** 备份目录（存档 zip 存放处）。 */
    public static Path backupDir(Path gameDir) {
        return gameDir.resolve("backups").resolve("worlds");
    }

    /** 把存档打包成 zip，返回 zip 路径。 */
    public static Path backup(Path gameDir, World w) throws IOException {
        Path out = backupDir(gameDir);
        Dirs.ensure(out);
        String base = sanitize(w.folder) + "-" + LocalDateTime.now().format(TS);
        Path zip = out.resolve(base + ".zip");
        int n = 2;
        while (Files.exists(zip)) zip = out.resolve(base + "-" + (n++) + ".zip");
        zipDir(w.dir, zip);
        return zip;
    }

    /** 把任意目录压成 zip（条目名以目录名开头，解压后还原为同名文件夹）。 */
    public static void zipDir(Path dir, Path zip) throws IOException {
        String base = dir.getFileName().toString();
        try (OutputStream fos = Files.newOutputStream(zip);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = dir.relativize(file).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(base + "/" + rel));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /** 从 zip 导入存档（支持 "根目录直接是 level.dat" 与 "包一层文件夹" 两种结构）。 */
    public static World importZip(Path runDir, Path zip) throws IOException {
        Path saves = savesDir(runDir);
        Dirs.ensure(saves);
        String baseName;
        String prefix = "";
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            boolean rootLevelDat = zf.getEntry("level.dat") != null;
            if (rootLevelDat) {
                baseName = stripExt(zip.getFileName().toString());
            } else {
                // 找出包含 level.dat 的最浅目录
                String found = null;
                for (ZipEntry e : java.util.Collections.list(zf.entries())) {
                    String nm = e.getName();
                    if (nm.endsWith("level.dat") && !e.isDirectory()) {
                        if (found == null || nm.length() < found.length()) found = nm;
                    }
                }
                if (found == null) throw new IOException("这个压缩包里没有找到 level.dat，不是有效的存档");
                int slash = found.lastIndexOf('/');
                prefix = slash >= 0 ? found.substring(0, slash + 1) : "";
                baseName = prefix.isEmpty() ? stripExt(zip.getFileName().toString())
                        : prefix.substring(0, prefix.length() - 1);
                int s = baseName.lastIndexOf('/');
                if (s >= 0) baseName = baseName.substring(s + 1);
            }
            Path target = uniqueDir(saves, sanitize(baseName));
            extract(zf, prefix, target);
            return read(target);
        }
    }

    private static void extract(ZipFile zf, String prefix, Path target) throws IOException {
        Dirs.ensure(target);
        for (ZipEntry e : java.util.Collections.list(zf.entries())) {
            String nm = e.getName();
            if (!prefix.isEmpty() && !nm.startsWith(prefix)) continue;
            String rel = prefix.isEmpty() ? nm : nm.substring(prefix.length());
            if (rel.isEmpty() || e.isDirectory()) continue;
            Path out = target.resolve(rel).normalize();
            if (!out.startsWith(target)) continue; // 防目录穿越
            Dirs.ensure(out.getParent());
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Path uniqueDir(Path parent, String name) {
        Path p = parent.resolve(name);
        int i = 2;
        while (Files.exists(p)) p = parent.resolve(name + " (" + i++ + ")");
        return p;
    }

    public static void delete(World w) throws IOException {
        deleteTree(w.dir);
    }

    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 复制一个存档目录到 saves 下的新名字（用于"另存一份"）。 */
    public static World duplicate(Path runDir, World w) throws IOException {
        Path target = uniqueDir(savesDir(runDir), sanitize(w.folder) + " 副本");
        copyTree(w.dir, target);
        return read(target);
    }

    public static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                Dirs.ensure(dst.resolve(src.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path out = dst.resolve(src.relativize(file).toString());
                Dirs.ensure(out.getParent());
                Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String sanitize(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return r.isEmpty() ? "world" : r;
    }

    private static String stripExt(String s) {
        int i = s.lastIndexOf('.');
        return i > 0 ? s.substring(0, i) : s;
    }

    private static long dirSize(Path dir) {
        long[] total = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        return total[0];
    }

    // ---------------- 极简 NBT ----------------

    public static Map<String, Object> readNbt(Path levelDat) throws IOException {
        try (InputStream fin = Files.newInputStream(levelDat);
             InputStream gz = new GZIPInputStream(new BufferedInputStream(fin));
             DataInputStream in = new DataInputStream(gz)) {
            int type = in.readUnsignedByte();
            if (type != 10) throw new IOException("level.dat 根标签不是 Compound：" + type);
            readUtf(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) readPayload(in, type);
            return root;
        }
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                int len = in.readInt();
                byte[] b = new byte[Math.max(0, len)];
                in.readFully(b);
                return b;
            }
            case 8: return readUtf(in);
            case 9: {
                int elem = in.readUnsignedByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < len; i++) list.add(readPayload(in, elem));
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String name = readUtf(in);
                    map.put(name, readPayload(in, t));
                }
                return map;
            }
            case 11: {
                int len = in.readInt();
                int[] a = new int[Math.max(0, len)];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                int len = in.readInt();
                long[] a = new long[Math.max(0, len)];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return a;
            }
            default:
                throw new EOFException("未知的 NBT 标签类型：" + type);
        }
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
