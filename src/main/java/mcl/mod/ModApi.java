package mcl.mod;

import mcl.util.Compat;
import mcl.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内容源统一入口：把 Modrinth 与 CurseForge 的差异挡在这一层后面。
 *
 * <p>两个站的数据模型差别很大：
 * <pre>
 *                Modrinth                  CurseForge
 *   项目 id      随机字符串                  纯数字 modId
 *   版本 id      随机字符串                  "modId:fileId"
 *   协议         Labrinth /v2                /v1 + x-api-key
 *   批量查项目   GET /projects?ids=[...]     POST /v1/mods（只支持数字 id）
 * </pre>
 * 上层（界面 / 安装器）统一调这里，不用自己判断源。
 */
public final class ModApi {

    private ModApi() {}

    /** 保证不为 null：多数方法默认走 Modrinth。 */
    private static ModSource norm(ModSource src) {
        return src == null ? ModSource.MODRINTH : src;
    }

    /** 在指定内容源上搜索。 */
    public static Modrinth.SearchResult search(ModSource src, String query, String projectType,
                                               String gameVersion, String loader, String sort,
                                               int offset, int limit) throws IOException {
        ModSource s = norm(src);
        if (s.isCurseForge()) {
            return CurseForge.search(query, projectType, gameVersion, loader, sort, offset, limit);
        }
        return Modrinth.search(s, query, projectType, gameVersion, loader, sort, offset, limit);
    }

    /**
     * 项目详情。
     *
     * <p>CurseForge 没有「按 id 拿单个项目」以外的查详情方式，
     * 所以这里统一先从 {@link #hit} 取一条记录再转成 Project。
     */
    public static Modrinth.Project project(ModSource src, String idOrSlug) throws IOException {
        ModSource s = norm(src);
        Modrinth.Hit h = hit(s, idOrSlug);
        if (h == null) throw new IOException(s.label + " 上没有 " + idOrSlug);
        Modrinth.Project p = new Modrinth.Project();
        p.id = h.projectId;
        p.slug = h.slug;
        p.title = h.title;
        p.description = h.description;
        p.projectType = h.projectType;
        p.iconUrl = h.iconUrl;
        return p;
    }

    /**
     * 取单个项目：Modrinth 的 id 与 slug 接口都认，CurseForge 则要先区分传进来的是
     * 数字 modId 还是 slug —— 数字走 {@code POST /v1/mods}，否则用 {@code ?slug=} 精确查。
     */
    public static Modrinth.Hit hit(ModSource src, String idOrSlug) throws IOException {
        ModSource s = norm(src);
        if (Compat.isBlank(idOrSlug)) return null;
        String id = idOrSlug.trim();
        if (s.isCurseForge()) {
            if (isNumeric(id)) {
                List<Modrinth.Hit> hs = CurseForge.modsByIds(Collections.singletonList(id));
                return hs.isEmpty() ? null : hs.get(0);
            }
            return CurseForge.bySlug(id, null);
        }
        List<Modrinth.Hit> hs = Modrinth.hitsBySlugs(s, Collections.singletonList(id));
        return hs.isEmpty() ? null : hs.get(0);
    }

    /** 批量按 slug 取项目。CurseForge 没有对应的批量接口，只能逐个查。 */
    public static List<Modrinth.Hit> hitsBySlugs(ModSource src, List<String> slugs) throws IOException {
        ModSource s = norm(src);
        if (s.isCurseForge()) {
            List<Modrinth.Hit> out = new ArrayList<>();
            if (slugs == null) return out;
            for (String slug : slugs) {
                if (Compat.isBlank(slug)) continue;
                Modrinth.Hit h = CurseForge.bySlug(slug, null);
                if (h != null) out.add(h);
            }
            return out;
        }
        return Modrinth.hitsBySlugs(s, slugs);
    }

    /** 批量按项目 id 取项目。只有 CurseForge 有原生批量；Modrinth 侧逐个查。 */
    public static List<Modrinth.Hit> modsByIds(ModSource src, List<String> ids) throws IOException {
        ModSource s = norm(src);
        if (s.isCurseForge()) return CurseForge.modsByIds(ids);
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        // Modrinth 的 /projects?ids 同时接受 id 和 slug，所以这里直接复用
        return Modrinth.hitsBySlugs(s, ids);
    }

    /** 列出版本（CurseForge 这里返回的是「文件」列表）。 */
    public static List<Modrinth.VersionInfo> versions(ModSource src, String idOrSlug, String gameVersion,
                                                      String loader) throws IOException {
        ModSource s = norm(src);
        if (s.isCurseForge()) return CurseForge.files(idOrSlug, gameVersion, loader);
        return Modrinth.versions(s, idOrSlug, gameVersion, loader);
    }

    /**
     * 按版本 id 取单个版本。CurseForge 的 id 是 {@code modId:fileId}，用
     * {@link CurseForge#splitId} 拆开后才能查文件。
     */
    public static Modrinth.VersionInfo version(ModSource src, String versionId) throws IOException {
        ModSource s = norm(src);
        if (Compat.isBlank(versionId)) return null;
        if (s.isCurseForge()) {
            String[] parts = CurseForge.splitId(versionId);
            if (Compat.isBlank(parts[0]) || Compat.isBlank(parts[1])) return null;
            return CurseForge.file(parts[0], parts[1]);
        }
        return Modrinth.version(s, versionId);
    }

    /** 项目的全部版本（不限游戏版本 / 加载器），用于更新检查挑最新版。 */
    public static List<Modrinth.VersionInfo> allVersions(ModSource src, String idOrSlug) throws IOException {
        return versions(src, idOrSlug, null, null);
    }

    /** 取指定项目在目标环境下最合适的版本；没匹配时退回不限过滤的最前一个。 */
    public static Modrinth.VersionInfo bestVersion(ModSource src, String idOrSlug, String gameVersion,
                                                   String loader) throws IOException {
        ModSource s = norm(src);
        List<Modrinth.VersionInfo> list = versions(s, idOrSlug, gameVersion, loader);
        if (list.isEmpty()) {
            Log.warn(s.label + " 上没有 " + idOrSlug + " 匹配 " + gameVersion + "/" + loader + " 的版本，放宽条件重试");
            list = versions(s, idOrSlug, null, null);
        }
        if (list.isEmpty()) return null;
        for (Modrinth.VersionInfo v : list) if ("release".equals(v.versionType)) return v;
        return list.get(0);
    }

    private static boolean isNumeric(String s) {
        if (Compat.isBlank(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }
}
