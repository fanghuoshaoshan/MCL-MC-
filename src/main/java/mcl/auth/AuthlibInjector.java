package mcl.auth;

import mcl.util.Dirs;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.config.Account;
import mcl.net.Http;
import mcl.util.Json;
import mcl.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 外置登录（authlib-injector / Yggdrasil API）。
 *
 * <p>第三方皮肤站（LittleSkin、Blessing Skin 等）都实现了 Yggdrasil 协议，
 * 只要在启动时通过 {@code -javaagent:authlib-injector.jar=<API 根地址>} 注入，
 * 游戏就会改用该服务器做认证与皮肤加载。
 */
public final class AuthlibInjector {

    /** 官方发布地址，返回 JSON 里有最新的下载链接。 */
    private static final String LATEST = "https://authlib-injector.yushi.moe/artifact/latest.json";

    private AuthlibInjector() {}

    /** 皮肤站信息。 */
    public static final class Server {
        public String apiRoot = "";
        public String serverName = "";
        public String implementation = "";
        public List<String> skinDomains = new ArrayList<>();

        @Override
        public String toString() {
            return serverName.isEmpty() ? apiRoot : serverName;
        }
    }

    /** 读取皮肤站元数据（同时用来验证地址是否可用）。 */
    public static Server metadata(String apiRoot) throws IOException {
        String root = normalize(apiRoot);
        String body = Http.getStringWithHeader(root, "Accept", "application/json");
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        Server s = new Server();
        s.apiRoot = root;
        JsonObject meta = Json.obj(o, "meta");
        s.serverName = Json.str(meta, "serverName", "");
        s.implementation = Json.str(meta, "implementationName", "");
        if (s.serverName.isEmpty()) s.serverName = root;
        JsonArray domains = Json.arr(o, "skinDomains");
        if (domains != null) for (JsonElement e : domains) s.skinDomains.add(e.getAsString());
        return s;
    }

    /**
     * 账号密码登录，返回带 uuid / accessToken 的账户。
     *
     * @param preferProfile 该账户有多个角色时选定的角色名（可空，默认第一个）
     */
    public static Account authenticate(Server server, String username, String password, String preferProfile)
            throws IOException {
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        body.add("agent", agent);
        body.addProperty("requestUser", false);
        body.addProperty("clientToken", java.util.UUID.randomUUID().toString().replace("-", ""));

        String resp;
        try {
            resp = Http.postJson(server.apiRoot + "/authserver/authenticate", body.toString());
        } catch (IOException e) {
            throw new IOException("登录失败（账号或密码错误，或服务器地址不对）：" + e.getMessage(), e);
        }
        JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
        String token = Json.str(o, "accessToken", "");
        if (token.isEmpty()) throw new IOException("登录失败：服务器没有返回 accessToken");

        JsonObject profile = Json.obj(o, "selectedProfile");
        if (profile == null && Json.arr(o, "availableProfiles") != null) {
            JsonArray list = Json.arr(o, "availableProfiles");
            if (!list.isEmpty()) profile = list.get(0).getAsJsonObject();
        }
        if (profile == null) throw new IOException("这个账号还没有创建角色，请先在皮肤站创建");

        String name = Json.str(profile, "name", username);
        String id = Json.str(profile, "id", "");
        if (!preferProfile.isEmpty() && !preferProfile.equals(name)) {
            Log.info("注意：该账号选中的角色是 " + name + "，不是 " + preferProfile);
        }

        Account a = new Account();
        a.username = name;
        a.uuid = id;
        a.type = "authlib";
        a.accessToken = token;
        a.apiRoot = server.apiRoot;
        a.serverName = server.serverName;
        a.expiresAt = 0;
        Log.info("外置登录成功：" + name + " @ " + server.serverName);
        return a;
    }

    /** 下载 authlib-injector.jar（有缓存则复用），返回本地路径。 */
    public static Path ensureJar(Path cacheDir) throws IOException {
        Dirs.ensure(cacheDir);
        Path jar = cacheDir.resolve("authlib-injector.jar");
        if (Files.isRegularFile(jar) && Files.size(jar) > 100_000) return jar;
        JsonObject o = JsonParser.parseString(Http.getString(LATEST)).getAsJsonObject();
        String url = Json.str(o, "download_url", "");
        if (url.isEmpty()) throw new IOException("拿不到 authlib-injector 的下载地址");
        String version = Json.str(o, "version", "");
        Log.info("下载 authlib-injector " + version);
        Http.download(url, jar, null, 0, null);
        if (!Files.isRegularFile(jar) || Files.size(jar) < 100_000) {
            throw new IOException("authlib-injector 下载失败");
        }
        return jar;
    }

    /** 生成 -javaagent 参数。 */
    public static String agentArg(Path jar, String apiRoot) {
        return "-javaagent:" + jar.toAbsolutePath() + "=" + normalize(apiRoot);
    }

    private static String normalize(String apiRoot) {
        String s = apiRoot == null ? "" : apiRoot.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
