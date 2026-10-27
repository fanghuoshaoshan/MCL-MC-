package mcl.auth;

import mcl.util.Dirs;
import mcl.util.Compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.config.Account;
import mcl.net.Http;
import mcl.util.Json;
import mcl.util.Log;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/**
 * 账户皮肤 / 披风：拉取纹理地址、下载并缓存。
 *
 * <p>两条链路：
 * <ul>
 *   <li>微软账户：{@code GET https://api.minecraftservices.com/minecraft/profile}（Bearer 令牌）</li>
 *   <li>外置登录：{@code GET {apiRoot}/sessionserver/session/minecraft/profile/{uuid}}，
 *       纹理藏在 properties[textures] 的 base64 里</li>
 * </ul>
 * 离线账户没有远端皮肤，直接用内置的 Steve 皮肤占位。
 */
public final class ProfileSkins {

    private ProfileSkins() {}

    public static final class Skin {
        public String name = "";
        public String uuid = "";
        public String skinUrl = "";
        public String capeUrl = "";
        public String model = "classic";
        public String source = "";
        public long fetchedAt;

        public boolean hasCape() {
            return capeUrl != null && !capeUrl.isEmpty();
        }

        public String modelLabel() {
            return "slim".equals(model) ? "细手臂（Alex）" : "经典（Steve）";
        }
    }

    public static Path cacheDir(Path launcherDir) {
        return launcherDir.resolve("cache").resolve("skins");
    }

    private static Path skinFile(Path dir, String uuid) {
        return dir.resolve(uuid.replace("-", "") + "-skin.png");
    }

    private static Path capeFile(Path dir, String uuid) {
        return dir.resolve(uuid.replace("-", "") + "-cape.png");
    }

    private static Path metaFile(Path dir, String uuid) {
        return dir.resolve(uuid.replace("-", "") + ".json");
    }

    // ---------------- 远端查询 ----------------

    /** 拉取账户的皮肤信息（不下载纹理）。失败时抛异常，由界面提示。 */
    public static Skin fetch(Account a) throws IOException {
        if (a == null) throw new IOException("没有账户");
        if ("msa".equals(a.type)) return fetchMicrosoft(a);
        if ("authlib".equals(a.type)) return fetchAuthlib(a);
        throw new IOException("离线账户没有远端皮肤");
    }

