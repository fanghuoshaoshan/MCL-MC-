package mcl.auth;

import mcl.util.Compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.config.Account;
import mcl.net.Http;
import mcl.util.Json;
import mcl.util.Log;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 微软正版登录（OAuth 2.0 设备代码流程 + Xbox Live / XSTS / Minecraft 服务链）。
 *
 * <p>流程与官方启动器一致：
 * <ol>
 *   <li>申请设备代码，用户在浏览器里输入代码完成授权</li>
 *   <li>轮询令牌接口拿到 Microsoft 访问令牌</li>
 *   <li>Xbox Live 认证 → XSTS 授权 → Minecraft 登录 → 读取档案（uuid / 名字）</li>
 * </ol>
 */
public final class MicrosoftAuth {

    /** 官方启动器使用的公共客户端 id（设备代码流程不需要密钥）。 */
    public static final String CLIENT_ID = "00000000402b5328";
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf";
    private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private MicrosoftAuth() {}

    /** 设备代码授权信息，界面上要展示给用户。 */
    public static final class DeviceCode {
        public String userCode = "";
        public String deviceCode = "";
        public String verificationUri = "";
        public int interval = 5;
        public int expiresIn = 900;

        public String message() {
            return "请打开 " + verificationUri + " 并输入代码：" + userCode;
        }
    }

    /** 用户还没完成授权时抛出，调用方应继续轮询。 */
    public static final class PendingException extends Exception {
        public PendingException(String msg) {
            super(msg);
        }
    }

    /** 第一步：申请设备代码。 */
    public static DeviceCode start() throws IOException {
        String body = "client_id=" + enc(CLIENT_ID)
                + "&scope=" + enc(SCOPE)
                + "&response_type=device_code";
        String resp = Http.postForm(DEVICE_CODE_URL, body);
        JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
        DeviceCode d = new DeviceCode();
        d.userCode = Json.str(o, "user_code", "");
        d.deviceCode = Json.str(o, "device_code", "");
        d.verificationUri = Json.str(o, "verification_uri", "https://microsoft.com/link");
        d.interval = Json.integer(o, "interval", 5);
        d.expiresIn = Json.integer(o, "expires_in", 900);
        if (d.deviceCode.isEmpty()) {
            throw new IOException("申请设备代码失败：" + resp);
        }
        return d;
    }

    /**
     * 第二步：轮询，直到用户完成授权。
     *
     * @throws PendingException 用户还没完成授权（继续等）
     */
    public static MsaToken poll(DeviceCode d) throws IOException, PendingException {
        String body = "client_id=" + enc(CLIENT_ID)
                + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                + "&device_code=" + enc(d.deviceCode);
        JsonObject o = JsonParser.parseString(Http.postForm(TOKEN_URL, body)).getAsJsonObject();
        String error = Json.str(o, "error", "");
        if (!error.isEmpty()) {
            if ("authorization_pending".equals(error) || "slow_down".equals(error)) {
                throw new PendingException(error);
            }
            throw new IOException("授权失败：" + error + " " + Json.str(o, "error_description", ""));
        }
        MsaToken t = new MsaToken();
        t.accessToken = Json.str(o, "access_token", "");
        t.refreshToken = Json.str(o, "refresh_token", "");
        t.expiresIn = Json.integer(o, "expires_in", 3600);
        if (t.accessToken.isEmpty()) throw new IOException("没有拿到 Microsoft 访问令牌");
        return t;
    }

    /** Microsoft 访问令牌。 */
    public static final class MsaToken {
        public String accessToken = "";
        public String refreshToken = "";
        public int expiresIn = 3600;
    }

    /** 用 refresh_token 续期。 */
    public static MsaToken refresh(String refreshToken) throws IOException {
        String body = "client_id=" + enc(CLIENT_ID)
                + "&grant_type=refresh_token"
                + "&refresh_token=" + enc(refreshToken)
                + "&scope=" + enc(SCOPE);
        JsonObject o = JsonParser.parseString(Http.postForm(TOKEN_URL, body)).getAsJsonObject();
        MsaToken t = new MsaToken();
        t.accessToken = Json.str(o, "access_token", "");
        t.refreshToken = Json.str(o, "refresh_token", refreshToken);
        t.expiresIn = Json.integer(o, "expires_in", 3600);
        if (t.accessToken.isEmpty()) {
            throw new IOException("刷新微软令牌失败：" + Json.str(o, "error_description", ""));
        }
        return t;
    }

