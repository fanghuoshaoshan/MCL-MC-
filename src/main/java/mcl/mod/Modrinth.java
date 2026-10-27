package mcl.mod;

import mcl.util.Compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.net.Http;
import mcl.util.Json;
import mcl.util.Log;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Modrinth 开放接口客户端（api.modrinth.com/v2）。
 * 用于模组 / 整合包 / 资源包 / 光影的搜索、版本解析与文件下载。
 */
public final class Modrinth {
    /** Modrinth 官方站的 API 根地址。内容源见 {@link ModSource}。 */
    public static final String API = ModSource.MODRINTH.base;

    public static final String TYPE_MOD = "mod";
    public static final String TYPE_MODPACK = "modpack";
    public static final String TYPE_RESOURCEPACK = "resourcepack";
    public static final String TYPE_SHADER = "shader";

    private Modrinth() {}

    /** 搜索结果条目（对应 /search 的 hit）。 */
    public static class Hit {
        public String projectId = "";
        public String slug = "";
        public String title = "";
        /** 中文名（只有通过中文检索带出来时才有值，用于列表里显示成「Sodium（钠）」）。 */
        public String zhTitle = "";
        public String description = "";
        public String author = "";
        public String iconUrl = "";
        public String projectType = "mod";
        public long downloads;
        public List<String> categories = new ArrayList<>();
        public List<String> versions = new ArrayList<>();
        public String latestVersion = "";
        /** 命中来源，用于界面提示：anchor / zhindex / translate / raw。 */
        public String matchedBy = "";

        /** 列表里显示的标题：有中文名时拼成「Sodium（钠）」。 */
        public String displayTitle() {
            String t = Compat.isBlank(title) ? slug : title;
            if (Compat.isBlank(zhTitle) || zhTitle.equalsIgnoreCase(t) || t.contains(zhTitle)) return t;
            return t + "（" + zhTitle + "）";
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public static class SearchResult {
        public int totalHits;
        public int offset;
        public int limit;
        public List<Hit> hits = new ArrayList<>();
    }

    /** 版本里的一个文件。 */
    public static class FileInfo {
        public String url = "";
        public String filename = "";
        public String sha1 = "";
        public long size;
        public boolean primary;
    }

    /** 依赖声明。 */
    public static class Dependency {
        public String projectId;
        public String versionId;
        public String dependencyType = "required";
    }

    /** 某个项目的具体版本。 */
    public static class VersionInfo {
        public String id = "";
        public String projectId = "";
        public String name = "";
        public String versionNumber = "";
        public String versionType = "release";
        public String datePublished = "";
        public String changelog = "";
        public List<String> gameVersions = new ArrayList<>();
        public List<String> loaders = new ArrayList<>();
        public List<FileInfo> files = new ArrayList<>();
        public List<Dependency> dependencies = new ArrayList<>();

        /** 主文件；没有 primary 标记时取第一个。 */
        public FileInfo primaryFile() {
            for (FileInfo f : files) if (f.primary) return f;
            return files.isEmpty() ? null : files.get(0);
        }

        public String loaderLabel() {
            if (loaders.isEmpty()) return "通用";
            List<String> out = new ArrayList<>();
            for (String l : loaders) {
                out.add(loaderName(l));
            }
            return String.join(" / ", out);
        }

        /** Modrinth 的加载器标识转显示名。 */
        static String loaderName(String l) {
            if ("fabric".equals(l)) return "Fabric";
            if ("quilt".equals(l)) return "Quilt";
            if ("forge".equals(l)) return "Forge";
            if ("neoforge".equals(l)) return "NeoForge";
            if ("liteloader".equals(l)) return "LiteLoader";
            if ("rift".equals(l)) return "Rift";
            if ("bukkit".equals(l)) return "Bukkit";
            if ("paper".equals(l)) return "Paper";
            if ("purpur".equals(l)) return "Purpur";
            if ("spigot".equals(l)) return "Spigot";
            return l;
        }

        @Override
        public String toString() {
            return versionNumber + "  ·  " + String.join(",", gameVersions) + "  ·  " + loaderLabel();
        }
    }

    /** 项目详情（用于拿到标题、类型）。 */
    public static class Project {
        public String id = "";
        public String slug = "";
        public String title = "";
        public String description = "";
        public String projectType = "mod";
        public String iconUrl = "";
    }

    // ---------------- 接口 ----------------

    public static SearchResult search(String query, String projectType, String gameVersion, String loader,
                                      String sort, int offset, int limit) throws IOException {
        return search(ModSource.MODRINTH, query, projectType, gameVersion, loader, sort, offset, limit);
    }

    /**
     * 在指定内容源上搜索。CurseForge 走的是另一套协议，由 {@link CurseForge} 实现，
     * 这个方法只在 Modrinth 上生效。
     */
    public static SearchResult search(ModSource src, String query, String projectType, String gameVersion,
                                      String loader, String sort, int offset, int limit) throws IOException {
        List<String> facets = new ArrayList<>();
        if (projectType != null && !Compat.isBlank(projectType)) facets.add("[\"project_type:" + projectType + "\"]");
        if (gameVersion != null && !Compat.isBlank(gameVersion)) facets.add("[\"versions:" + gameVersion + "\"]");
        if (loader != null && !Compat.isBlank(loader)) facets.add("[\"categories:" + loader + "\"]");
        StringBuilder url = new StringBuilder(src.base).append("/search?query=")
                .append(enc(query == null ? "" : query))
                .append("&limit=").append(Math.max(1, Math.min(50, limit)))
                .append("&offset=").append(Math.max(0, offset));
        if (sort != null && !Compat.isBlank(sort)) url.append("&index=").append(sort);
        if (!facets.isEmpty()) {
            url.append("&facets=").append(enc("[" + String.join(",", facets) + "]"));
        }
        JsonObject root = JsonParser.parseString(Http.getString(url.toString())).getAsJsonObject();
        SearchResult r = new SearchResult();
        r.totalHits = Json.integer(root, "total_hits", 0);
        r.offset = Json.integer(root, "offset", 0);
        r.limit = Json.integer(root, "limit", limit);
        JsonArray hits = Json.arr(root, "hits");
        if (hits != null) {
            for (JsonElement e : hits) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                Hit h = new Hit();
                h.projectId = Json.str(o, "project_id", "");
                h.slug = Json.str(o, "slug", "");
                h.title = Json.str(o, "title", h.slug);
                h.description = Json.str(o, "description", "");
                h.author = Json.str(o, "author", "");
                h.iconUrl = Json.str(o, "icon_url", "");
                h.projectType = Json.str(o, "project_type", "mod");
                h.downloads = Json.lng(o, "downloads", 0);
                h.latestVersion = Json.str(o, "latest_version", "");
                JsonArray cats = Json.arr(o, "display_categories");
                if (cats != null) for (JsonElement c : cats) h.categories.add(c.getAsString());
                JsonArray vers = Json.arr(o, "versions");
                if (vers != null) for (JsonElement c : vers) h.versions.add(c.getAsString());
                r.hits.add(h);
            }
        }
        return r;
    }

