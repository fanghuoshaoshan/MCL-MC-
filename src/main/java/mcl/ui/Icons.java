package mcl.ui;

import mcl.net.Http;
import mcl.util.Dirs;
import mcl.util.Log;
import mcl.util.WebP;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 项目图标（模组 / 整合包 / 资源包 / 光影包的 logo）异步加载与缓存。
 *
 * <p>列表渲染跑在 EDT 上，绝对不能在里面等网络：{@link #get} 只查缓存，
 * 没有就立刻返回 null（调用方先画字母占位）并在后台线程去抓，
 * 抓到之后回调里重绘一次，图就换上了。
 *
 * <p>两级缓存：
 * <ul>
 *   <li>内存：LRU，存已经缩放到目标尺寸的成品，重绘不用再缩</li>
 *   <li>磁盘：{@code <启动器>/cache/icons/}，存缩放后的 PNG。下次开启动器直接命中，</li>
 * </ul>
 * 失败过的 URL 进程内不再重试 —— 404 / 不是图片这类问题重试也是白跑一趟。
 */
public final class Icons {
    /** 内存里最多留多少张成品图（38×38 的 ARGB 约 5.8KB，400 张不到 2.5MB）。 */
    private static final int CACHE_LIMIT = 400;

    private static final Map<String, BufferedImage> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > CACHE_LIMIT;
                }
            });

    /** 正在下载的 key，避免同一张图被多个可见行同时排队。 */
    private static final Set<String> LOADING = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** 永久失败的 URL（404 / 不是图片），本次运行不再重试。 */
    private static final Set<String> FAILED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** 临时失败（超时 / 连不上）的 URL → 允许重试的时间点，单位毫秒。 */
    private static final Map<String, Long> RETRY_AT = new ConcurrentHashMap<String, Long>();
    /** 网络抖动导致的重试间隔：够短能自己缓过来，够长不至于把列表刷成请求风暴。 */
    private static final long RETRY_DELAY_MS = 60000;

    private static final Object POOL_LOCK = new Object();
    private static ExecutorService pool;
    private static Path cacheDir;

    private Icons() {}

    /** 指定磁盘缓存目录（{@code <启动器>/cache/icons}）；不指定就不落盘。 */
    public static void setCacheDir(Path dir) {
        cacheDir = dir;
    }

    /**
     * 取图标。命中缓存直接返回成品图；否则返回 null 并异步加载，
     * 加载完成后在 EDT 上调用 {@code onReady}（用来触发列表重绘）。
     *
     * @param url     图片地址，可以为 null / 空（此时直接返回 null）
     * @param size    目标边长（正方形，单位像素）
     * @param onReady 加载完成回调，可为 null
     */
    public static BufferedImage get(String url, int size, Runnable onReady) {
        if (url == null || url.isEmpty() || size <= 0) return null;
        String key = size + "|" + url;
        BufferedImage hit = CACHE.get(key);
        if (hit != null) return hit;
        if (FAILED.contains(url)) return null;
        Long retryAt = RETRY_AT.get(url);
        if (retryAt != null) {
            if (System.currentTimeMillis() < retryAt) return null;
            RETRY_AT.remove(url);
        }
        if (!LOADING.add(key)) return null;
        final String fUrl = url;
        final int fSize = size;
        final String fKey = key;
        try {
            pool().submit(new Runnable() {
                @Override
                public void run() {
                    load(fUrl, fSize, fKey, onReady);
                }
            });
        } catch (Exception e) {
            // 关窗时线程池已停，静默放弃
            LOADING.remove(key);
        }
        return null;
    }

    /** 清掉失败标记，让下次重绘重新尝试（换网络 / 换代理后用）。 */
    public static void clearFailures() {
        FAILED.clear();
    }

    private static void load(String url, int size, String key, Runnable onReady) {
        try {
            Path file = fileOf(url, size);
            BufferedImage raw = readDisk(file);
            if (raw == null) {
                byte[] bytes = Http.getStringBytes(url);
                raw = decode(bytes);
                if (raw == null) throw new IOException("不是能识别的图片格式");
            }
            BufferedImage out = round(raw, size);
            CACHE.put(key, out);
            writeDisk(file, out);
            if (onReady != null) SwingUtilities.invokeLater(onReady);
        } catch (Exception e) {
            if (permanent(e)) {
                FAILED.add(url);
                Log.info("图标不可用（改用字母占位）" + url + " — " + e.getMessage());
            } else {
                // 超时 / 连不上：网络抖动，过一会儿再试，别让一张图卡死在字母占位
                RETRY_AT.put(url, System.currentTimeMillis() + RETRY_DELAY_MS);
                Log.info("图标下载失败（稍后重试）" + url + " — " + e.getMessage());
                scheduleRetry(onReady);
            }
        } finally {
            LOADING.remove(key);
        }
    }

    /**
     * 把下载到的字节变成图。
     *
     * <p>Modrinth 的图标是 WebP，ImageIO 解不了，先过一遍自带的 VP8L 解码器；
     * 不是 WebP（CurseForge 的 JPG/PNG）或者是有损 WebP 时再交给 ImageIO。
     */
    public static BufferedImage decode(byte[] bytes) {
        if (WebP.looksLikeWebp(bytes)) {
            try {
                BufferedImage img = WebP.decode(bytes);
                if (img != null) return img;
            } catch (Exception e) {
                Log.info("WebP 解码失败，尝试按普通图片读取：" + e.getMessage());
            }
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 到点了主动催一次重绘，让失败的那张图有机会重试。
     *
     * <p>没有这一步的话，列表只要不滚动就不会重绘，也就永远不会重新发起请求 ——
     * 网络恢复后用户还得自己动一下滚轮才看得见图标。
     */
    private static void scheduleRetry(final Runnable onReady) {
        if (onReady == null) return;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                javax.swing.Timer timer = new javax.swing.Timer((int) RETRY_DELAY_MS,
                        new java.awt.event.ActionListener() {
                            @Override
                            public void actionPerformed(java.awt.event.ActionEvent e) {
                                onReady.run();
                            }
                        });
                timer.setRepeats(false);
                timer.start();
            }
        });
    }

    /**
     * 区分"这辈子的失败"和"一时的失败"。
     *
     * <p>404 / 403 / 不是图片 —— 重试也是白跑，直接钉死；
     * 超时、连接被拒、5xx、429 —— 大概率是网络抖动，值得等一会儿再来一次。
     */
    private static boolean permanent(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("不是能识别的图片格式")) return true;
        if (msg.startsWith("HTTP ")) {
            try {
                int code = Integer.parseInt(msg.substring(5, 8).trim());
                return code >= 400 && code < 500 && code != 408 && code != 429;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    // ---------------- 磁盘缓存 ----------------

    private static Path fileOf(String url, int size) {
        Path dir = cacheDir;
        if (dir == null) return null;
        return dir.resolve(sha1(url) + "-" + size + ".png");
    }

    private static BufferedImage readDisk(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try (InputStream in = Files.newInputStream(file)) {
            return ImageIO.read(in);
        } catch (Exception e) {
            // 半截文件 / 损坏的缓存：删掉当作没有，下次重新下
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private static void writeDisk(Path file, BufferedImage img) {
        if (file == null) return;
        try {
            Dirs.ensureParent(file);
            // 先写临时文件再改名：写到一半退出不会留下半张图被当成好缓存读回来
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            ImageIO.write(img, "png", tmp.toFile());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Log.info("图标缓存写入失败（不影响显示）：" + e.getMessage());
        }
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // ---------------- 画图 ----------------

    /** 缩放到 size×size 并裁成圆角，返回可直接塞进 ImageIcon 的图。 */
    static BufferedImage round(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            float r = Math.max(6f, size * 0.24f);
            g.setClip(new RoundRectangle2D.Float(0, 0, size, size, r, r));
            g.drawImage(scale(src, size), 0, 0, null);
            g.setClip(null);
            // 描一圈极淡的边：纯白 logo 落进白卡片里否则看不出边界
            g.setColor(new Color(0, 0, 0, 26));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, size - 1f, size - 1f, r, r));
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 居中裁成正方形后缩到目标尺寸；逐级减半，缩小比例大时也不会出现锯齿和摩尔纹。 */
    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage cur = centerSquare(src);
        while (cur.getWidth() / 2 >= size) {
            int half = cur.getWidth() / 2;
            BufferedImage next = new BufferedImage(half, half, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(cur, 0, 0, half, half, null);
            } finally {
                g.dispose();
            }
            cur = next;
        }
        if (cur.getWidth() == size) return cur;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(cur, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 非正方形的 logo（少数整合包是长条或 4:3）居中裁成正方形，避免被拉变形。 */
    private static BufferedImage centerSquare(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w == h && src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, side, side, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static ExecutorService pool() {
        synchronized (POOL_LOCK) {
            if (pool == null || pool.isShutdown()) {
                // 4 个线程够用：一屏可见十来行，再多也是排队等同一幅 CDN
                pool = Executors.newFixedThreadPool(4, new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "mcl-icon-" + seq.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                });
            }
            return pool;
        }
    }
}
