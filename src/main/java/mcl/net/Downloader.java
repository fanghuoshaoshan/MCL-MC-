package mcl.net;

import mcl.util.Compat;
import mcl.util.Digest;
import mcl.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程下载队列：聚合进度、按文件失败自动换源重试、可取消。
 * 与 HMCL 的 DownloadTask 思路一致：官方 URL 存元数据，下载时映射镜像，失败回退官方。
 */
public class Downloader {
    public interface Listener {
        void onProgress(int filesDone, int filesTotal, long bytesDone, long bytesTotal, String currentFile);

        default void onMessage(String message) {
        }
    }

    private static final class Task {
        final String name;
        final String url;
        final String sha1;
        final long size;
        final Path dest;
        final boolean direct;

        Task(String name, String url, Path dest, String sha1, long size, boolean direct) {
            this.name = name;
            this.url = url;
            this.dest = dest;
            this.sha1 = sha1;
            this.size = size;
            this.direct = direct;
        }
    }

    private final Source source;
    private final int threads;
    private final List<Task> tasks = new ArrayList<>();
    /** dest → 已加入的任务，用于去重（见 {@link #enqueue}）。 */
    private final java.util.Map<String, Task> byDest = new java.util.LinkedHashMap<>();
    private volatile boolean cancelled;
    private volatile long lastNotify;
    /** 最近一次失败的原因：调用方拿到 failed > 0 时可以把这条带出去，别只给一句「下载失败」。 */
    private volatile IOException lastError;

    public IOException lastError() {
        return lastError;
    }

    public Downloader(Source source, int threads) {
        this.source = source;
        this.threads = Math.max(1, Math.min(256, threads));
    }

    public Downloader add(String name, String url, Path dest, String sha1, long size) {
        if (url == null || url.isEmpty() || dest == null) return this;
        return enqueue(new Task(name, url, dest, sha1, size, false));
    }

    /** 不走镜像映射，只用给定地址（镜像上没有的资源，例如 Mojang 的 Java 运行时对象）。 */
    public Downloader addDirect(String name, String url, Path dest, String sha1, long size) {
        if (url == null || url.isEmpty() || dest == null) return this;
        return enqueue(new Task(name, url, dest, sha1, size, true));
    }

    /**
     * 同一个目标文件只留一个任务。
     *
     * <p>必须去重的原因：Forge 的安装器有三份清单（installer profile / version json /
     * version json 里的 install 段），同一个库会各出现一次，于是同一路径被加了 2~3 遍。
     * 多线程一跑，两个线程拿着**同一个 .part** 边下边写，字节直接互相踩烂，
     * sha1 必然对不上；失败重试时两个线程又同时重来，三次全撞在一起，永远好不了。
     * 现场表现是「只有 fmlloader / fmlearlydisplay 这两个 jar 死活下不下来，
     * 但磁盘上那个 jar 拿 sha1sum 一验又是对的」——因为其中一个任务确实写成功了。
     *
     * <p>重复时保留信息更全的那份：有 sha1 的优先（能校验），其次 size 大的。
     */
    private Downloader enqueue(Task t) {
        String key = destKey(t.dest);
        Task prev = byDest.get(key);
        if (prev != null) {
            if (Compat.isBlank(prev.sha1) && !Compat.isBlank(t.sha1)) {
                tasks.set(tasks.indexOf(prev), t);
                byDest.put(key, t);
                Log.warn("同一目标文件被重复加入下载队列，已换成带校验的那份：" + t.name);
            } else if (!Compat.isBlank(prev.sha1) && !Compat.isBlank(t.sha1)
                    && !prev.sha1.equalsIgnoreCase(t.sha1)) {
                Log.warn("同一目标文件出现两个不同的 SHA1，保留先加入的那份：" + t.name);
            }
            return this;
        }
        byDest.put(key, t);
        tasks.add(t);
        return this;
    }

    private static String destKey(Path dest) {
        return dest.toAbsolutePath().normalize().toString().toLowerCase(java.util.Locale.ROOT);
    }