    public static Project project(String idOrSlug) throws IOException {
        return project(ModSource.MODRINTH, idOrSlug);
    }

    /**
     * 按 slug（或项目 id）批量取项目，转成搜索结果条目。
     *
     * <p>走 {@code GET /v2/projects?ids=["a","b"]}，每批 20 个。<b>不存在的 slug 会被
     * 接口静默丢弃</b>，所以返回条数可能少于入参 —— 这正是「中文索引 → Modrinth 反查」
     * 需要的语义：命中的留下，没命中的自动消失。
     *
     * @return 顺序不保证与入参一致，调用方需要按 slug 建索引
     */
    public static List<Hit> hitsBySlugs(ModSource src, List<String> slugs) throws IOException {
        List<Hit> out = new ArrayList<>();
        if (slugs == null || slugs.isEmpty()) return out;
        List<String> clean = new ArrayList<>();
        for (String s : slugs) {
            if (Compat.isBlank(s)) continue;
            if (!clean.contains(s)) clean.add(s);
        }
        for (int from = 0; from < clean.size(); from += 20) {
            int to = Math.min(from + 20, clean.size());
            StringBuilder ids = new StringBuilder("[");
            for (int i = from; i < to; i++) {
                if (i > from) ids.append(',');
                ids.append('"').append(jsonEscape(clean.get(i))).append('"');
            }
            ids.append(']');
            JsonElement root = JsonParser.parseString(
                    Http.getString(src.base + "/projects?ids=" + enc(ids.toString())));
            if (!root.isJsonArray()) continue;
            for (JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                out.add(projectToHit(e.getAsJsonObject()));
            }
        }
        return out;
    }