    /** 完整链路：微软令牌 → Xbox → XSTS → Minecraft → 档案。 */
    public static Account toAccount(MsaToken token) throws IOException {
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + token.accessToken);
        JsonObject xblBody = new JsonObject();
        xblBody.add("Properties", props);
        xblBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblBody.addProperty("TokenType", "JWT");
        JsonObject xbl = postJson(XBL_URL, xblBody.toString());
        String xblToken = Json.str(xbl, "Token", "");
        String uhs = firstUhs(xbl);
        if (xblToken.isEmpty()) throw new IOException("Xbox Live 认证失败：" + xbl);

        JsonObject xstsProps = new JsonObject();
        xstsProps.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(xblToken);
        xstsProps.add("UserTokens", tokens);
        JsonObject xstsBody = new JsonObject();
        xstsBody.add("Properties", xstsProps);
        xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.addProperty("TokenType", "JWT");
        JsonObject xsts = postJson(XSTS_URL, xstsBody.toString());
        String xstsToken = Json.str(xsts, "Token", "");
        if (xstsToken.isEmpty()) {
            String xerr = Json.str(xsts, "XErr", "");
            throw new IOException(xstsError(xerr, xsts));
        }
        String xstsUhs = firstUhs(xsts);
        if (!xstsUhs.isEmpty()) uhs = xstsUhs;

        JsonObject mcBody = new JsonObject();
        mcBody.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        JsonObject mc = postJson(MC_LOGIN_URL, mcBody.toString());
        String mcToken = Json.str(mc, "access_token", "");
        if (mcToken.isEmpty()) {
            throw new IOException("Minecraft 登录失败（这个账户可能没有购买 Minecraft）：" + mc);
        }

        JsonObject profile = JsonParser.parseString(
                Http.getStringWithHeader(MC_PROFILE_URL, "Authorization", "Bearer " + mcToken)).getAsJsonObject();
        String uuid = Json.str(profile, "id", "");
        String name = Json.str(profile, "name", "");
        if (uuid.isEmpty() || name.isEmpty()) {
            throw new IOException("读取 Minecraft 档案失败（可能没有购买游戏）：" + profile);
        }

        Account a = new Account();
        a.username = name;
        a.uuid = uuid;
        a.type = "msa";
        a.accessToken = mcToken;
        a.refreshToken = token.refreshToken;
        a.expiresAt = System.currentTimeMillis() + Math.max(60, Json.integer(mc, "expires_in", 86400)) * 1000L;
        Log.info("微软登录成功：" + name + "（" + uuid + "）");
        return a;
    }

    /** 令牌过期时刷新账户（返回 true 表示已更新）。 */
    public static boolean ensureValid(Account a) {
        if (a == null || !"msa".equals(a.type)) return false;
        if (a.refreshToken == null || Compat.isBlank(a.refreshToken)) return false;
        if (a.expiresAt > System.currentTimeMillis() + 60_000) return false;
        try {
            MsaToken t = refresh(a.refreshToken);
            Account fresh = toAccount(t);
            a.accessToken = fresh.accessToken;
            a.refreshToken = fresh.refreshToken;
            a.expiresAt = fresh.expiresAt;
            a.uuid = fresh.uuid;
            a.username = fresh.username;
            return true;
        } catch (IOException e) {
            Log.warn("刷新账户失败：" + e.getMessage());
            return false;
        }
    }

    /** 把 XSTS 的 XErr 翻成人话。 */
    private static String xstsError(String xerr, JsonObject body) {
        if ("2148916233".equals(xerr)) return "这个微软账户还没有 Xbox 账户，请先在 xbox.com 创建角色";
        if ("2148916235".equals(xerr)) return "该地区不支持 Xbox Live";
        if ("2148916238".equals(xerr)) return "未成年账户需要先加入家庭组";
        return "XSTS 授权失败：" + body;
    }

    private static String firstUhs(JsonObject o) {
        JsonObject claims = Json.obj(o, "DisplayClaims");
        JsonArray xui = Json.arr(claims, "xui");
        if (xui == null || xui.isEmpty()) return "";
        JsonElement e = xui.get(0);
        return e.isJsonObject() ? Json.str(e.getAsJsonObject(), "uhs", "") : "";
    }

    private static JsonObject postJson(String url, String body) throws IOException {
        return JsonParser.parseString(Http.postJson(url, body)).getAsJsonObject();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;   // UTF-8 一定存在，这里只是签名要求
        }
    }
}
