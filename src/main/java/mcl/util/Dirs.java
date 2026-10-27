package mcl.util;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 建目录的唯一入口。
 *
 * <p>为什么不用裸的 {@code Files.createDirectories}：当「同名文件已经占着这个位置」时，
 * 它会抛 {@code FileAlreadyExistsException}，消息里只有路径本身，看不出到底哪儿错了。
 * 真实事故：用户机器的 {@code cache/modpacks} 里躺着一个手滑存进去的 HTML（2.7 KB），
 * 于是所有整合包下载都在建父目录这一步**秒失败**，三次重试全在同一秒，日志还写着
 * 「CDN 都没给这个文件」——把排查方向带偏了整整两轮。
 *
 * <p><b>实现上有个坑，踩过一次：</b>不能写成「先 {@code isDirectory} 判断、再
 * {@code createDirectories}」——两步之间是 TOCTOU 窗口。32 线程并发下载 Java 运行时那次，
 * 刚 mkdir 出来的空目录在另一个线程眼里出现 {@code exists=true} 而 {@code isDirectory=false}
 * 的一瞬，于是它把一个好端端的空目录改名成 {@code bin.bak-…}，日志里刷出十几条
 * 「目录位置被文件占用」。正确顺序是**先建**：{@code createDirectories} 对
 * 「已存在且是目录」是静默通过的，只有真被文件占住才抛 {@code FileAlreadyExistsException}。
 *
 * <p>另外这里多做一步：真被文件占住时，先改名备份成 {@code xxx.bak-时间戳} 再建目录。
 * 备份不删，用户误放的东西还能自己找回；若挪走的其实是个空目录（并发误判的残留），
 * 直接清掉，不留垃圾。
 */
public final class Dirs {
    /** 同一路径的 ensure 串行化，避免两个线程同时去「让路」。 */
    private static final ConcurrentMap<String, Object> LOCKS = new ConcurrentHashMap<String, Object>();

    private Dirs() {
    }

    /** 确保 dir 是一个可用的目录；被同名文件占用时改名让路。 */
    public static Path ensure(Path dir) throws IOException {
        if (dir == null) return null;
        try {
            // 快路径：已存在且是目录就直接过；不存在就建出来。两种都不抛。
            return Files.createDirectories(dir);
        } catch (FileAlreadyExistsException occupied) {
            // 走到这里才是「位置被同名文件占住」
        }
        synchronized (lockFor(dir)) {
            try {
                return Files.createDirectories(dir);   // 双检：可能已经被别的线程收拾好了
            } catch (FileAlreadyExistsException stillOccupied) {
                // 仍被占着，往下清场
            }
            Path bak = backupName(dir);
            boolean moved = false;
            try {
                Files.move(dir, bak, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (IOException e) {
                // 改名也失败（被别的进程占着 / 没权限）时只能删，删不掉再把原因抛出去
                try {
                    Files.deleteIfExists(dir);
                    Log.warn("目录位置被文件占用，已删除：" + dir);
                } catch (IOException e2) {
                    throw new IOException("目录位置被文件占用且无法清理：" + dir, e2);
                }
            }
            if (moved) {
                if (isEmptyDir(bak)) {
                    // 并发误判：挪走的是个刚建好的空目录，直接清掉，别在用户盘上堆垃圾
                    deleteQuietly(bak);
                    Log.warn("目录刚建出来就被并发当成文件，已复原（忽略这次误判）：" + dir);
                } else {
                    Log.warn("目录位置被文件占用，已改名让路：" + dir + " -> " + bak.getFileName());
                }
            }
            return Files.createDirectories(dir);
        }
    }

    /** 为某个文件路径准备好它的父目录。 */
    public static Path ensureParent(Path file) throws IOException {
        if (file == null) return null;
        return ensure(file.toAbsolutePath().getParent());
    }

    /**
     * 静默版：不写日志。给 {@link Log} 自己用 —— 否则「logs 目录被文件占用」时
     * Dirs.ensure → Log.warn → Log.write → Dirs.ensure 会无限递归。
     */
    static Path ensureQuiet(Path dir) throws IOException {
        if (dir == null) return null;
        try {
            return Files.createDirectories(dir);
        } catch (FileAlreadyExistsException occupied) {
            Path bak = backupName(dir);
            try {
                Files.move(dir, bak, StandardCopyOption.REPLACE_EXISTING);
                if (isEmptyDir(bak)) deleteQuietly(bak);
            } catch (IOException e) {
                Files.deleteIfExists(dir);
            }
            return Files.createDirectories(dir);
        }
    }

    private static Object lockFor(Path dir) {
        String key = dir.toAbsolutePath().normalize().toString().toLowerCase(java.util.Locale.ROOT);
        Object lock = LOCKS.get(key);
        if (lock == null) {
            Object fresh = new Object();
            Object prev = LOCKS.putIfAbsent(key, fresh);
            lock = prev == null ? fresh : prev;
        }
        return lock;
    }

    private static Path backupName(Path dir) {
        String name = String.valueOf(dir.getFileName()) + ".bak-" + System.currentTimeMillis();
        return dir.resolveSibling(name);
    }

    private static boolean isEmptyDir(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return !s.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
