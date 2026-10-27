package mcl.mod;

import mcl.util.Dirs;
import mcl.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** 本地模组目录操作：枚举、启用/禁用（改后缀）、删除、导入。 */
public final class Mods {
    public static final String DISABLED = ".disabled";

    public static final class Entry {
        public Path path;
        public String name;
        public boolean enabled;
        public long size;

        /** 去掉 .jar / .jar.disabled 后的显示名。 */
        public String displayName() {
            String n = name;
            if (n.toLowerCase(Locale.ROOT).endsWith(DISABLED)) n = n.substring(0, n.length() - DISABLED.length());
            if (n.toLowerCase(Locale.ROOT).endsWith(".jar")) n = n.substring(0, n.length() - 4);
            return n;
        }

        @Override
        public String toString() {
            return displayName();
        }
    }

    private Mods() {}

    /** 列出目录内的模组文件（.jar 与已禁用的 .jar.disabled）。 */
    public static List<Entry> list(Path dir) {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                String lower = n.toLowerCase(Locale.ROOT);
                boolean disabled = lower.endsWith(DISABLED);
                String base = disabled ? n.substring(0, n.length() - DISABLED.length()) : n;
                if (!base.toLowerCase(Locale.ROOT).endsWith(".jar")) return;
                Entry e = new Entry();
                e.path = p;
                e.name = n;
                e.enabled = !disabled;
                try {
                    e.size = Files.size(p);
                } catch (IOException ignored) {
                }
                out.add(e);
            });
        } catch (IOException e) {
            Log.warn("读取模组目录失败：" + e.getMessage());
        }
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.displayName(), b.displayName()));
        return out;
    }

    /** 启用 / 禁用：切换 .disabled 后缀（HMCL 同款做法，不需要改 jar 内容）。 */
    public static Path setEnabled(Entry e, boolean enabled) throws IOException {
        Path src = e.path;
        if (e.enabled == enabled) return src;
        Path dst = enabled
                ? src.resolveSibling(src.getFileName().toString().substring(
                        0, src.getFileName().toString().length() - DISABLED.length()))
                : src.resolveSibling(src.getFileName().toString() + DISABLED);
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        return dst;
    }

    public static void delete(Entry e) throws IOException {
        Files.deleteIfExists(e.path);
    }

    /** 把外部文件复制进模组目录，重名时自动加序号。 */
    public static Path importFile(Path dir, Path source) throws IOException {
        Dirs.ensure(dir);
        String name = source.getFileName().toString();
        Path target = dir.resolve(name);
        int i = 1;
        while (Files.exists(target)) {
            String base = name;
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                base = name.substring(0, dot);
                ext = name.substring(dot);
            }
            target = dir.resolve(base + " (" + i++ + ")" + ext);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
