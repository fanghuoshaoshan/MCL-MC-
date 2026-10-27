package mcl.game;

import mcl.util.Compat;
import mcl.util.Dirs;
import mcl.util.Log;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 版本隔离：给某个版本准备独立的游戏目录。
 *
 * <p>隔离之后 {@code versions/<id>} 就是这一版的游戏目录，里面的 {@code mods}、
 * {@code saves}、{@code config} 互不干扰。目录本身由 {@code Installer} 建出来，
 * 这里只负责补齐游戏运行时需要的子目录，以及**从共用目录接管一次旧内容**。
 *
 * <h3>为什么必须要「接管」这一步</h3>
 * 不开隔离时所有版本共用 {@code .minecraft/saves}、{@code .minecraft/mods}。
 * 一开隔离，游戏就去 {@code versions/<id>/saves} 找存档了，用户眼里就是
 * **「存档全没了」** —— 文件其实还在，只是没人去读。所以首次为某版本初始化隔离目录时，
 * 把共用目录里已有的内容复制一份过来，并且只做一次（写 {@link #MARKER} 做标记）。
 *
 * <h3>为什么 mods 不在接管清单里</h3>
 * 模组跟游戏版本、加载器版本强绑定。把 {@code .minecraft/mods} 原样复制到每个版本目录，
 * 结果就是「1.20.1 的模组出现在 1.21.11 目录里」——加载器直接崩在启动阶段，
 * 而这正是用户想用隔离解决的问题。所以 mods 留空，由用户按版本自己装。
 *
 * <p>同理 {@code config} 不接管（与 mod 配套才有意义），{@code shaderpacks} 也不接管
 * （光影包动辄几百 MB，复制到每个版本会白白吃掉磁盘）。
 */
public final class Isolation {

    /** 版本目录里的标记：表示隔离目录已经初始化过，不再重复接管。 */
    public static final String MARKER = ".mcl-isolated";

    /**
     * 整合包实例的标记（由 {@code Modpack} 写入）：这类版本**永远**独立，
     * 即使全局关掉隔离也不回共用目录；反过来，它的目录必须是干净的，不接管共用内容。
     */
    public static final String FORCED_MARKER = ".mcl-isolate";

    /** 隔离目录里需要预先建好的子目录（游戏 / 加载器会往里写东西）。 */
    private static final String[] SUBDIRS = {
            "mods", "saves", "config", "resourcepacks", "shaderpacks", "logs", "crash-reports",
    };

    /** 首次接管时从共用目录复制的内容：体积小、且不与具体版本绑定。 */
    private static final String[] ADOPT_DIRS = {"saves", "resourcepacks", "screenshots"};

    /** 首次接管时复制的散文件。 */
    private static final String[] ADOPT_FILES = {"options.txt", "servers.dat", "servers.dat_old"};

    private Isolation() {
    }

    /** 某个版本是否已经初始化过隔离目录。 */
    public static boolean isPrepared(Path versionDir) {
        return versionDir != null && Files.isRegularFile(versionDir.resolve(MARKER));
    }

    /**
     * 给版本目录补齐 mods / saves / config 等子目录，并打上标记。
     *
     * <p>幂等：目录已存在就静默通过，标记已存在就保留原来的（里面记着首次接管时间）。
     */
    public static void prepare(Path versionDir) throws IOException {
        if (versionDir == null) return;
        Dirs.ensure(versionDir);
        for (String sub : SUBDIRS) {
            Dirs.ensure(versionDir.resolve(sub));
        }
        Path marker = versionDir.resolve(MARKER);
        if (!Files.isRegularFile(marker)) {
            Compat.writeString(marker, "isolated\n");
        }
    }

    /**
     * 首次为版本准备隔离目录，并把共用目录里已有的存档等复制过来。
     *
     * <p>已经初始化过的版本直接返回 {@link Result#none()}，不会覆盖用户在版本目录里
     * 后来的改动（比如删掉的存档不会被复制回来）。
     */
    public static Result prepareAndAdopt(Path sharedDir, Path versionDir) throws IOException {
        if (versionDir == null) return Result.none();
        boolean first = !isPrepared(versionDir);
        prepare(versionDir);
        if (!first || sharedDir == null) return Result.none();

        // 共用目录就是版本目录自己时没有「接管」可言（关闭隔离的版本）
        if (samePath(sharedDir, versionDir)) return Result.none();

        // 整合包实例自带一套内容，不能被共用目录的存档 / 资源包污染
        if (Files.isRegularFile(versionDir.resolve(FORCED_MARKER))) return Result.none();

        List<String> adopted = new ArrayList<String>();
        long bytes = 0;
        for (String dir : ADOPT_DIRS) {
            Path from = sharedDir.resolve(dir);
            if (!Files.isDirectory(from)) continue;
            Path to = versionDir.resolve(dir);
            long n = copyTree(from, to);
            if (n > 0) {
                adopted.add(dir);
                bytes += n;
            }
        }
        for (String file : ADOPT_FILES) {
            Path from = sharedDir.resolve(file);
            if (!Files.isRegularFile(from)) continue;
            Path to = versionDir.resolve(file);
            if (Files.isRegularFile(to)) continue;   // 已有一份就别覆盖
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            adopted.add(file);
            bytes += Files.size(to);
        }
        if (!adopted.isEmpty()) {
            Log.info("版本隔离首次初始化，已从共用目录接管：" + join(adopted)
                    + "（" + humanSize(bytes) + "）");
            Log.info("共用目录的 mods 没有复制 —— 模组与游戏版本强绑定，请按版本单独安装");
        }
        return new Result(adopted, bytes, !adopted.isEmpty());
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append("、");
            sb.append(s);
        }
        return sb.toString();
    }

    /** 判断两个目录是否指向同一位置（不要求都存在）。 */
    private static boolean samePath(Path a, Path b) {
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 递归复制目录，返回复制的字节数。
     *
     * <p>目标里已有的文件**不覆盖**：用户可能已经在版本目录里放了同名资源，
     * 接管只是「补上缺失的」，不是「还原成共用目录的样子」。
     */
    private static long copyTree(Path from, Path to) throws IOException {
        final long[] total = {0L};
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Dirs.ensure(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isRegularFile(target)) return FileVisitResult.CONTINUE;
                try {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    total[0] += attrs.size();
                } catch (IOException e) {
                    // 单个文件复制失败（占用 / 权限）不该让整次接手中断
                    Log.warn("接管文件失败，已跳过：" + file.getFileName() + "（" + e.getMessage() + "）");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /** 接管结果，给状态栏 / 日志用。 */
    public static final class Result {
        public final List<String> items;
        public final long bytes;

        Result(List<String> items, long bytes, boolean adopted) {
            this.items = items;
            this.bytes = bytes;
        }

        static Result none() {
            return new Result(new ArrayList<String>(), 0L, false);
        }

        public boolean adopted() {
            return !items.isEmpty();
        }

        public String summary() {
            if (!adopted()) return "";
            return "已把共用目录的 " + join(items) + " 复制到本版本（" + humanSize(bytes) + "）";
        }
    }
}
