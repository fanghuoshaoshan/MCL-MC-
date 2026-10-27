package mcl.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import mcl.config.Account;
import mcl.util.Compat;
import mcl.util.Dirs;
import mcl.util.Log;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 本地 Yggdrasil 服务：让**离线账户在游戏内**也用上自己导入的皮肤。
 *
 * <p>为什么需要这个东西：原版客户端只会向 {@code sessionserver} 要皮肤的 URL，
 * 拿到之后再去那个 URL 下 PNG，本地文件它根本不看。所以「把 PNG 放进 skins 目录」
 * 只能改启动器里的预览，进游戏还是 Steve —— 这是 M24 里跟用户说清楚过的事实。
 *
 * <p>HMCL 的做法（源码 {@code auth/offline/YggdrasilServer}）：

 * <ol>
 *   <li>启动时注入 {@code -javaagent:authlib-injector.jar=http://127.0.0.1:端口}，
 *       authlib-injector 会改掉客户端里写死的 Yggdrasil API 地址；</li>
 *   <li>那个端口上跑一个只认自己账户的迷你服务，把 {@code profile} 请求里的
 *       textures 属性指回本地 PNG；</li>
 *   <li>textures 必须由服务端私钥签名，公钥在根路径的 metadata 里给出来，
 *       authlib-injector 拿它对签。</li>
 * </ol>
 *
 * <p>我们照同一套路做。区别在于只服务「当前这一局的那个账户」——
 * 不做登录、不做多人联机，起一个用完即停的临时服务即可，
 * 端口交给系统分配，退出游戏就关掉。
 *
 * <p><b>边界</b>：单人 / 本地存档能看到皮肤；连别人的离线服务器时，
 * 对方服务端不认这套签名，皮肤仍然看不到（HMCL 也一样）。这个不用藏着，
 * 界面上已经写清楚了。
 */
public final class LocalSkinServer {

    private final HttpServer http;
    private final ExecutorService pool;
    private final int port;
    private final String uuid;
    private final String name;
    private final byte[] skinPng;
    private final String model;
    private final PrivateKey privateKey;
    private final String publicKeyPem;
    private volatile boolean stopped;

    private LocalSkinServer(HttpServer http, ExecutorService pool, int port, String uuid, String name,
                            byte[] skinPng, String model, PrivateKey privateKey, String publicKeyPem) {
        this.http = http;
        this.pool = pool;
        this.port = port;
        this.uuid = uuid;
        this.name = name;
        this.skinPng = skinPng;
        this.model = model;
        this.privateKey = privateKey;
        this.publicKeyPem = publicKeyPem;
    }

    /**
     * 起服务。调用方负责在游戏退出后 {@link #stop()}。
     *
     * @param launcherDir 启动器目录，签名密钥缓存在它的 cache 下
     * @param account     当前账户（取 uuid / 名字 / 模型）
     * @param skin        已经规整成 64×64 的皮肤
     */
    public static LocalSkinServer start(Path launcherDir, Account account, BufferedImage skin) throws IOException {
        if (account == null) throw new IOException("没有账户");
        if (skin == null) throw new IOException("没有皮肤");
        String id = normalizeUuid(account.uuid);
        if (id.isEmpty()) throw new IOException("账户没有 UUID");

        byte[] png = encodePng(skin);
        KeyPair kp;
        String pem;
        try {
            kp = loadOrCreateKeyPair(launcherDir.resolve("cache").resolve("local-skin"));
            pem = toPem(kp.getPublic().getEncoded());
        } catch (Exception e) {
            throw new IOException("生成本地皮肤签名密钥失败：" + e.getMessage(), e);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "mcl-local-skin");
            t.setDaemon(true);
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(2, tf);

        LocalSkinServer self = new LocalSkinServer(server, pool, server.getAddress().getPort(), id,
                account.username, png, modelOf(account), kp.getPrivate(), pem);
        server.createContext("/", self::handle);
        server.setExecutor(pool);
        server.start();
        Log.info("本地皮肤服务已启动：" + self.apiRoot() + "（" + account.username + "）");
        return self;
    }

    /** 给 authlib-injector 用的 API 根地址。 */
    public String apiRoot() {
        return "http://127.0.0.1:" + port;
    }

    public String agentArg(Path injectorJar) {
        return AuthlibInjector.agentArg(injectorJar, apiRoot());
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        try {
            http.stop(0);
        } catch (Exception ignored) {
        }
        pool.shutdownNow();
        Log.info("本地皮肤服务已关闭（端口 " + port + "）");
    }

    // ------------------------------------------------------------------ 路由

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                empty(ex, 204);
                return;
            }
            if (path.isEmpty() || "/".equals(path)) {
                body(ex, 200, "application/json", metadata().getBytes(StandardCharsets.UTF_8));
                return;
            }
            String profilePrefix = "/sessionserver/session/minecraft/profile/";
            if (path.startsWith(profilePrefix)) {
                String id = normalizeUuid(path.substring(profilePrefix.length()));
                if (id.isEmpty() || !id.equalsIgnoreCase(uuid)) {
                    empty(ex, 204);   // 别的玩家：我们不知道，让它走原版逻辑
                    return;
                }
                body(ex, 200, "application/json", profile().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/textures/skin.png".equals(path)) {
                // 这行是「真的生效了」的证据：日志里看到它，说明游戏确实来拉过本地皮肤
                Log.info("本地皮肤已生效：游戏取走了 " + name + " 的纹理");
                body(ex, 200, "image/png", skinPng);
                return;
            }
            // 客户端在多人游戏时才用得到；单人世界里不触发，给个空响应让它可以继续
            if (path.startsWith("/sessionserver/session/minecraft/join")
                    || path.startsWith("/sessionserver/session/minecraft/hasJoined")
                    || path.startsWith("/sessionserver/session/minecraft/profile")) {
                empty(ex, 204);
                return;
            }
            if (path.startsWith("/authserver/")) {
                authserver(ex, path);
                return;
            }
            empty(ex, 404);
        } catch (Exception e) {
            Log.warn("本地皮肤服务处理 " + ex.getRequestURI() + " 出错：" + e);
            try {
                empty(ex, 500);
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    /**
     * 认证端点。游戏客户端启动时不会主动调这些，但外置登录的刷新逻辑有可能碰到，
     * 返回一个「就是这个角色」的假令牌，免得它拿 404 去炸启动流程。
     */
    private void authserver(HttpExchange ex, String path) throws IOException {
        if (path.endsWith("/validate")) {
            empty(ex, 204);
            return;
        }
        if (path.endsWith("/authenticate") || path.endsWith("/refresh")) {
            JsonObject o = new JsonObject();
            o.addProperty("accessToken", "0");
            o.addProperty("clientToken", "mcl-local-skin");
            JsonObject p = new JsonObject();
            p.addProperty("id", uuid);
            p.addProperty("name", name);
            o.add("selectedProfile", p);
            JsonArray profiles = new JsonArray();
            profiles.add(p);
            o.add("availableProfiles", profiles);
            body(ex, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        empty(ex, 204);
    }

    // ------------------------------------------------------------------ 内容

    private String metadata() {
        JsonObject meta = new JsonObject();
        meta.addProperty("serverName", "MCL 本地皮肤");
        meta.addProperty("implementationName", "MCL");
        meta.addProperty("implementationVersion", "1.0");
        JsonObject o = new JsonObject();
        o.add("meta", meta);
        JsonArray domains = new JsonArray();
        // authlib-injector 会校验纹理地址的域名在不在这张表里
        domains.add("127.0.0.1");
        domains.add("localhost");
        o.add("skinDomains", domains);
        o.addProperty("signaturePublickey", publicKeyPem);
        return o.toString();
    }

    private String profile() throws Exception {
        JsonObject textures = new JsonObject();
        textures.addProperty("timestamp", System.currentTimeMillis());
        textures.addProperty("profileId", uuid);
        textures.addProperty("profileName", name);
        JsonObject skins = new JsonObject();
        JsonObject skin = new JsonObject();
        skin.addProperty("url", apiRoot() + "/textures/skin.png");
        if ("slim".equals(model)) {
            JsonObject md = new JsonObject();
            md.addProperty("model", "slim");
            skin.add("metadata", md);
        }
        skins.add("SKIN", skin);
        textures.add("textures", skins);

        // value = Base64(JSON)，signature = Base64(SHA1withRSA(value 的 ASCII 字节))
        String value = Base64.getEncoder().encodeToString(
                textures.toString().getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(privateKey);
        signer.update(value.getBytes(StandardCharsets.US_ASCII));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        JsonObject prop = new JsonObject();
        prop.addProperty("name", "textures");
        prop.addProperty("value", value);
        prop.addProperty("signature", signature);
        JsonArray props = new JsonArray();
        props.add(prop);

        JsonObject out = new JsonObject();
        out.addProperty("id", uuid);
        out.addProperty("name", name);
        out.add("properties", props);
        return out.toString();
    }

    // ------------------------------------------------------------------ 工具

    private static void body(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        addCors(ex);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    private static void empty(HttpExchange ex, int code) throws IOException {
        addCors(ex);
        ex.sendResponseHeaders(code, -1);
    }

    private static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    private static byte[] encodePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        if (!ImageIO.write(img, "png", out)) throw new IOException("皮肤无法编码成 PNG");
        return out.toByteArray();
    }

    private static String modelOf(Account a) {
        return ProfileSkins.isSlim(a.uuid, a.skinModel) ? "slim" : "classic";
    }

    /** UUID 统一成 32 位小写无横线：游戏、Yggdrasil 协议都用这种写法。 */
    static String normalizeUuid(String uuid) {
        if (uuid == null) return "";
        return uuid.replace("-", "").trim().toLowerCase();
    }

    /**
     * 签名密钥：第一次生成后缓存，之后复用。
     *
     * <p>放在 {@code cache/local-skin} 下是因为它随时可以重新生成 ——
     * 真丢了也只是换一对密钥，下次启动重新签名，对用户没有任何影响。
     */
    private static KeyPair loadOrCreateKeyPair(Path dir) throws Exception {
        Dirs.ensure(dir);
        Path privFile = dir.resolve("signing.pk8");
        Path pubFile = dir.resolve("signing.x509");
        if (Files.isRegularFile(privFile) && Files.isRegularFile(pubFile)) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privFile)));
                PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pubFile)));
                return new KeyPair(pub, priv);
            } catch (Exception e) {
                Log.warn("本地皮肤签名密钥读不出来，重新生成：" + e.getMessage());
            }
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        Files.write(privFile, kp.getPrivate().getEncoded());
        Files.write(pubFile, kp.getPublic().getEncoded());
        Log.info("已生成本地皮肤签名密钥（" + dir + "）");
        return kp;
    }

    /** X.509 DER → PEM 文本，authlib-injector 只认 PEM。 */
    private static String toPem(byte[] der) {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END PUBLIC KEY-----\n");
        return sb.toString();
    }

    /** 读一行请求体的工具（本项目里给自检用，正常路径不读 body）。 */
    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(Compat.readAll(in), StandardCharsets.UTF_8);
        }
    }
}
