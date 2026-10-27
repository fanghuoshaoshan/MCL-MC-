package mcl.mod;

import mcl.util.Compat;
import mcl.util.Json;
import mcl.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.net.Http;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CurseForge 开放接口客户端（api.curseforge.com/v1）。
 *
 * <p>和 {@link Modrinth} 的 Labrinth 协议<b>不是一回事</b>——CF 的字段名、分页方式、
 * 过滤参数全都不同，所以没法像 ModSource 里换 base URL 那样复用，得单独写一层。
 * 这一层把 CF 的响应翻 {@link Modrinth.Hit} / {@link Modrinth.VersionInfo}，
 * 让上层（界面、安装器）不用知道数据来自哪个站。
 *
 * <h3>必须要有 API Key</h3>
 * 所有请求都要带 {@code x-api-key}，没有就 403。见 {@link #key()}。
 *
 * <h3>两个踩过的坑</h3>
 * <ol>
 *   <li><b>{@code gameVersions} 是混合列表</b>：里面既有真正的 MC 版本号（{@code 1.20.1}、
 *       {@code 26.3}），也有 {@code Client} / {@code Server} / {@code Forge} / {@code NeoForge}
 *       这类端与加载器的标记。直接拿这个字段过滤会全错，所以 {@link #realGameVersions}
 *       只用「看起来像版本号」的正则来挑，其余归到 loaders。</li>
 *   <li><b>搜索词匹配不上时 CF 会返回噪声</b>而不是空结果：搜「钠」会返回一串
 *       {@code (Aps)Auto portal search} 之类完全无关的项目（它们和查询的唯一共同点是……没有共同点）。
 *       所以中文查询不能直接扔给 CF，见 {@link ZhRecall} 的本地索引路径。</li>
 * </ol>
 */
public final class CurseForge {

    public static final String API = "https://api.curseforge.com";
    /** Minecraft 在 CurseForge 里的 gameId。 */
    public static final int GAME_MINECRAFT = 432;

    /** Modrinth 项目类型 → CurseForge 的 classId。 */
    public static final int CLASS_MOD = 6;
    public static final int CLASS_RESOURCEPACK = 12;
    public static final int CLASS_WORLD = 17;
    public static final int CLASS_MODPACK = 4471;
    /** CF 没有独立的「光影」分类，CF 站点上它叫 Shaders。 */
    public static final int CLASS_SHADER = 6552;
    /** Customization：数据之类的小改造，和光影不是一回事。 */
    public static final int CLASS_CUSTOMIZATION = 4546;

    /** 加载器 → CF 的 modLoaderType。 */
    public static final int LOADER_FORGE = 1;
    public static final int LOADER_CAULDRON = 2;
    public static final int LOADER_LITELOADER = 3;
    public static final int LOADER_FABRIC = 4;
    public static final int LOADER_QUILT = 5;
    public static final int LOADER_NEOFORGE = 6;

    private static final Map<String, String> HEADERS = new HashMap<>();
    /** 用户在设置里指定的 key；空表示没指定，用 {@link KeyHolder#BUILTIN}。 */
    private static volatile String customKey = "";

    private CurseForge() {}

    // ---------------- API Key ----------------

    /**
     * 当前的 API Key；取不到就用内置兜底 key，所以永远不会返回空串
     * ——关键在于 {@code ModSource.available()} 要不要把 CurseForge 放进可选列表，
     * 而这取决于 {@link #key()} 是否有值。
     *
     * <p>优先级：<b>系统属性 {@code mcl.curseforge.apikey}</b> &gt;
     * <b>设置里填的 key</b>（见 {@link #setCustomKey}）&gt; <b>内置兜底 key</b>。
     *
     * <p>关于兜底 key：CurseForge 的 key 免费申请，但要去开发者控制台，对只想用启动器的人是道门槛。
     * 所以内置了一个 HMCL 公开发布版本里带的 key（GPL 项目，key 随构建产物公开）保证开箱即用。
     * 代价是<b>共用别人的配额，可能被限速或随时失效</b>——想要稳定就自己去申请一个，
     * 填进设置或用 {@code java -Dmcl.curseforge.apikey=你的key -jar MCL.jar} 覆盖。
     */
    public static String key() {
        String k = System.getProperty("mcl.curseforge.apikey", "").trim();
        if (!k.isEmpty()) return k;
        String c = customKey.trim();
        if (!c.isEmpty()) return c;
        return KeyHolder.BUILTIN;
    }

    /** 用户在设置里填的 key（覆盖内置兜底值）。传空串表示「不指定，用兜底」。 */
    public static void setCustomKey(String k) {
        customKey = k == null ? "" : k.trim();
    }

    /** 用户是否配了自己的 key。 */
    public static boolean usingCustomKey() {
        return !System.getProperty("mcl.curseforge.apikey", "").trim().isEmpty()
                || !customKey.trim().isEmpty();
    }

    /** 兜底 key 单独放在一个延迟初始化的类里，避免被顺手序列化出去。 */
    private static final class KeyHolder {
        // HMCL 官方构建产物公开的 CurseForge API key。共享配额，可能被限速/失效。
        static final String BUILTIN =
                "$2a$10$o8pygPrhvKBHuuh5imL2W.LCNFhB15zBYAExXx/TqTx/Zp5px2lxu";
    }

    /**
     * CurseForge 现在能不能用。
     *
     * <p>判据就是「有没有 key」——有 key 就假定可用。真正的鉴权失败要等请求发出去才知道，
     * 那时候 {@link #get} 会把 403 翻译成一句人话，界面显示「搜索失败」而不是崩掉。
     *
     * <p>{@code -Dmcl.curseforge.disable=true} 可以强制关掉这个源
     * （应急用：内置兜底 key 某天失效、或者干脆不想看到它的情况下）。
     */
    public static boolean available() {
        if (Boolean.getBoolean("mcl.curseforge.disable")) return false;
        return !Compat.isBlank(key());
    }

    /** CF 请求需要带的头：x-api-key 是强制的，Accept 明确要 JSON。 */
    private static Map<String, String> headers() {
        synchronized (HEADERS) {
            HEADERS.put("x-api-key", key());
            HEADERS.put("Accept", "application/json");
            return new HashMap<>(HEADERS);
        }
    }

    // ---------------- 搜索 ----------------

    /**
     * 按关键词搜索。
     *
     * @param projectType Modrinth 的项目类型名（会被翻译成 CF 的 classId）
     * @param sort        relevance / downloads / updated / newest，认不出就用 CF 的 Featured
     */
    public static Modrinth.SearchResult search(String query, String projectType, String gameVersion,
                                               String loader, String sort, int index, int pageSize)
            throws IOException {
        Modrinth.SearchResult r = new Modrinth.SearchResult();
        StringBuilder url = new StringBuilder(API).append("/v1/mods/search?gameId=").append(GAME_MINECRAFT);
        int classId = classIdOf(projectType);
        if (classId > 0) url.append("&classId=").append(classId);
        if (!Compat.isBlank(query)) url.append("&searchFilter=").append(enc(query.trim()));
        if (!Compat.isBlank(gameVersion)) url.append("&gameVersion=").append(enc(gameVersion.trim()));
        int lt = loaderTypeOf(loader);
        if (lt > 0) url.append("&modLoaderType=").append(lt);
        url.append("&sortField=").append(sortFieldOf(sort)).append("&sortOrder=desc");
        int ps = Math.max(1, Math.min(50, pageSize));
        int ix = Math.max(0, index);
        // CF 硬性限制：index + pageSize <= 10000
        if (ix + ps > 10000) ix = Math.max(0, 10000 - ps);
        url.append("&index=").append(ix).append("&pageSize=").append(ps);

        JsonObject root = JsonParser.parseString(get(url.toString())).getAsJsonObject();
        JsonObject page = Json.obj(root, "pagination");
        r.totalHits = Json.integer(page, "totalCount", 0);
        r.offset = ix;
        r.limit = ps;
        JsonArray hits = Json.arr(root, "data");
        Set<String> seen = new HashSet<>();
        // CF 的 searchFilter 是全文模糊匹配，相关性排序很差：
        // 搜 "complementary" 会返回 "Toon Cel Shader"，而 Complementary Shaders 排在几十条之后。
        // 先把 slug 精确命中的那条顶上来——用户搜的往往就是项目名本身。
        Modrinth.Hit exact = null;
        if (!Compat.isBlank(query) && looksLikeSlug(query)) {
            try {
                exact = bySlug(query.trim().toLowerCase(), projectType);
            } catch (IOException ignored) {
                // 精确查询只是加分项，失败就用模糊结果
            }
        }
        if (exact != null) {
            r.hits.add(exact);
            seen.add(exact.projectId);
        }
        List<Modrinth.Hit> rest = new ArrayList<>();
        if (hits != null) {
            for (JsonElement e : hits) {
                if (!e.isJsonObject()) continue;
                Modrinth.Hit h = modToHit(e.getAsJsonObject());
                if (seen.contains(h.projectId)) continue;
                seen.add(h.projectId);
                rest.add(h);
            }
        }
        // CF 默认的 Featured 排序跟「关键词匹配程度」基本无关：
        // 搜 twilight-forest，正主 The Twilight Forest（2.1 亿下载）被排在第六条，
        // 前五条是 Every Compat、Corail Woodcutter 这类完全不相干的项目。
        // 这里做一次纯本地的重排——把标题里带这个词的项目提到前面，不额外发请求。
        bumpTitleMatches(rest, query);
        r.hits.addAll(rest);
        return r;
    }

    /**
     * 把标题里包含查询词的结果挪到前面，其余保持原顺序（稳定）。
     *
     * <p>比较前会先把双方都归一化成「只留字母数字、全小写」，
     * 这样 {@code twilight-forest} 能匹配 {@code The Twilight Forest}，
     * {@code jei} 也能匹配 {@code Just Enough Items (JEI)}。
     */
    private static void bumpTitleMatches(List<Modrinth.Hit> list, String query) {
        if (list == null || list.size() < 2 || Compat.isBlank(query)) return;
        String nq = alnumOnly(query);
        if (nq.length() < 2) return;
        List<Modrinth.Hit> top = new ArrayList<>();
        List<Modrinth.Hit> tail = new ArrayList<>();
        for (Modrinth.Hit h : list) {
            if (alnumOnly(h.title).contains(nq) || alnumOnly(h.slug).contains(nq)) top.add(h);
            else tail.add(h);
        }
        if (top.isEmpty()) return;   // 一个都没对上就别动，原样交给上层
        // 提到前面之后还要内部按下载量排：搜 twilight-forest 时
        // 「The Twilight Forest」(2.1 亿) 和它的衍生模组都带这个词，
        // 不重排的话下载量小 400 倍的衍生模组会照样占着第一条。
        Collections.sort(top, new Comparator<Modrinth.Hit>() {
            @Override
            public int compare(Modrinth.Hit a, Modrinth.Hit b) {
                if (a.downloads != b.downloads) return a.downloads > b.downloads ? -1 : 1;
                return a.slug.compareTo(b.slug);
            }
        });
        list.clear();
        list.addAll(top);
        list.addAll(tail);
    }

    /** 归一化：只保留字母和数字，转小写。 {@code "The Twilight Forest"} → {@code "thetwilightforest"} */
    private static String alnumOnly(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 像 slug 吗：CF 的 slug 只允许小写字母、数字和连字符。 */
    private static boolean looksLikeSlug(String s) {
        if (Compat.isBlank(s)) return false;
        return s.trim().matches("[a-zA-Z0-9][a-zA-Z0-9-]*");
    }

    /**
     * 按 slug 精确查询，命中就是那个项目。
     *
     * <p>这是中文索引桥接的关键：中文名索引里存的是通用 slug，
     * 实测这个 slug 在 CF 上大约一半的概率同名存在（{@code sodium}、{@code create}、
     * {@code worldedit} 都在），于是中文 $\rightarrow$ slug $\rightarrow$ CurseForge 就通了。
     */
    public static Modrinth.Hit bySlug(String slug, String projectType) throws IOException {
        if (Compat.isBlank(slug)) return null;
        StringBuilder url = new StringBuilder(API).append("/v1/mods/search?gameId=").append(GAME_MINECRAFT)
                .append("&slug=").append(enc(slug.trim())).append("&pageSize=1");
        int classId = classIdOf(projectType);
        if (classId > 0) url.append("&classId=").append(classId);
        JsonElement root = JsonParser.parseString(get(url.toString()));
        if (!root.isJsonObject()) return null;
        JsonArray data = Json.arr(root.getAsJsonObject(), "data");
        if (data == null || data.size() == 0) return null;
        JsonElement first = data.get(0);
        return first.isJsonObject() ? modToHit(first.getAsJsonObject()) : null;
    }

    /** 批量按 modId 取项目（CF 只有按 id 的批量，没有按 slug 的）。 */
    public static List<Modrinth.Hit> modsByIds(List<String> ids) throws IOException {
        List<Modrinth.Hit> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return out;
        JsonArray arr = new JsonArray();
        for (String id : ids) {
            if (Compat.isBlank(id)) continue;
            try {
                arr.add(Long.parseLong(id.trim()));
            } catch (NumberFormatException ignored) {
                // CF 的 id 是纯数字，这里忽略无效项
            }
        }
        if (arr.size() == 0) return out;
        JsonObject body = new JsonObject();
        body.add("modIds", arr);
        String resp = Http.postJsonWithHeaders(API + "/v1/mods", body.toString(), headers());
        JsonElement root = JsonParser.parseString(resp);
        if (!root.isJsonObject()) return out;
        JsonArray data = Json.arr(root.getAsJsonObject(), "data");
        if (data != null) {
            for (JsonElement e : data) {
                if (e.isJsonObject()) out.add(modToHit(e.getAsJsonObject()));
            }
        }
        return out;
    }

    // ---------------- 版本 / 文件 ----------------

    /**
     * 把 slug 或 modId 统一解析成数字 modId。
     *
     * <p>上层传什么都允许：搜来的结果在 {@code projectId} 里是数字，但用户手输、
     * 命令行参数、以及依赖声明给的很可能是 slug。已经是数字就直接返回，
     * 不多打一次请求；不是数字才查一次。
     */
    public static String resolveModId(String idOrSlug) throws IOException {
        if (Compat.isBlank(idOrSlug)) throw new IOException("缺少 CurseForge 项目 id");
        String s = idOrSlug.trim();
        if (isNumeric(s)) return s;
        Modrinth.Hit h = bySlug(s, null);
        if (h == null) throw new IOException("CurseForge 上没有 " + s);
        return h.projectId;
    }

    /** 取某个项目的文件列表，可按游戏版本与加载器过滤。 */
    public static List<Modrinth.VersionInfo> files(String idOrSlug, String gameVersion, String loader)
            throws IOException {
        List<Modrinth.VersionInfo> out = new ArrayList<>();
        if (Compat.isBlank(idOrSlug)) return out;
        String modId = resolveModId(idOrSlug);
        StringBuilder url = new StringBuilder(API).append("/v1/mods/").append(enc(modId.trim()))
                .append("/files?pageSize=50");
        if (!Compat.isBlank(gameVersion)) url.append("&gameVersion=").append(enc(gameVersion.trim()));
        int lt = loaderTypeOf(loader);
        if (lt > 0) url.append("&modLoaderType=").append(lt);
        JsonElement root = JsonParser.parseString(get(url.toString()));
        if (!root.isJsonObject()) return out;
        JsonArray data = Json.arr(root.getAsJsonObject(), "data");
        if (data == null) return out;
        for (JsonElement e : data) {
            if (e.isJsonObject()) out.add(fileToVersion(e.getAsJsonObject()));
        }
        return out;
    }

    /** 取单个文件，转成 VersionInfo。id 格式是「modId:fileId」。 */
    public static Modrinth.VersionInfo file(String modId, String fileId) throws IOException {
        if (Compat.isBlank(modId) || Compat.isBlank(fileId)) return null;
        String url = API + "/v1/mods/" + enc(modId.trim()) + "/files/" + enc(fileId.trim());
        JsonElement root = JsonParser.parseString(get(url));
        if (!root.isJsonObject()) return null;
        JsonObject data = Json.obj(root.getAsJsonObject(), "data");
        return data == null ? null : fileToVersion(data);
    }

    /**
     * 版本 id 是 {@code modId:fileId} 形式（CF 查单个文件必须同时知道两者）。
     * 上层拿到的 {@link Modrinth.VersionInfo#id} 也是这个格式，用 {@link #splitId} 拆开。
     */
    public static String[] splitId(String versionId) {
        if (versionId == null) return new String[]{"", ""};
        int at = versionId.indexOf(':');
        if (at <= 0) return new String[]{versionId.trim(), ""};
        return new String[]{versionId.substring(0, at).trim(), versionId.substring(at + 1).trim()};
    }

    // ---------------- 转换 ----------------

    private static Modrinth.Hit modToHit(JsonObject o) {
        Modrinth.Hit h = new Modrinth.Hit();
        h.projectId = String.valueOf(Json.lng(o, "id", 0L));
        // CF 的 slug 就在网站链接里（https://www.curseforge.com/minecraft/mc-mods/sodium）
        String slug = Json.str(o, "slug", "");
        if (Compat.isBlank(slug)) slug = slugFromUrl(Json.str(o, "websiteUrl", ""));
        JsonObject links = Json.obj(o, "links");
        if (Compat.isBlank(slug) && links != null) {
            slug = slugFromUrl(Json.str(links, "websiteUrl", ""));
        }
        h.slug = slug;
        h.title = Json.str(o, "name", h.slug);
        h.description = Json.str(o, "summary", "");
        h.downloads = Json.lng(o, "downloadCount", 0L);
        JsonArray cats = Json.arr(o, "categories");
        if (cats != null) {
            for (JsonElement c : cats) {
                if (!c.isJsonObject()) continue;
                String n = Json.str(c.getAsJsonObject(), "slug", "");
                if (!Compat.isBlank(n) && !h.categories.contains(n)) h.categories.add(n);
            }
        }
        JsonArray authors = Json.arr(o, "authors");
        if (authors != null && authors.size() > 0) {
            JsonElement a0 = authors.get(0);
            if (a0.isJsonObject()) h.author = Json.str(a0.getAsJsonObject(), "name", "");
        }
        JsonObject logo = Json.obj(o, "logo");
        if (logo != null) h.iconUrl = Json.str(logo, "thumbnailUrl", "");
        h.projectType = projectTypeOf(Json.integer(o, "classId", CLASS_MOD));
        // latestFilesIndexes 里带游戏版本，列表用它显示「支持哪些版本」
        JsonArray idx = Json.arr(o, "latestFilesIndexes");
        if (idx != null) {
            for (JsonElement e : idx) {
                if (!e.isJsonObject()) continue;
                String gv = Json.str(e.getAsJsonObject(), "gameVersion", "");
                if (!Compat.isBlank(gv) && !h.versions.contains(gv)) h.versions.add(gv);
            }
        }
        if (h.versions.isEmpty()) {
            JsonArray lf = Json.arr(o, "latestFiles");
            if (lf != null && lf.size() > 0) {
                JsonElement f0 = lf.get(0);
                if (f0.isJsonObject()) {
                    Modrinth.VersionInfo v = fileToVersion(f0.getAsJsonObject());
                    h.versions.addAll(v.gameVersions);
                    h.latestVersion = v.versionNumber;
                }
            }
        }
        return h;
    }

    /** 从 CF 的项目页 URL 里取出 slug（最后一段）。 */
    private static String slugFromUrl(String url) {
        if (Compat.isBlank(url)) return "";
        String s = url.split("\\?")[0];
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int at = s.lastIndexOf('/');
        return at >= 0 ? s.substring(at + 1) : s;
    }

    private static Modrinth.VersionInfo fileToVersion(JsonObject f) {
        Modrinth.VersionInfo v = new Modrinth.VersionInfo();
        String modId = String.valueOf(Json.lng(f, "modId", 0L));
        String fileId = String.valueOf(Json.lng(f, "id", 0L));
        v.id = modId + ":" + fileId;
        v.projectId = modId;
        v.name = Json.str(f, "displayName", "");
        String fileName = Json.str(f, "fileName", "");
        v.versionNumber = Compat.isBlank(v.name) ? fileName : v.name;
        v.versionType = releaseType(Json.integer(f, "releaseType", 1));
        v.datePublished = Json.str(f, "fileDate", "");
        JsonArray gv = Json.arr(f, "gameVersions");
        if (gv != null) {
            List<String> real = new ArrayList<>();
            List<String> loaders = new ArrayList<>();
            for (JsonElement e : gv) {
                if (!e.isJsonPrimitive()) continue;
                String s = e.getAsString();
                if (realGameVersions(s) != null) real.add(s);
                else loaders.add(s);
            }
            v.gameVersions.addAll(real);
            for (String l : loaders) {
                String norm = normalizeLoader(l);
                if (norm != null && !v.loaders.contains(norm)) v.loaders.add(norm);
            }
        }
        Modrinth.FileInfo fi = new Modrinth.FileInfo();
        fi.filename = fileName;
        fi.url = Json.str(f, "downloadUrl", "");
        fi.primary = true;
        fi.size = Json.lng(f, "fileLength", 0L);
        JsonArray hashes = Json.arr(f, "hashes");
        if (hashes != null) {
            for (JsonElement e : hashes) {
                if (!e.isJsonObject()) continue;
                JsonObject hh = e.getAsJsonObject();
                // algo: 1 = sha1, 2 = md5
                if (Json.integer(hh, "algo", 0) == 1) fi.sha1 = Json.str(hh, "value", "");
            }
        }
        // CF 有些文件不给 downloadUrl（例如只存在于整合包内部），这种直接丢掉
        if (!Compat.isBlank(fi.url)) v.files.add(fi);
        JsonArray deps = Json.arr(f, "dependencies");
        if (deps != null) {
            for (JsonElement e : deps) {
                if (!e.isJsonObject()) continue;
                JsonObject d = e.getAsJsonObject();
                Modrinth.Dependency dep = new Modrinth.Dependency();
                dep.projectId = String.valueOf(Json.lng(d, "modId", 0L));
                // relationType: 1 内嵌库 / 2 可选依赖 / 3 必需依赖 / 4 工具 / 5 不兼容 / 6 包含
                dep.dependencyType = relation(Json.integer(d, "relationType", 3));
                if (!"0".equals(dep.projectId)) v.dependencies.add(dep);
            }
        }
        return v;
    }

    // ---------------- 辅助 ----------------

    /**
     * CF 的 {@code gameVersions} 把 MC 版本号和 {@code Client}/{@code Server}/{@code Forge}/
     * {@code NeoForge}/{@code Fabric}/{@code Quilt}/{@code Bukkit} 这些端与加载器标记混在一个数组里。
     * 判断真版本号的可靠办法是看它长得像不像版本号——早年 Mojang 用过 {@code 1.20.1}、
     * 新方案用 {@code 26.3}，共同点都是「以数字开头且含点」。
     *
     * @return 是版本号就返回原串，否则 null
     */
    public static String realGameVersions(String s) {
        if (Compat.isBlank(s)) return null;
        String t = s.trim();
        if (!t.matches("[0-9]+(\\.[0-9]+)*.*")) return null;
        return t;
    }

    /** 把 CF 的端/加载器标记归一化成 Modrinth 的加载器名，认不出返回 null。 */
    public static String normalizeLoader(String s) {
        if (Compat.isBlank(s)) return null;
        String t = s.trim().toLowerCase();
        if (t.equals("forge")) return "forge";
        if (t.equals("fabric")) return "fabric";
        if (t.equals("quilt")) return "quilt";
        if (t.equals("neoforge")) return "neoforge";
        if (t.equals("liteloader")) return "liteloader";
        if (t.equals("cauldron")) return "cauldron";
        return null;
    }

    public static int classIdOf(String projectType) {
        if (Modrinth.TYPE_MODPACK.equals(projectType)) return CLASS_MODPACK;
        if (Modrinth.TYPE_RESOURCEPACK.equals(projectType)) return CLASS_RESOURCEPACK;
        if (Modrinth.TYPE_SHADER.equals(projectType)) return CLASS_SHADER;
        return CLASS_MOD;
    }

    /** 反方向：CF 的 classId → Modrinth 项目类型。 */
    public static String projectTypeOf(int classId) {
        if (classId == CLASS_MODPACK) return Modrinth.TYPE_MODPACK;
        if (classId == CLASS_RESOURCEPACK) return Modrinth.TYPE_RESOURCEPACK;
        if (classId == CLASS_SHADER) return Modrinth.TYPE_SHADER;
        if (classId == CLASS_WORLD) return Modrinth.TYPE_MOD;
        return Modrinth.TYPE_MOD;
    }

    public static int loaderTypeOf(String loader) {
        if (Compat.isBlank(loader)) return 0;
        String t = loader.trim().toLowerCase();
        if (t.equals("forge")) return LOADER_FORGE;
        if (t.equals("fabric")) return LOADER_FABRIC;
        if (t.equals("quilt")) return LOADER_QUILT;
        if (t.equals("neoforge")) return LOADER_NEOFORGE;
        if (t.equals("liteloader")) return LOADER_LITELOADER;
        if (t.equals("cauldron")) return LOADER_CAULDRON;
        return 0;
    }

    /** Modrinth 的排序名 → CF 的 sortField。 */
    private static int sortFieldOf(String sort) {
        if ("downloads".equals(sort)) return 6;
        if ("updated".equals(sort) || "newest".equals(sort)) return 3;
        if ("follows".equals(sort)) return 2;
        return 1; // Featured，CF 的默认相关度
    }

    private static String releaseType(int n) {
        if (n == 1) return "release";
        if (n == 2) return "beta";
        if (n == 3) return "alpha";
        return "release";
    }

    private static String relation(int n) {
        if (n == 2) return "optional";
        if (n == 5) return "incompatible";
        if (n == 6) return "embedded";
        if (n == 1 || n == 4) return "embedded";
        return "required";
    }

    /** “传进来的标识是不是纯数字”——纯数字才是 CF 的 modId，否则当作 slug。 */
    private static boolean isNumeric(String s) {
        if (Compat.isBlank(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    private static String get(String url) throws IOException {
        try {
            return Http.getStringWithHeaders(url, headers());
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("403")) {
                throw new IOException("CurseForge 拒绝请求（403）：API Key 无效或已被撤销");
            }
            throw e;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