    public int size() {
        return tasks.size();
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** 已存在且大小/校验和匹配的文件会被跳过。返回失败文件数。 */
    public int run(Listener listener) throws IOException {
        List<Task> pending = new ArrayList<>();
        for (Task t : tasks) {
            if (cancelled) return 0;
            if (isComplete(t)) continue;
            pending.add(t);
        }
        if (pending.isEmpty()) {
            if (listener != null) listener.onProgress(tasks.size(), tasks.size(), 0, 0, "文件已齐全");
            return 0;
        }
        long totalBytes = 0;
        for (Task t : pending) if (t.size > 0) totalBytes += t.size;

        final int fileTotal = pending.size();
        final AtomicInteger fileDone = new AtomicInteger();
        final AtomicLong bytesDone = new AtomicLong();
        final AtomicInteger failed = new AtomicInteger();
        final long total = totalBytes;

        int poolSize = Math.max(1, Math.min(threads, fileTotal));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread th = new Thread(r, "mcl-download");
            th.setDaemon(true);
            return th;
        });
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Task t : pending) {
                Callable<Void> job = () -> {
                    if (cancelled) return null;
                    try {
                        boolean ok = fetch(t, bytesDone, total, listener, fileTotal, fileDone);
                        if (!ok) failed.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        lastError = e instanceof IOException ? (IOException) e
                                : new IOException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                        Log.warn("下载失败：" + t.name + " -> " + e.getMessage());
                    } finally {
                        fileDone.incrementAndGet();
                        notifyProgress(listener, fileDone.get(), fileTotal, bytesDone.get(), total, t.name, true);
                    }
                    return null;
                };
                futures.add(pool.submit(job));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
        notifyProgress(listener, fileDone.get(), fileTotal, bytesDone.get(), total, "完成", true);
        return failed.get();
    }

    private boolean fetch(Task t, AtomicLong bytesDone, long total, Listener listener, int fileTotal,
                          AtomicInteger fileDone) throws Exception {
        List<String> candidates = new ArrayList<>();
        if (!t.direct) {
            String mapped = source.map(t.url);
            // 镜像进入熔断期时直接跳过，别让每个文件都先失败一次
            if (mapped != null && !mapped.equals(t.url) && source.available()) candidates.add(mapped);
        }
        List<String> cf = forgeCdnCandidates(t.url);
        if (cf.isEmpty()) {
            candidates.add(t.url);
        } else {
            candidates.addAll(cf);
        }
        Exception last = null;
        for (int ci = 0; ci < candidates.size(); ci++) {
            String url = candidates.get(ci);
            if (cancelled) return false;
            // 镜像失败要计入熔断；CF 的另一套 CDN 属于官方地址，不算镜像
            boolean mirror = !url.equals(t.url) && !isForgeCdn(url);
            // 镜像只试一次（整站挂了重试纯浪费），官方地址一律 3 次。
            // 这里原来写的是 url.equals(t.url) ? 3 : 1 —— 而 CF 整合包真正能下的那套
            // CDN 恰恰不是原地址，等于「好地址只给一次机会、404 的地址重试三次」，
            // 稍微抖一下就整包失败。
            int attempts = mirror ? 1 : 3;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                if (cancelled) return false;
                try {
                    Http.download(url, t.dest, t.sha1, t.size, n -> {
                        bytesDone.addAndGet(n);
                        notifyProgress(listener, fileDone.get(), fileTotal, bytesDone.get(), total, t.name, false);
                    });
                    source.recordSuccess();
                    return true;
                } catch (IOException e) {
                    last = e;
                    // 一定要落日志：GUI 模式下没有控制台，原来这句原因只有 stdout 能看到，
                    // 用户拿到的 mcl.log 里什么都查不到
                    Log.warn("下载失败 " + hostOf(url) + "（第 " + attempt + "/" + attempts + " 次）"
                            + t.name + " -> " + e.getMessage());
                    if (mirror) {
                        source.recordFailure();
                    } else if (attempt < attempts) {
                        sleep(300L * attempt);
                    }
                }
            }
            if (mirror) Log.warn("镜像地址不可用，改用官方地址：" + t.name);
            else if (ci + 1 < candidates.size()) {
                Log.warn("换下一个地址重试：" + t.name + " -> " + hostOf(candidates.get(ci + 1)));
            }
        }
        if (last != null) throw last;
        return false;
    }

    private static String hostOf(String url) {
        if (url == null) return "?";
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * CurseForge 的文件挂在两套 CDN 上：{@code edge.forgecdn.net} 和
     * {@code mediafilez.forgecdn.net}。
     *
     * <p>两者<b>不是</b>互为镜像 —— mod 的 jar 两个都有，但整合包的 zip 只在
     * {@code mediafilez} 上，用 {@code edge} 的地址去取会直接 404（API 给的
     * {@code downloadUrl} 偏偏就是 edge 的）。实测整合包 100% 命中这个坑，
     * 表现是「下载被拒绝」。所以统一把 mediafilez 排最前，原地址和 edge 依次回落。
     *
     * @return 候选地址（含去重），非 forgecdn 的地址返回空列表表示「不用特殊处理」
     */
    public static List<String> forgeCdnCandidates(String url) {
        List<String> out = new ArrayList<>();
        if (url == null || !url.contains(".forgecdn.net/")) return out;
        String media = url.replace("//edge.forgecdn.net/", "//mediafilez.forgecdn.net/");
        String edge = url.replace("//mediafilez.forgecdn.net/", "//edge.forgecdn.net/");
        for (String u : new String[]{media, url, edge}) if (!out.contains(u)) out.add(u);
        return out;
    }

    private static boolean isForgeCdn(String url) {
        return url != null && url.contains(".forgecdn.net/");
    }

    private boolean isComplete(Task t) {
        if (!Files.isRegularFile(t.dest)) return false;
        try {
            long len = Files.size(t.dest);
            if (t.size > 0 && len != t.size) return false;
            if (t.sha1 != null && !t.sha1.isEmpty()) {
                if (t.size > 0) return true; // 大小一致即认为完整，避免每次启动全量算哈希
                return t.sha1.equalsIgnoreCase(Digest.sha1(t.dest));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void notifyProgress(Listener l, int done, int total, long bytes, long bytesTotal, String current,
                                boolean force) {
        if (l == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastNotify < 80) return;
        lastNotify = now;
        l.onProgress(done, total, bytes, bytesTotal, current);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