    private static Skin fetchMicrosoft(Account a) throws IOException {
        if (a.accessToken == null || a.accessToken.isEmpty()) {
            throw new IOException("微软账户缺少访问令牌，请重新登录一次");
        }
        String body = Http.getStringWithHeader("https://api.minecraftservices.com/minecraft/profile",
                "Authorization", "Bearer " + a.accessToken);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        Skin s = new Skin();
        s.name = Json.str(root, "name", a.username);
        s.uuid = Json.str(root, "id", a.uuid);
        s.source = "msa";
        s.fetchedAt = System.currentTimeMillis();
        JsonArray skins = Json.arr(root, "skins");
        if (skins != null) {
            for (JsonElement e : skins) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                if (!"ACTIVE".equalsIgnoreCase(Json.str(o, "state", "ACTIVE"))) continue;
                s.skinUrl = Json.str(o, "url", "");
                s.model = Json.str(o, "variant", "CLASSIC").toLowerCase();
                if (!s.skinUrl.isEmpty()) break;
            }
        }
        JsonArray capes = Json.arr(root, "capes");
        if (capes != null) {
            for (JsonElement e : capes) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                if (!"ACTIVE".equalsIgnoreCase(Json.str(o, "state", "ACTIVE"))) continue;
                s.capeUrl = Json.str(o, "url", "");
                if (!s.capeUrl.isEmpty()) break;
            }
        }
        return s;
    }

    private static Skin fetchAuthlib(Account a) throws IOException {
        if (a.apiRoot == null || Compat.isBlank(a.apiRoot)) throw new IOException("账户缺少皮肤站地址");
        String base = a.apiRoot.endsWith("/") ? a.apiRoot.substring(0, a.apiRoot.length() - 1) : a.apiRoot;
        String url = base + "/sessionserver/session/minecraft/profile/" + a.uuid;
        String body;
        try {
            body = Http.getStringOnce(url);
        } catch (IOException e) {
            // 未签名的 profile 接口在部分皮肤站需要带 ?unsigned=false
            body = Http.getStringOnce(url + "?unsigned=false");
        }
        if (body == null || Compat.isBlank(body)) throw new IOException("皮肤站没有返回角色信息（可能未设置皮肤）");
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        Skin s = new Skin();
        s.name = Json.str(root, "name", a.username);
        s.uuid = Json.str(root, "id", a.uuid);
        s.source = "authlib";
        s.fetchedAt = System.currentTimeMillis();
        JsonArray props = Json.arr(root, "properties");
        if (props != null) {
            for (JsonElement e : props) {
                if (!e.isJsonObject()) continue;
                JsonObject p = e.getAsJsonObject();
                if (!"textures".equals(Json.str(p, "name", ""))) continue;
                String value = Json.str(p, "value", "");
                if (value.isEmpty()) continue;
                parseTextures(s, value);
            }
        }
        return s;
    }

    /** 解析 base64 的 textures 属性。 */
    public static void parseTextures(Skin s, String base64) {
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = Json.obj(root, "textures");
            if (textures == null) return;
            JsonObject skin = Json.obj(textures, "SKIN");
            if (skin != null) {
                s.skinUrl = Json.str(skin, "url", "");
                JsonObject meta = Json.obj(skin, "metadata");
                if (meta != null) s.model = Json.str(meta, "model", "classic");
            }
            JsonObject cape = Json.obj(textures, "CAPE");
            if (cape != null) s.capeUrl = Json.str(cape, "url", "");
        } catch (Exception e) {
            Log.warn("解析皮肤属性失败：" + e.getMessage());
        }
    }

    // ---------------- 缓存 ----------------

    /** 拉取并缓存纹理，返回可绘制的皮肤图（失败时返回 null）。 */
    public static BufferedImage sync(Skin s, Path launcherDir) {
        if (s == null) return null;
        Path dir = cacheDir(launcherDir);
        try {
            Dirs.ensure(dir);
            String key = s.uuid == null || Compat.isBlank(s.uuid) ? "unknown" : s.uuid;
            BufferedImage skin = null;
            if (s.skinUrl != null && !s.skinUrl.isEmpty()) {
                skin = download(s.skinUrl, skinFile(dir, key));
            }
            if (s.hasCape()) download(s.capeUrl, capeFile(dir, key));
            writeMeta(dir, key, s);
            return skin;
        } catch (Exception e) {
            Log.warn("皮肤下载失败：" + e.getMessage());
            return null;
        }
    }

    private static BufferedImage download(String url, Path dest) throws IOException {
        byte[] bytes = Http.getStringBytes(url);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw new IOException("不是有效的 PNG：" + url);
        Files.write(dest, bytes);
        return img;
    }

    private static void writeMeta(Path dir, String key, Skin s) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.name);
            o.addProperty("uuid", s.uuid);
            o.addProperty("skinUrl", s.skinUrl);
            o.addProperty("capeUrl", s.capeUrl);
            o.addProperty("model", s.model);
            o.addProperty("source", s.source);
            o.addProperty("fetchedAt", s.fetchedAt);
            Compat.writeString(metaFile(dir, key), o.toString());
        } catch (Exception e) {
            Log.warn("写入皮肤元数据失败：" + e.getMessage());
        }
    }

    /** 读本地缓存（不联网）。 */
    public static BufferedImage cachedSkin(Path launcherDir, String uuid) {
        if (uuid == null || Compat.isBlank(uuid)) return null;
        return read( skinFile(cacheDir(launcherDir), uuid));
    }

    public static BufferedImage cachedCape(Path launcherDir, String uuid) {
        if (uuid == null || Compat.isBlank(uuid)) return null;
        return read(capeFile(cacheDir(launcherDir), uuid));
    }

    public static Skin cachedMeta(Path launcherDir, String uuid) {
        if (uuid == null || Compat.isBlank(uuid)) return null;
        try {
            Path f = metaFile(cacheDir(launcherDir), uuid);
            if (!Files.isRegularFile(f)) return null;
            JsonObject o = JsonParser.parseString(Compat.readString(f)).getAsJsonObject();
            Skin s = new Skin();
            s.name = Json.str(o, "name", "");
            s.uuid = Json.str(o, "uuid", "");
            s.skinUrl = Json.str(o, "skinUrl", "");
            s.capeUrl = Json.str(o, "capeUrl", "");
            s.model = Json.str(o, "model", "classic");
            s.source = Json.str(o, "source", "");
            s.fetchedAt = o.has("fetchedAt") ? o.get("fetchedAt").getAsLong() : 0;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage read(Path p) {
        try {
            if (!Files.isRegularFile(p)) return null;
            return ImageIO.read(p.toFile());
        } catch (Exception e) {
            return null;
        }
    }

    /** 缓存两份内置纹理，头像列表里每个账户都会调，没必要反复解码。 */
    private static BufferedImage DEFAULT_STEVE;
    private static BufferedImage DEFAULT_ALEX;

    /**
     * 离线账户的默认皮肤：按 UUID 在 Steve / Alex 之间二选一，
     * <b>与游戏内 DefaultSkinHelper 的判定完全一致</b>，所以预览和进游戏看到的是同一个人。
     */
    public static BufferedImage defaultSkin(String uuid) {
        return defaultSkin(uuid, "");
    }

    /**
     * 同上，但允许账户显式指定模型（离线账户在账户页选的 Steve / Alex）。
     *
     * <p>注意：这个指定只作用于启动器自己的预览。进游戏之后模型仍由
     * {@code UUID.hashCode()} 决定 —— 客户端不装皮肤模组的话，启动器没地方插手。
     *
     * @param skinModel {@code ""} 跟随 UUID，{@code "classic"} 指定 Steve，{@code "slim"} 指定 Alex
     */
    public static BufferedImage defaultSkin(String uuid, String skinModel) {
        return isSlim(uuid, skinModel) ? alex() : steve();
    }

    /**
     * UUID 决定玩家模型：游戏里是 {@code Math.floorMod(uuid.hashCode(), 2)}，
     * 0 用经典（Steve，4px 手臂），1 用纤细（Alex，3px 手臂）。
     *
     * <p>这里必须用 {@link java.util.UUID#hashCode()}，不能用字符串 hashCode ——
     * 前者和游戏算的是同一个数，后者不是。
     */
    public static boolean isSlim(String uuid) {
        if (Compat.isBlank(uuid)) return false;
        try {
            return Math.floorMod(java.util.UUID.fromString(dashed(uuid)).hashCode(), 2) == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 带账户指定值的判定：显式选了就用选的，没选才跟随 UUID。 */
    public static boolean isSlim(String uuid, String skinModel) {
        String m = normalizeModel(skinModel);
        if ("slim".equals(m)) return true;
        if ("classic".equals(m)) return false;
        return isSlim(uuid);
    }

    /** 实际生效的模型，用于界面文案。 */
    public static String effectiveModel(String uuid, String skinModel) {
        return isSlim(uuid, skinModel) ? "slim" : "classic";
    }

    /**
     * 把各种写法的模型名收敛成 {@code ""} / {@code "classic"} / {@code "slim"}。
     *
     * <p>认 {@code alex} / {@code steve} 这两种口语写法，免得手改 mcl.json 之后失效。
     */
    public static String normalizeModel(String model) {
        if (model == null) return "";
        String t = model.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty() || "auto".equals(t) || "uuid".equals(t)) return "";
        if ("slim".equals(t) || "alex".equals(t) || "纤细".equals(t)) return "slim";
        if ("classic".equals(t) || "steve".equals(t) || "经典".equals(t)) return "classic";
        return "";
    }

    /** 模型的中文名，界面文案统一走这里。 */
    public static String modelLabel(String model) {
        return "slim".equals(normalizeModel(model)) ? "Alex（纤细）" : "Steve（经典）";
    }

    /** 把无横线的 32 位 uuid 还原成 UUID.fromString 能吃的格式。 */
    private static String dashed(String s) {
        String t = s.trim().replace("-", "");
        if (t.length() != 32) return s;
        return t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16)
                + "-" + t.substring(16, 20) + "-" + t.substring(20);
    }

    /** 经典皮肤（Steve）。 */
    public static BufferedImage steve() {
        if (DEFAULT_STEVE == null) DEFAULT_STEVE = loadDefault("steve", false);
        return DEFAULT_STEVE;
    }

    /** 纤细皮肤（Alex）。 */
    public static BufferedImage alex() {
        if (DEFAULT_ALEX == null) DEFAULT_ALEX = loadDefault("alex", true);
        return DEFAULT_ALEX;
    }

    private static BufferedImage loadDefault(String name, boolean slim) {
        try (java.io.InputStream in = ProfileSkins.class.getResourceAsStream("/mcl/skins/" + name + ".png")) {
            if (in != null) {
                BufferedImage img = ImageIO.read(in);
                // 只认 64x64：混进 64x32 的旧图会让预览缺左臂左腿
                if (img != null && img.getWidth() == 64 && img.getHeight() == 64) return img;
            }
        } catch (Exception e) {
            Log.warn("内置皮肤 " + name + " 读取失败，改用色块占位：" + e.getMessage());
        }
        return fallbackSkin(slim);
    }

    /**
     * 最后兜底：资源丢了的时候至少画个能看出人形的色块。
     *
     * <p>正常情况下走不到这里 —— 真正的 Steve / Alex 纹理在 {@code mcl/skins/*.png}，
     * 这里的颜色故意做成"一眼能看出是占位"而不是"假装是皮肤"。
     */
    private static BufferedImage fallbackSkin(boolean slim) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        java.awt.Color hair = new java.awt.Color(0x3B2A1A);
        java.awt.Color skin = new java.awt.Color(0xC69C6D);
        java.awt.Color shirt = slim ? new java.awt.Color(0x5A8F3C) : new java.awt.Color(0x00AAAA);
        java.awt.Color pants = slim ? new java.awt.Color(0x6B5133) : new java.awt.Color(0x3B3BA0);

        g.setColor(hair);
        g.fillRect(8, 8, 8, 2);          // 头发
        g.setColor(skin);
        g.fillRect(8, 10, 8, 6);         // 脸
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(9, 11, 2, 1);         // 眼白
        g.fillRect(13, 11, 2, 1);
        g.setColor(slim ? new java.awt.Color(0x2B6B2B) : new java.awt.Color(0x3B3BB0));
        g.fillRect(10, 11, 1, 1);        // 瞳孔
        g.fillRect(14, 11, 1, 1);
        g.setColor(new java.awt.Color(0x8B5A3C));
        g.fillRect(11, 14, 2, 1);        // 嘴
        g.setColor(shirt);
        g.fillRect(20, 20, 8, 12);       // 身体
        g.setColor(skin);
        g.fillRect(44, 20, 4, 12);       // 右臂
        g.setColor(pants);
        g.fillRect(4, 20, 4, 12);        // 右腿
        g.fillRect(20, 52, 4, 12);       // 左腿
        if (slim) {
            g.fillRect(37, 52, 3, 12);   // Alex 的细手臂
        } else {
            g.fillRect(36, 52, 4, 12);
        }
        // 第二层（外套 / 袖子）留空，避免遮住脸
        g.dispose();
        return img;
    }
    // ---------------- 本地皮肤（离线账户自己导入的 PNG） ----------------

    /**
     * 本地皮肤存放目录：{@code <启动器目录>/skins}。
     *
     * <p>刻意不放 {@code cache/} —— cache 是「删了还能重新下回来」的东西，
     * 用户自己导入的皮肤删了就没了，所以单独一个目录，名字也直白。
     */
    public static Path localSkinDir(Path launcherDir) {
        return launcherDir.resolve("skins");
    }

    /** 本地皮肤按 UUID 存，一个账户一张；改名后 UUID 会变，由调用方迁移。 */
    public static Path localSkinFile(Path launcherDir, String uuid) {
        String key = uuid == null ? "" : uuid.replace("-", "");
        return localSkinDir(launcherDir).resolve(key + ".png");
    }

    public static boolean hasLocalSkin(Path launcherDir, String uuid) {
        if (launcherDir == null || Compat.isBlank(uuid)) return false;
        return Files.isRegularFile(localSkinFile(launcherDir, uuid));
    }

    /** 读本地皮肤；没有或读不出返回 null。 */
    public static BufferedImage localSkin(Path launcherDir, String uuid) {
        if (launcherDir == null || Compat.isBlank(uuid)) return null;
        return read(localSkinFile(launcherDir, uuid));
    }

    /**
     * 保存用户导入的皮肤（统一转 64×64 ARGB 再落盘）。
     *
     * <p>64×32 的老皮肤必须补齐下半区：1.8 之后皮肤是 64×64，多出来的部分是
     * 手臂和腿的下半截，游戏自己会镜像采样，预览控件不会 —— 不补就是「腿只剩上半截」。
     * 128×128 这种整数倍用最近邻缩回去，像素是整块对应的，不会糊。
     */
    public static Path saveLocalSkin(Path launcherDir, String uuid, BufferedImage src) throws IOException {
        if (launcherDir == null) throw new IOException("启动器目录未知");
        if (Compat.isBlank(uuid)) throw new IOException("账户没有 UUID");
        BufferedImage out = normalizeSkin(src);
        Path dest = localSkinFile(launcherDir, uuid);
        Dirs.ensure(dest.getParent());
        if (!ImageIO.write(out, "png", dest.toFile())) throw new IOException("写入 PNG 失败：" + dest);
        return dest;
    }

    public static void deleteLocalSkin(Path launcherDir, String uuid) {
        if (launcherDir == null || Compat.isBlank(uuid)) return;
        try {
            Files.deleteIfExists(localSkinFile(launcherDir, uuid));
        } catch (IOException e) {
            Log.warn("删除本地皮肤失败：" + e.getMessage());
        }
    }

    /** 改名时把本地皮肤迁到新 UUID 名下，不然换个名字皮肤就"丢"了。 */
    public static void moveLocalSkin(Path launcherDir, String fromUuid, String toUuid) {
        if (launcherDir == null || Compat.isBlank(fromUuid) || Compat.isBlank(toUuid)) return;
        if (fromUuid.equalsIgnoreCase(toUuid)) return;
        Path from = localSkinFile(launcherDir, fromUuid);
        if (!Files.isRegularFile(from)) return;
        Path to = localSkinFile(launcherDir, toUuid);
        try {
            Dirs.ensure(to.getParent());
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.warn("迁移本地皮肤失败：" + e.getMessage());
        }
    }

    /**
     * 把任意合法尺寸的皮肤规整成 64×64。
     *
     * @throws IOException 尺寸不是 64×64 / 64×32 的整数倍
     */
    public static BufferedImage normalizeSkin(BufferedImage src) throws IOException {
        if (src == null) throw new IOException("图片读不出来");
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) throw new IOException("图片尺寸无效");
        boolean tall = w == h;
        boolean flat = w == h * 2;
        if (!(tall || flat) || w % 64 != 0 || h % 32 != 0) {
            throw new IOException("尺寸不对：" + w + "×" + h
                    + "\n皮肤必须正好是 64×64 或 64×32（128×128、128×64 这种整数倍也可以）");
        }
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        try {
            if (tall) {
                g.drawImage(src, 0, 0, 64, 64, null);
            } else {
                g.drawImage(src, 0, 0, 64, 32, 0, 0, w, h, null);
                mirrorLegacy(g, src, w, h);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 64×32 → 64×64：把腿和手臂的下半截复制到下半区（坐标取自 1.8 的皮肤布局）。 */
    private static void mirrorLegacy(Graphics2D g, BufferedImage src, int w, int h) {
        int u = w / 64;
        // 右腿 (0,16)-(16,32) → (16,48)
        g.drawImage(src, 16, 48, 32, 64, 0, 16 * u, 16 * u, 32 * u, null);
        // 左腿 (16,16)-(32,32) → (0,48)
        g.drawImage(src, 0, 48, 16, 64, 16 * u, 16 * u, 32 * u, 32 * u, null);
        // 右臂 (40,16)-(56,32) → (32,48)
        g.drawImage(src, 32, 48, 48, 64, 40 * u, 16 * u, 56 * u, 32 * u, null);
    }
}
