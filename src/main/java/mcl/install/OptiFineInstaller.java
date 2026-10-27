package mcl.install;

import mcl.util.Dirs;
import mcl.util.Compat;

import mcl.net.Http;
import mcl.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OptiFine 安装。
 *
 * <p>OptiFine 是**模组**，不是模组加载器。它下载到的那个 jar 里带着
 * {@code META-INF/mods.toml}（{@code modLoader="javafml"}、{@code modId="optifine"}）
 * 和 {@code TweakClass: optifine.OptiFineForgeTweaker} —— 本身就能被 Forge 当 mod 加载，
 * 所以正确用法是往 {@code mods/} 里放一个文件，和别的模组共存，而不是另开一个版本。
 *
 * <p>分两条路：
 * <ul>
 *   <li>{@link Shape#MOD}：目标版本已经有 Forge / NeoForge / Fabric / Quilt，把 jar 复制进
 *       {@code mods/}（Fabric / Quilt 还要配 OptiFabric）。</li>
 *   <li>{@link Shape#INSTALLER}：目标是原版，没有 mod 加载能力，只能调官方安装器
 *       {@code optifine.Installer.doInstall} 生成一个带 OptiFine 的独立版本
 *       （HMCL 同款思路：安装器 jar 里带着打补丁所需的 patch 与安装逻辑）。</li>
 * </ul>
 */
public final class OptiFineInstaller {

    private static final String SITE = "https://optifine.net";
    private static final Pattern LINK = Pattern.compile(
            "adloadx\\?f=(OptiFine_([0-9][0-9._]*)_([A-Za-z0-9_]+)\\.jar)");
    private static final Pattern TOKEN = Pattern.compile(
            "(downloadx\\?f=OptiFine_[^\"'&]+&(?:amp;)?x=[0-9a-fA-F]+)");
    /** 安装器输出里真正有信息量的行（其余是逐个 class 的解包日志，太吵）。 */
    private static final Pattern KEEP = Pattern.compile(
            "^(Update|Install|Patching|Extract|Creating|Copying|Downloading|Error|Exception|Warn|OptiFine installer)");

    public static final class Version {
        public String mcVersion = "";
        public String edition = "";     // HD_U_I6
        public String fileName = "";

        public String patch() {
            int i = edition.lastIndexOf('_');
            return i >= 0 ? edition.substring(i + 1) : edition;
        }

        /** 官方安装后生成的版本 id。 */
        public String versionId() {
            return mcVersion + "-OptiFine_" + edition;
        }

        @Override
        public String toString() {
            return "OptiFine " + edition + "（" + mcVersion + "）";
        }
    }

    private OptiFineInstaller() {}

    /** 抓取全部 OptiFine 版本（按游戏版本索引）。 */
    public static List<Version> all() throws IOException {
        String html = Http.getString(SITE + "/downloads");
        Map<String, Version> map = new LinkedHashMap<>();
        Matcher m = LINK.matcher(html);
        while (m.find()) {
            Version v = new Version();
            v.fileName = m.group(1);
            v.mcVersion = m.group(2);
            v.edition = m.group(3);
            map.putIfAbsent(v.mcVersion + "|" + v.edition, v);
        }
        return new ArrayList<>(map.values());
    }

    /** 列出某个游戏版本可用的 OptiFine（通常 1~2 个）。 */
    public static List<Version> list(String mcVersion) throws IOException {
        List<Version> out = new ArrayList<>();
        for (Version v : all()) {
            if (v.mcVersion.equals(mcVersion)) out.add(v);
        }
        return out;
    }

    // ---------------- 作为模组安装 ----------------

    /** OptiFine 该以什么形态落地。 */
    public enum Shape {
        /** 丢进 mods：Forge / NeoForge 直接认，Fabric / Quilt 靠 OptiFabric。 */
        MOD,
        /** 原版没有 mod 加载能力，只能由官方安装器另开一个版本。 */
        INSTALLER
    }

    /**
     * 按目标版本的加载器决定形态。
     *
     * <p>这是这个类里最该被记住的一条：OptiFine 从来不是「加载器」。
     * 把它和 Forge / Fabric 摆在一起让用户二选一，等于逼用户放弃已有模组。
     *
     * <p>官方只支持 Forge；Fabric / Quilt 靠社区的 OptiFabric；NeoForge 官方明确不支持
     * （能装进去，但大概率起不来，界面上会单独提醒）。原版没有 mod 加载能力，只能走安装器。
     */
    public static Shape shapeFor(String loaderKind) {
        String k = loaderKind == null ? "" : loaderKind.toLowerCase(java.util.Locale.ROOT);
        if ("forge".equals(k) || "neoforge".equals(k) || "fabric".equals(k) || "quilt".equals(k)) {
            return Shape.MOD;
        }
        return Shape.INSTALLER;
    }

    /** Fabric / Quilt 需要 OptiFabric 才能加载 OptiFine。 */
    public static boolean needsOptiFabric(String loaderKind) {
        String k = loaderKind == null ? "" : loaderKind.toLowerCase(java.util.Locale.ROOT);
        return "fabric".equals(k) || "quilt".equals(k);
    }

    /** mods 目录里已经放着的那份 OptiFine（没有返回 null）。 */
    public static Path existingMod(Path modsDir) {
        return findJar(modsDir, "optifine");
    }

    /** mods 目录里已经放着的 OptiFabric（没有返回 null）。 */
    public static Path existingOptiFabric(Path modsDir) {
        return findJar(modsDir, "optifabric");
    }

    private static Path findJar(Path dir, String prefix) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                return n.startsWith(prefix) && n.endsWith(".jar");
            }).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 把 OptiFine 的 jar 放进 mods，返回落盘路径。
     *
     * <p>先清掉目录里别的 OptiFine jar：两个 OptiFine 同时在 mods 里，
     * Forge 会因为 {@code modId=optifine} 重复直接拒绝启动，而且报错信息
     * 完全看不出是「装了两份」。
     */
    public static Path placeAsMod(Path jar, Path modsDir) throws IOException {
        Dirs.ensure(modsDir);
        Path old = existingMod(modsDir);
        if (old != null) {
            Files.deleteIfExists(old);
            Log.info("已移除旧的 " + old.getFileName());
        }
        Path dest = modsDir.resolve(jar.getFileName());
        Files.copy(jar, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Log.info("OptiFine 作为模组放入 " + dest);
        return dest;
    }

    /** 下载安装器 jar 到缓存目录，返回本地路径。 */
    public static Path download(Version v, Path cacheDir) throws IOException {
        Dirs.ensure(cacheDir);
        Path dest = cacheDir.resolve(v.fileName);
        if (Files.isRegularFile(dest) && Files.size(dest) > 100_000) return dest;
        String adload = SITE + "/adloadx?f=" + v.fileName;
        String page = Http.getString(adload);
        Matcher m = TOKEN.matcher(page);
        if (!m.find()) {
            throw new IOException("OptiFine 下载页没有返回下载令牌（" + v.fileName + "），可能被广告页拦截");
        }
        String tokenUrl = SITE + "/" + m.group(1).replace("&amp;", "&");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", adload);
        headers.put("Accept", "*/*");
        Http.downloadWithHeaders(tokenUrl, dest, headers, null);
        long size = Files.size(dest);
        if (size < 100_000) {
            Files.deleteIfExists(dest);
            throw new IOException("OptiFine 安装包下载不完整（" + size + " 字节）");
        }
        Log.info("已下载 " + v.fileName + "（" + (size / 1024) + " KB）");
        return dest;
    }

    /**
     * 用官方安装器无界面安装，返回生成的版本 id。
     *
     * @param gameDir   .minecraft 目录（原版必须已安装）
     * @param installer OptiFine 安装器 jar
     * @param javaExe   用来运行安装器的 java（需要 Java 8+，1.17+ 建议 17/21）
     * @param classpathEntry 启动器自身的 jar/classes 目录（提供 hook 入口）
     */
    public static String install(Path gameDir, Path installer, Path javaExe, Path classpathEntry,
                                 Listener listener) throws Exception {
        listener.onStage("运行 OptiFine 安装器");
        ensureLauncherProfiles(gameDir);
        String sep = System.getProperty("path.separator");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(classpathEntry.toString() + sep + installer.toString());
        cmd.add("mcl.install.OptiFineHook");
        cmd.add(gameDir.toAbsolutePath().toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (java.io.InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    String s = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                    out.append(s);
                    for (String line : s.split("\\R")) {
                        if (!Compat.isBlank(line) && KEEP.matcher(line).find()) Log.info("[OptiFine] " + line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "mcl-optifine");
        reader.setDaemon(true);
        reader.start();
        boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("OptiFine 安装超时");
        }
        reader.join(3000);
        String text = out.toString();
        // 用安装器最后打印的完成标记判断成功，不能只看 "Exception"（jar 里有 DeltaException.class 之类）
        if (p.exitValue() != 0 || !text.contains(OptiFineHook.DONE_MARK)) {
            String tail = text.length() > 1200 ? text.substring(text.length() - 1200) : text;
            throw new IOException("OptiFine 安装器执行失败：\n" + tail.trim());
        }
        String id = findInstalledVersion(gameDir);
        if (id == null) throw new IOException("OptiFine 安装器没有生成版本目录，请查看日志");
        listener.onStage("OptiFine 安装完成：" + id);
        return id;
    }

    /**
     * OptiFine 安装器会往 launcher_profiles.json 里写一个 OptiFine 配置，文件不存在时它直接
     * 弹错误框（headless 下就是 HeadlessException）。所以先造一个最小可用的空档案文件。
     */
    private static void ensureLauncherProfiles(Path gameDir) throws IOException {
        Dirs.ensure(gameDir);
        Path file = gameDir.resolve("launcher_profiles.json");
        if (Files.isRegularFile(file) && Files.size(file) > 2) return;
        String json = "{\n"
                + "  \"profiles\": {},\n"
                + "  \"settings\": {\"enableSnapshots\": false, \"keepLauncherOpen\": false},\n"
                + "  \"version\": 2\n"
                + "}";
        Files.write(file, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Log.info("已创建 launcher_profiles.json（OptiFine 安装器要求）");
    }

    /** 在 versions 里找刚生成的 OptiFine 版本（取修改时间最新的一个）。 */
    private static String findInstalledVersion(Path gameDir) throws IOException {
        Path versions = gameDir.resolve("versions");
        if (!Files.isDirectory(versions)) return null;
        String best = null;
        long bestTime = 0;
        try (java.util.stream.Stream<Path> stream = Files.list(versions)) {
            for (Path d : stream.filter(Files::isDirectory).collect(java.util.stream.Collectors.toList())) {
                String name = d.getFileName().toString();
                if (!name.contains("OptiFine")) continue;
                Path json = d.resolve(name + ".json");
                if (!Files.isRegularFile(json)) continue;
                long t = Files.getLastModifiedTime(json).toMillis();
                if (t > bestTime) {
                    bestTime = t;
                    best = name;
                }
            }
        }
        return best;
    }

    public interface Listener {
        void onStage(String stage);
    }
}
