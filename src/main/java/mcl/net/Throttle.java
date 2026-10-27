package mcl.net;

/**
 * 全局下载限速（漏桶 / 预约式）。
 *
 * <p>所有线程共享同一时间轴：限速 1 MB/s 意味着"整个启动器"合计 1 MB/s，
 * 而不是每个下载线程各 1 MB/s（HMCL 的限速也是全局语义）。
 * 允许 300ms 的突发额度，小文件不会被拖成龟速。
 */
public final class Throttle {
    private static final Object LOCK = new Object();
    /** 下一个数据块最早可以结束的时间点（nanoTime） */
    private static long nextFree = System.nanoTime();
    /** 每秒允许的字节数，0 表示不限速 */
    private static long rate;
    private static final long BURST_NANOS = 300_000_000L;

    private Throttle() {}

    /** @param kbps KB/s，0 或负数表示不限速 */
    public static void setLimitKbps(int kbps) {
        synchronized (LOCK) {
            rate = kbps <= 0 ? 0 : (long) kbps * 1024L;
            nextFree = System.nanoTime();
        }
    }

    public static long limitBytesPerSecond() {
        return rate;
    }

    /** 刚读到 {@code bytes} 个字节时调用；必要时阻塞，把平均速率压到限速以内。 */
    public static void acquire(int bytes) {
        long rateNow = rate;
        if (rateNow <= 0 || bytes <= 0) return;
        long waitNanos;
        synchronized (LOCK) {
            long now = System.nanoTime();
            if (nextFree < now) nextFree = now;
            nextFree += (long) (bytes * 1_000_000_000.0 / rateNow);
            waitNanos = nextFree - now - BURST_NANOS;
            // 长时间没人下载后不要积压出巨额欠账
            if (nextFree - now > 2_000_000_000L) {
                nextFree = now + 2_000_000_000L;
                waitNanos = Math.min(waitNanos, 2_000_000_000L);
            }
        }
        if (waitNanos <= 0) return;
        try {
            Thread.sleep(Math.max(1, Math.min(1000, waitNanos / 1_000_000L)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