    /** 把 /projects 的完整项目对象转成 Hit（渲染用得到的字段才留）。 */
    private static Hit projectToHit(JsonObject o) {
        Hit h = new Hit();
        h.projectId = Json.str(o, "id", "");
        h.slug = Json.str(o, "slug", "");
        h.title = Json.str(o, "title", h.slug);
        h.description = Json.str(o, "description", "");
        h.iconUrl = Json.str(o, "icon_url", "");
        h.projectType = Json.str(o, "project_type", "mod");
        h.downloads = Json.lng(o, "downloads", 0);
        JsonArray cats = Json.arr(o, "categories");
        if (cats != null) for (JsonElement c : cats) h.categories.add(c.getAsString());
        JsonArray gv = Json.arr(o, "game_versions");
        if (gv != null) for (JsonElement c : gv) h.versions.add(c.getAsString());
        return h;
    }

    /** 拼进 JSON 数组的字符串转义（slug 只会是 a-z0-9-_，这里防御一下）。 */
    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c >= 0x20) sb.append(c);
        }
        return sb.toString();
    }

    public static Project project(ModSource src, String idOrSlug) throws IOException {
        JsonObject o = JsonParser.parseString(Http.getString(src.base + "/project/" + enc(idOrSlug)))
                .getAsJsonObject();
        Project p = new Project();
        p.id = Json.str(o, "id", "");
        p.slug = Json.str(o, "slug", idOrSlug);
        p.title = Json.str(o, "title", p.slug);
        p.description = Json.str(o, "description", "");
        p.projectType = Json.str(o, "project_type", "mod");
        p.iconUrl = Json.str(o, "icon_url", "");
        return p;
    }

    /**
     * 列出项目的版本，可按游戏版本与加载器过滤（传 null 表示不限）。
     * Modrinth 的过滤参数要求 JSON 数组格式。
     */
    public static List<VersionInfo> versions(String idOrSlug, String gameVersion, String loader) throws IOException {
        return versions(ModSource.MODRINTH, idOrSlug, gameVersion, loader);
    }

    public static List<VersionInfo> versions(ModSource src, String idOrSlug, String gameVersion, String loader)
            throws IOException {
        StringBuilder url = new StringBuilder(src.base).append("/project/").append(enc(idOrSlug)).append("/version");
        List<String> query = new ArrayList<>();
        if (gameVersion != null && !Compat.isBlank(gameVersion)) {
            query.add("game_versions=" + enc("[\"" + gameVersion + "\"]"));
        }
        if (loader != null && !Compat.isBlank(loader)) {
            query.add("loaders=" + enc("[\"" + loader + "\"]"));
        }
        if (!query.isEmpty()) url.append('?').append(String.join("&", query));
        String body = Http.getString(url.toString());
        return parseVersions(body);
    }

    public static VersionInfo version(String versionId) throws IOException {
        return version(ModSource.MODRINTH, versionId);
    }

    public static VersionInfo version(ModSource src, String versionId) throws IOException {
        JsonObject o = JsonParser.parseString(Http.getString(src.base + "/version/" + enc(versionId)))
                .getAsJsonObject();
        return parseVersion(o);
    }

    /**
     * 用单个文件 SHA1 反查所属版本（GET /version_file/{hash}）。
     * 查不到时返回 {@code null}。
     */
    public static VersionInfo versionByHash(ModSource src, String sha1) throws IOException {
        if (sha1 == null || sha1.length() != 40) return null;
        String body;
        try {
            body = Http.getString(src.base + "/version_file/" + enc(sha1) + "?algorithm=sha1");
        } catch (IOException e) {
            // 404 表示这份文件不在该源上，是正常情况，不当成错误抛出
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("404") || msg.contains("Not Found") || msg.contains("not_found")) return null;
            throw e;
        }
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (o.has("error")) return null;
        return parseVersion(o);
    }

    /**
     * 用文件 SHA1 批量反查所属版本（POST /version_files），用于已安装模组的更新检查。
     * 返回 sha1 → 版本 的映射（查不到的文件不会出现在结果里）。
     */
    public static java.util.Map<String, VersionInfo> versionsByHashes(List<String> hashes) throws IOException {
        java.util.Map<String, VersionInfo> out = new java.util.LinkedHashMap<>();
        if (hashes == null || hashes.isEmpty()) return out;
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String h : hashes) arr.add(h);
        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        body.add("hashes", arr);
        body.addProperty("algorithm", "sha1");
        String resp = Http.postJson(API + "/version_files", body.toString());
        JsonElement root = JsonParser.parseString(resp);
        if (!root.isJsonObject()) return out;
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            out.put(e.getKey(), parseVersion(e.getValue().getAsJsonObject()));
        }
        return out;
    }

    /** 项目的全部版本（不限游戏版本 / 加载器），用于更新检查里挑最新版。 */
    public static List<VersionInfo> allVersions(String idOrSlug) throws IOException {
        return versions(idOrSlug, null, null);
    }

    /** 取指定项目在目标环境下最合适的版本；没有匹配时退回不限过滤的最前一个。 */
    public static VersionInfo bestVersion(String idOrSlug, String gameVersion, String loader) throws IOException {
        return bestVersion(ModSource.MODRINTH, idOrSlug, gameVersion, loader);
    }

    public static VersionInfo bestVersion(ModSource src, String idOrSlug, String gameVersion, String loader)
            throws IOException {
        List<VersionInfo> list = versions(src, idOrSlug, gameVersion, loader);
        if (list.isEmpty()) {
            Log.warn(src.label + " 上没有 " + idOrSlug + " 匹配 " + gameVersion + "/" + loader + " 的版本，放宽条件重试");
            list = versions(src, idOrSlug, null, null);
        }
        if (list.isEmpty()) return null;
        for (VersionInfo v : list) if ("release".equals(v.versionType)) return v;
        return list.get(0);
    }

    private static List<VersionInfo> parseVersions(String body) {
        List<VersionInfo> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray()) return out;
        for (JsonElement e : root.getAsJsonArray()) {
            if (e.isJsonObject()) out.add(parseVersion(e.getAsJsonObject()));
        }
        return out;
    }

    private static VersionInfo parseVersion(JsonObject o) {
        VersionInfo v = new VersionInfo();
        v.id = Json.str(o, "id", "");
        v.projectId = Json.str(o, "project_id", "");
        v.name = Json.str(o, "name", "");
        v.versionNumber = Json.str(o, "version_number", "");
        v.versionType = Json.str(o, "version_type", "release");
        v.datePublished = Json.str(o, "date_published", "");
        v.changelog = Json.str(o, "changelog", "");
        JsonArray gv = Json.arr(o, "game_versions");
        if (gv != null) for (JsonElement e : gv) v.gameVersions.add(e.getAsString());
        JsonArray ld = Json.arr(o, "loaders");
        if (ld != null) for (JsonElement e : ld) v.loaders.add(e.getAsString());
        JsonArray files = Json.arr(o, "files");
        if (files != null) {
            for (JsonElement e : files) {
                if (!e.isJsonObject()) continue;
                JsonObject f = e.getAsJsonObject();
                FileInfo fi = new FileInfo();
                fi.url = Json.str(f, "url", "");
                fi.filename = Json.str(f, "filename", "");
                fi.primary = Json.bool(f, "primary", false);
                fi.size = Json.lng(f, "size", 0);
                JsonObject hashes = Json.obj(f, "hashes");
                fi.sha1 = Json.str(hashes, "sha1", "");
                if (fi.url.isEmpty()) continue;
                v.files.add(fi);
            }
        }
        JsonArray deps = Json.arr(o, "dependencies");
        if (deps != null) {
            for (JsonElement e : deps) {
                if (!e.isJsonObject()) continue;
                JsonObject d = e.getAsJsonObject();
                Dependency dep = new Dependency();
                dep.projectId = Json.str(d, "project_id", null);
                dep.versionId = Json.str(d, "version_id", null);
                dep.dependencyType = Json.str(d, "dependency_type", "required");
                v.dependencies.add(dep);
            }
        }
        return v;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /** 项目类型对应的本地目录名。 */
    public static String dirOf(String projectType) {
        if (TYPE_MODPACK.equals(projectType == null ? "" : projectType)) {
            return "modpacks";
        }
        if (TYPE_RESOURCEPACK.equals(projectType == null ? "" : projectType)) {
            return "resourcepacks";
        }
        if (TYPE_SHADER.equals(projectType == null ? "" : projectType)) {
            return "shaderpacks";
        }
        return "mods";
    }

    public static String typeLabel(String projectType) {
        if (TYPE_MODPACK.equals(projectType == null ? "" : projectType)) {
            return "整合包";
        }
        if (TYPE_RESOURCEPACK.equals(projectType == null ? "" : projectType)) {
            return "资源包";
        }
        if (TYPE_SHADER.equals(projectType == null ? "" : projectType)) {
            return "光影";
        }
        return "模组";
    }
}
