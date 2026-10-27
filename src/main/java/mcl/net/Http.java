package mcl.net;

import mcl.util.Dirs;
import mcl.util.Compat;
import mcl.util.Digest;
import mcl.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;

/**
 * 基于 {@link HttpURLConnection} 的极简下载器，支持重试、SHA1 校验、代理与进度回调。
 *
 * <p>故意不用 {@code java.net.http.HttpClient}：那是 Java 11 才有的 API，而本项目要能跑在
 * Java 8 上（兼容所有已装 Java 的机器）。HttpURLConnection 从 Java 1.1 就存在，行为稳定，
 * 用来做启动器的元数据请求与文件下载完全够用。
 *
 * <p>重定向自己跟（默认只跟 GET，POST 的 307 要原样重发 body），代理自己填
 * {@code Proxy-Authorization}，避免依赖全局 Authenticator 带来的副作用。
 */
public final class Http {
    public static final String USER_AGENT = "MCL/0.3.0 (Minecraft Launcher; +https://github.com/)";

    private static final int CONNECT_TIMEOUT_MS = 15000;

    /** 当前代理；{@link Proxy#NO_PROXY} 表示直连。 */
    private static volatile Proxy proxy = Proxy.NO_PROXY;
    /** HTTP 代理的认证头，已 base64 好；null 表示无需认证。 */
    private static volatile String proxyAuth;
    /** 当前代理描述，仅用于界面展示与日志 */
    private static volatile String proxyLabel = "直连";

    private Http() {}

    // ---------------- 代理 ----------------

    /**
     * 配置网络代理。HTTP 代理用请求头认证；SOCKS5 的认证走一次性的全局 Authenticator。
     *
     * @param mode none / http / socks
     */
    public static synchronized void configure(String mode, String host, int port, String user, String pass) {
        String m = mode == null ? "none" : mode.toLowerCase();
        if (m.equals("none") || host == null || Compat.isBlank(host) || port <= 0) {
            proxy = Proxy.NO_PROXY;
            proxyAuth = null;
            proxyLabel = "直连";
            return;
        }
        InetSocketAddress addr = new InetSocketAddress(host.trim(), port);
        if (addr.isUnresolved()) {
            proxy = Proxy.NO_PROXY;
            proxyAuth = null;
            proxyLabel = "直连（代理地址无法解析：" + host + "）";
            return;
        }
        boolean socks = m.equals("socks") || m.equals("socks5");
        Proxy.Type type = socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        Proxy p = new Proxy(type, addr);
        String auth = null;
        if (user != null && !user.isEmpty()) {
            final String u = user;
            final char[] pw = (pass == null ? "" : pass).toCharArray();
            if (socks) {
                Authenticator.setDefault(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(u, pw);
                    }
                });
            } else {
                String raw = u + ":" + (pass == null ? "" : pass);
                auth = "Basic " + Base64.getEncoder()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            }
        }
        proxy = p;
        proxyAuth = auth;
        proxyLabel = (socks ? "SOCKS5 " : "HTTP ") + host.trim() + ":" + port;
        Log.info("网络代理已启用：" + proxyLabel);
    }

    public static String proxyLabel() {
        return proxyLabel;
    }

    @FunctionalInterface
    public interface BytesListener {
        void onBytes(int count);
    }

    // ---------------- 请求 ----------------

    private static boolean bodyless(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method);
    }

    /**
     * 发请求并返回已经拿到响应头的连接；重定向在这里统一跟掉。
     *
     * @param body 为 null 表示无请求体
     */
    private static HttpURLConnection open(String url, String method, Map<String, String> headers,
                                          byte[] body, int readTimeoutSec) throws IOException {
        String current = url;
        String verb = method;
        byte[] payload = body;
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(current);
            HttpURLConnection c = (HttpURLConnection) (proxy == Proxy.NO_PROXY
                    ? u.openConnection() : u.openConnection(proxy));
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(readTimeoutSec * 1000);
            c.setRequestMethod(verb);
            c.setRequestProperty("User-Agent", USER_AGENT);
            // 不让服务器 gzip：否则 content-length 与实际读到的字节对不上
            c.setRequestProperty("Accept-Encoding", "identity");
            if (proxyAuth != null && proxy != null && proxy.type() == Proxy.Type.HTTP) {
                c.setRequestProperty("Proxy-Authorization", proxyAuth);
            }
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        c.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (payload != null && !bodyless(verb)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(payload.length);
                OutputStream out = c.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            }
            int code = c.getResponseCode();
            boolean redirect = code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
            if (!redirect) return c;

            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc == null) throw new IOException("重定向缺少 Location：" + current);
            current = new URL(u, loc).toString();
            if (code == 301 || code == 302 || code == 303) {
                // 和浏览器一致：这几种之后退化成 GET，body 丢掉
                verb = "GET";
                payload = null;
            }
        }
        throw new IOException("重定向次数过多：" + url);
    }

    private static Charset charsetOf(HttpURLConnection c) {
        String ct = c.getContentType();
        if (ct != null) {
            String lower = ct.toLowerCase();
            int at = lower.indexOf("charset=");
            if (at >= 0) {
                String name = ct.substring(at + 8).trim();
                int semi = name.indexOf(';');
                if (semi >= 0) name = name.substring(0, semi);
                name = name.trim().replace("\"", "");
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {
                    // 服务端偶尔给个不存在的字符集名，按 UTF-8 处理
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 读响应体（>=400 时读 errorStream，OAuth 的错误 JSON 就在里面）。 */
    private static byte[] readBody(HttpURLConnection c) throws IOException {
        InputStream in;
        try {
            in = c.getInputStream();
        } catch (IOException e) {
            in = c.getErrorStream();
            if (in == null) throw e;
        }
        if (in == null) return new byte[0];
        try {
            return Compat.readAll(in);
        } finally {
            in.close();
        }
    }

    private static String readText(HttpURLConnection c) throws IOException {
        byte[] data = readBody(c);
        return new String(data, charsetOf(c));
    }

    /** 一次 GET，返回 2xx 的文本；非 2xx 抛 IOException。 */
    private static String getOnce(String url, Map<String, String> headers, int readTimeoutSec) throws IOException {
        HttpURLConnection c = open(url, "GET", headers, null, readTimeoutSec);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code + " for " + url);
            return readText(c);
        } finally {
            c.disconnect();
        }
    }

    public static String getString(String url) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return getOnce(url, null, 30);
            } catch (IOException e) {
                last = e;
            }
            sleep(400L * attempt);
        }
        throw last != null ? last : new IOException("请求失败: " + url);
    }

    public static String getString(String... urls) throws IOException {
        IOException last = null;
        for (String u : urls) {
            try {
                return getString(u);
            } catch (IOException e) {
                last = e;
                Log.warn("请求失败：" + u + " -> " + e.getMessage());
            }
        }
        throw last != null ? last : new IOException("没有可用的地址");
    }

    /** 只尝试一次（多地址候选时用，避免每个地址都白等三轮超时）。 */
    public static String getStringOnce(String url) throws IOException {
        return getOnce(url, null, 20);
    }

    /** 带一个自定义请求头（例如 CurseForge 的 x-api-key）。 */
    public static String getStringWithHeader(String url, String header, String value) throws IOException {
        if (header == null) return getOnce(url, null, 30);
        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put(header, value);
        return getOnce(url, h, 30);
    }

    /** 带多个自定义请求头的 GET（CurseForge 要同时给 x-api-key 和 Accept）。 */
    public static String getStringWithHeaders(String url, Map<String, String> headers) throws IOException {
        return getOnce(url, headers, 30);
    }

    /** 带自定义请求头的 POST JSON（CurseForge 的 /v1/mods 批量查询要用 key 鉴权）。 */
    public static String postJsonWithHeaders(String url, String body, Map<String, String> headers)
            throws IOException {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Content-Type", "application/json");
        if (headers != null) h.putAll(headers);
        return postWithHeaders(url, payload, h, false);
    }

    private static String postWithHeaders(String url, byte[] payload, Map<String, String> h,
                                          boolean accept400) throws IOException {
        HttpURLConnection c = open(url, "POST", h, payload, 30);
        try {
            int code = c.getResponseCode();
            String text = readText(c);
            // OAuth 的错误响应也是 400，把 body 交给调用方解析（里面有 error 字段）
            if (code / 100 == 2 || (accept400 && code == 400)) return text;
            throw new IOException("HTTP " + code + " for " + url);
        } finally {
            c.disconnect();
        }
    }
    /** 取原始字节（皮肤 PNG 之类的小文件）。 */
    public static byte[] getStringBytes(String url) throws IOException {
        HttpURLConnection c = open(url, "GET", null, null, 30);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code + " for " + url);
            return readBody(c);
        } finally {
            c.disconnect();
        }
    }

    /** POST JSON 并返回响应体（Modrinth 的批量查询等）。 */
    public static String postJson(String url, String body) throws IOException {
        return post(url, body, "application/json", false);
    }

    /** POST 表单（微软 OAuth 的 device code / token 接口用）。 */
    public static String postForm(String url, String body) throws IOException {
        return post(url, body, "application/x-www-form-urlencoded", true);
    }

    private static String post(String url, String body, String contentType, boolean accept400) throws IOException {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Content-Type", contentType);
        HttpURLConnection c = open(url, "POST", h, payload, 30);
        try {
            int code = c.getResponseCode();
            String text = readText(c);
            // OAuth 的错误响应也是 400，把 body 交给调用方解析（里面有 error 字段）
            if (code / 100 == 2 || (accept400 && code == 400)) return text;
            throw new IOException("HTTP " + code + " for " + url);
        } finally {
            c.disconnect();
        }
    }

    /**
     * 上传文件（multipart/form-data），用于给外置登录账户上传皮肤。
     *
     * @param fields 额外表单字段，可为 null
     */
    public static String putMultipart(String url, Map<String, String> headers,
                                      Map<String, String> fields,
                                      String fileField, Path file, String mime)
            throws IOException {
        String boundary = "----MCLBoundary" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Charset cs = StandardCharsets.UTF_8;
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                buf.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + e.getKey()
                        + "\"\r\n\r\n" + e.getValue() + "\r\n").getBytes(cs));
            }
        }
        if (file != null && fileField != null) {
            buf.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"" + file.getFileName() + "\"\r\nContent-Type: " + mime
                    + "\r\n\r\n").getBytes(cs));
            buf.write(Files.readAllBytes(file));
            buf.write("\r\n".getBytes(cs));
        }
        buf.write(("--" + boundary + "--\r\n").getBytes(cs));

        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (headers != null) h.putAll(headers);
        HttpURLConnection c = open(url, "PUT", h, buf.toByteArray(), 60);
        try {
            int code = c.getResponseCode();
            String text = readText(c);
            if (code / 100 == 2) return text;
            throw new IOException("HTTP " + code + " for " + url + " " + text);
        } finally {
            c.disconnect();
        }
    }

    // ---------------- 下载 ----------------

    /** 下载到目标文件（先写 .part 再原子移动），完成后校验 SHA1。 */
    public static void download(String url, Path dest, String sha1, long expectedSize, BytesListener listener)
            throws IOException {
        downloadWithHeaders(url, dest, null, listener, sha1, expectedSize);
    }

    /** 带自定义请求头的下载（OptiFine 之类需要 Referer 的站点）。 */
    public static void downloadWithHeaders(String url, Path dest, Map<String, String> headers,
                                           BytesListener listener) throws IOException {
        downloadWithHeaders(url, dest, headers, listener, null, 0);
    }

    /**
     * 同一个目标文件的下载串行化。
     *
     * <p>Downloader 层已经按 dest 去过重，但那里管不了跨实例的情况（Forge 安装器、
     * 整合包安装、模组更新各自 new 一个 Downloader）。两个线程写同一个 {@code .part}
     * 会把字节踩烂，sha1 怎么都过不了 —— 这里兜最后一道。
     */
    private static final java.util.concurrent.ConcurrentMap<String, Object> FILE_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<String, Object>();

    private static void downloadWithHeaders(String url, Path dest, Map<String, String> headers,
                                            BytesListener listener, String sha1, long expectedSize)
            throws IOException {
        String key = dest.toAbsolutePath().normalize().toString().toLowerCase(java.util.Locale.ROOT);
        Object fresh = new Object();
        Object prev = FILE_LOCKS.putIfAbsent(key, fresh);
        Object lock = prev == null ? fresh : prev;
        synchronized (lock) {
            downloadLocked(url, dest, headers, listener, sha1, expectedSize);
        }
    }

    private static void downloadLocked(String url, Path dest, Map<String, String> headers,
                                       BytesListener listener, String sha1, long expectedSize)
            throws IOException {
        Dirs.ensure(dest.toAbsolutePath().getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        // 断点续传：上次断在中途时 .part 里已经是真文件的前半段，接着下就行。
        // 只有知道期望大小才敢续 —— 否则无从判断残片是不是同一个文件。
        long have = 0;
        if (expectedSize > 0 && Files.isRegularFile(part)) {
            try {
                have = Files.size(part);
            } catch (IOException ignored) {
                have = 0;
            }
            if (have >= expectedSize) {
                // 残片不比目标小，肯定不是半截文件，别拿它续
                deleteQuietly(part);
                have = 0;
            }
        }
        Map<String, String> h = headers == null
                ? new java.util.HashMap<String, String>() : new java.util.HashMap<String, String>(headers);
        if (have > 0) h.put("Range", "bytes=" + have + "-");
        HttpURLConnection c = open(url, "GET", h, null, 900);
        try {
            int code = c.getResponseCode();
            if (code == 416) {
                // 服务器不认这个区间（残片和远端已经不是同一个文件），丢掉重来
                deleteQuietly(part);
                throw new IOException("HTTP 416 断点续传被拒绝：" + url);
            }
            if (code / 100 != 2) throw new IOException("HTTP " + code + " " + url);
            boolean resume = have > 0 && code == 206;
            if (!resume) have = 0;
            long total = c.getContentLengthLong();
            if (total > 0) total += have;   // 206 的 Content-Length 只是剩余部分
            if (total <= 0) total = expectedSize;
            long done = have;
            InputStream in = c.getInputStream();
            OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                    resume ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (listener != null) listener.onBytes(n);
                    Throttle.acquire(n);
                }
            } catch (IOException e) {
                // 这里故意不删 .part：留着让下一次重试接着下。
                // 原来一断就删，54 MB 得从头再来，网络一抖就永远下不完。
                throw e;
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (total > 0 && done != total) {
                if (done > total) deleteQuietly(part);  // 多出来的字节不能用，别拿去续传
                throw new IOException("文件大小不符（期望 " + total + "，实际 " + done + "）：" + url);
            }
        } finally {
            c.disconnect();
        }
        if (sha1 != null && !sha1.isEmpty()) {
            String actual = Digest.sha1(part);
            if (!sha1.equalsIgnoreCase(actual)) {
                // 期望值和实际值都要写出来：只写「校验失败」时，没法判断是文件下错了
                // 还是元数据给的 sha1 本来就不对（这两种情况的修法完全不一样）
                deleteQuietly(part);
                throw new IOException("SHA1 校验失败（期望 " + head(sha1) + "，实际 " + head(actual)
                        + "）：" + url);
            }
        }
        try {
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 只留前 8 位，日志/弹窗里够辨认，又不至于糊一屏。 */
    private static String head(String s) {
        if (s == null) return "null";
        return s.length() <= 8 ? s : s.substring(0, 8) + "…";
    }

    private static void deleteQuietly(Path p) {        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
