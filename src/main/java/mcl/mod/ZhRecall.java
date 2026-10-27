package mcl.mod;

import mcl.util.Compat;
import mcl.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文查询在 Modrinth 上的多路召回与融合。
 *
 * <p><b>为什么需要这一层。</b>Modrinth 的索引里既有英文项目，也夹着一堆国内作者上传的中文项目
 * （汉化包、附属模组），所以中文并不是「搜不到」，而是<b>被周边内容淹没</b>：
 * 搜「钠」返回的全是「钠-汉化包」「钠-设备检测」，正主 {@code sodium} 反而看不见。
 * 单纯把中文翻成英文只能救回译名表里那两百来条热门词，冷门词仍然抓瞎。
 *
 * <p><b>四条召回路径</b>，由强到弱：
 *
 * <ol>
 *   <li><b>锚定</b>（{@code anchor}）—— 译名表能唯一确定某个项目时，直接按 slug 取到它，
 *       钉在第一条。这是「钠 → Sodium」这种用户最想要的精确命中。</li>
 *   <li><b>中文索引</b>（{@code zhindex}）—— 本地全站对照表（构建期从 MC 社区站标题挖出来、
 *       再到 Modrinth 反查过 slug），运行时零网络依赖。这些 slug 两源通用：
 *       Modrinth 上存在的直接用，CurseForge 上同名存在的也能桥接过去。</li>
 *   <li><b>译名</b>（{@code translate}）—— 译名表把中文切成英文关键词后搜 Modrinth，
 *       用于扩召回同类项目（搜「钠」顺带带出 Sodium Extra、Rubidium）。</li>
 *   <li><b>原文</b>（{@code raw}）—— 直接拿中文丢给 Modrinth。单独看质量差，
 *       但有些项目的中文名只在源站标题里、不在本地索引中，所以保留，排在最后。</li>
 * </ol>
 *
 * <p>四路结果按 slug 去重后按上述优先级拼接，再把「汉化包」这类内容降权挪到尾部
 * （除非用户自己就在搜汉化）。后续分页只走主召回，由界面侧按 slug 去重。
 */
public final class ZhRecall {

    /**
     * CurseForge 侧按 slug 逐个反查时最多试几个候选。
     *
     * <p>这是<b>延迟与召回的折中</b>：中文索引一次可能给出几十个候选 slug，
     * 而 CF 没有批量端点，只能一个一个查（每个约 200~400ms）。全查会大到几秒、
     * 让用户觉得卡死；而真正想要的那个通常就在相关度最高的前几个里。
     * 查不到也不影响大局——主召回（译名 / 原文）那边还有结果兜底。
     */
    private static final int CF_LOOKUP_BUDGET = 10;

    /**
     * slug → CurseForge 项目 的进程内缓存，同时记住「查过但没有」。
     *
     * <p>用户改一次版本筛选或翻一次页就会重跑一遍融合，命中的那几个 slug 是同一批，
     * 不去重的话每次都要重新打一圈网络请求。
     */
    private static final CfSlugCache CF_CACHE = new CfSlugCache();

    private ZhRecall() {}

    /** 融合结果：除了命中列表，还带着「怎么得来的」，供界面解释与分页使用。 */
    public static final class Result {
        public Modrinth.SearchResult merged = new Modrinth.SearchResult();
        /** 下一页该传给服务端的 offset：只累计真正消耗了服务端分页的主召回。 */
        public int nextOffset;
        /** 锚定命中的中文词与官方名；空串表示没锚定。 */
        public String anchorZh = "";
        public String anchorTitle = "";
        /** 各路贡献了多少条（去重后）。 */
        public int fromAnchor;
        public int fromZhIndex;
        public int fromTranslate;
        public int fromRaw;
        /** 主召回实际用的关键词（可能是翻译后的英文），以及是否用了翻译。 */
        public String effectiveQuery = "";
        public boolean translated;
        /** 中文索引这一路是否走通（失败只降级，不影响结果）。 */
        public boolean zhIndexOk;
        /** 中文索引这一路贡献的 slug 是否在目标源上找到了对应项目。 */
        public int zhIndexTried;
        public int zhIndexFound;

        /** 给界面的一行解释。 */
        public String summary() {
            if (merged.hits.isEmpty()) return "没有结果";
            StringBuilder sb = new StringBuilder();
            if (fromAnchor > 0) sb.append("精确匹配「").append(anchorZh).append("」");
            if (fromZhIndex > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("中文索引 +").append(fromZhIndex);
            }
            if (translated && !Compat.isBlank(effectiveQuery)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("「").append(effectiveQuery).append("」相关");
            }
            return sb.toString();
        }
    }

    /**
     * 做一次搜索。非中文查询时行为与直接请求数据源完全一致（只包了一层）。
     *
     * <p>融合发生在<b>所有</b>源上：Modrinth 与 CurseForge 都只索引英文，但用户的输入是中文，
     * 所以本地中文索引对两边同样有意义。
     */
    public static Result search(ModSource src, String raw, String projectType, String gameVersion,
                                String loader, String sort, int offset, int limit) throws IOException {
        String q = raw == null ? "" : raw.trim();
        Result out = new Result();
        out.effectiveQuery = q;

        boolean cjk = ZhSearch.hasCjk(q);
        List<String> cands = cjk ? ZhSearch.candidates(q) : null;
        // 翻译只认出查询里一小部分时（「剑与王国」只认出「剑」）不可靠，直接走原文
        if (cands != null && !ZhSearch.trustTranslate(q)) {
            Log.info("译名表对「" + q + "」覆盖不足，改用原文搜索");
            cands = null;
        }

        // ---------- 主召回：译者优先，原文兜底 ----------
        Modrinth.SearchResult primary = null;
        String used = q;
        boolean translated = false;
        if (cands != null) {
            for (String cand : cands) {
                Modrinth.SearchResult t = ModApi.search(src, cand, projectType, gameVersion, loader,
                        sort, offset, limit);
                if (primary == null || t.totalHits > primary.totalHits) {
                    primary = t;
                    used = cand;
                    translated = true;
                }
                if (t.totalHits > 0) break;
            }
        }
        if (primary == null || primary.totalHits == 0) {
            Modrinth.SearchResult t = ModApi.search(src, q, projectType, gameVersion, loader,
                    sort, offset, limit);
            if (primary == null || t.totalHits > primary.totalHits) {
                primary = t;
                used = q;
                translated = false;
            }
        }
        out.effectiveQuery = used;
        out.translated = translated && !q.equals(used);
        out.nextOffset = offset + primary.hits.size();

        // 翻页时只走主召回，重叠部分靠调用方按 slug 去重
        if (offset > 0) {
            out.merged = primary;
            return out;
        }

        // ---------- 融合 ----------
        ZhSearch.Anchor anchor = cjk ? ZhSearch.anchor(q) : null;
        List<String> slugs = new ArrayList<>();
        Map<String, String> zhNames = new LinkedHashMap<>(); // slug → 对应的中文名
        if (anchor != null) slugs.add(anchor.slug);

        // 中文索引：本地全站对照表。数据是构建期从社区站标题挖出来、
        // 再到 Modrinth 反查过 slug 的，所以运行时不依赖那个站点是否在线。
        // 索引里的条目都保证在 Modrinth 上真实存在（只存在于 MCBBS 的那批已剔除），
        // 而同一个 slug 实测约一半在 CurseForge 上也同名存在，
        // 所以同一份索引可以桥接到两个源（见下面的反查分流）。
        List<ZhIndex.Match> zhMatches = ZhIndex.match(q, projectType, 40);
        out.zhIndexOk = !zhMatches.isEmpty();
        out.zhIndexTried = zhMatches.size();
        for (ZhIndex.Match m : zhMatches) {
            String s = m.entry.slug;
            if (Compat.isBlank(s) || slugs.contains(s)) continue;
            slugs.add(s);
            zhNames.put(s, m.name);
        }

        // 拼音：输入 na / jxdl / jixiedongli 这种打法，走拼音倒排索引
        if (slugs.size() < 40) {
            for (String s : ZhIndex.pinyinMatch(q, 20)) {
                if (Compat.isBlank(s) || slugs.contains(s)) continue;
                slugs.add(s);
            }
        }

        // 批量反查：一次请求解决「锚定 + 中文索引」两路的存在性判断
        Map<String, Modrinth.Hit> bySlug = new LinkedHashMap<>();
        if (!slugs.isEmpty()) {
            out.zhIndexTried = slugs.size();
            try {
                if (src.isCurseForge()) {
                    // CurseForge 只有「按数字 id 批量」和「按单个 slug 查」两种接口，
                    // 没有像 Modrinth 那样一次喂一批 slug 的端点。逐个查注定慢，
                    // 所以用 CF_LOOKUP_BUDGET 卡一个上限：只试最相关的前几个，查不到就算了。
                    // 缓存避免同一个 slug 在一次会话里被反复查（分页、改筛选都会重来一次）。
                    int budget = CF_LOOKUP_BUDGET;
                    for (String s : slugs) {
                        if (budget <= 0) break;
                        Modrinth.Hit h = CF_CACHE.slug(s, projectType);
                        budget--;
                        if (h != null) bySlug.put(s, h);
                    }
                } else {
                    for (Modrinth.Hit h : Modrinth.hitsBySlugs(ModSource.MODRINTH, slugs)) {
                        bySlug.put(h.slug, h);
                    }
                }
            } catch (IOException e) {
                Log.warn("批量反查项目失败，中文索引这一路降级：" + e.getMessage());
            }
        }
        out.zhIndexFound = bySlug.size();

        LinkedHashMap<String, Modrinth.Hit> picked = new LinkedHashMap<>();

        // 1) 锚定：钉第一条
        if (anchor != null) {
            Modrinth.Hit h = bySlug.get(anchor.slug);
            if (h != null && typeOk(h, projectType)) {
                h.matchedBy = "anchor";
                if (Compat.isBlank(h.zhTitle)) h.zhTitle = anchor.zh;
                picked.put(h.slug, h);
                out.anchorZh = anchor.zh;
                out.anchorTitle = h.title;
            }
        }

        // 2) 中文索引 / 拼音：按索引相关度（同分按热度）顺序
        for (String s : slugs) {
            if (picked.containsKey(s)) continue;
            Modrinth.Hit h = bySlug.get(s);
            if (h == null) continue;
            if (!typeOk(h, projectType)) continue;
            h.matchedBy = "zhindex";
            String zn = zhNames.get(s);
            if (!Compat.isBlank(zn)) h.zhTitle = zn;
            picked.put(s, h);
        }

        // 3) 主召回补在后面
        for (Modrinth.Hit h : primary.hits) {
            if (Compat.isBlank(h.slug) || picked.containsKey(h.slug)) continue;
            h.matchedBy = translated ? "translate" : "raw";
            picked.put(h.slug, h);
        }

        // 4) 排序：汉化包降权（用户自己搜汉化时除外）
        List<Modrinth.Hit> merged = demoteLocalization(picked.values(), q);

        out.merged.totalHits = primary.totalHits;
        out.merged.offset = offset;
        out.merged.limit = limit;
        out.merged.hits = merged;
        for (Modrinth.Hit h : merged) {
            if ("anchor".equals(h.matchedBy)) out.fromAnchor++;
            else if ("zhindex".equals(h.matchedBy)) out.fromZhIndex++;
            else if ("translate".equals(h.matchedBy)) out.fromTranslate++;
            else out.fromRaw++;
        }
        return out;
    }

    /** 项目类型是否接受：用户没指定类型时一律接受。 */
    private static boolean typeOk(Modrinth.Hit h, String projectType) {
        if (Compat.isBlank(projectType)) return true;
        return projectType.equals(h.projectType);
    }

    /**
     * 把「汉化包 / 翻译包」这类内容挪到列表尾部。
     *
     * <p>原因是中文查询会大量命中它们：搜「钠」本来想找 Sodium，却先看到「钠-汉化包」。
     * 但用户明确在搜汉化时（查询里带「汉化」「翻译」「chinese」）就不动，保持原序。
     * 锚定命中的项目永远不降权。
     */
    private static List<Modrinth.Hit> demoteLocalization(Iterable<Modrinth.Hit> hits, String q) {
        boolean want = wantsLocalization(q);
        List<Modrinth.Hit> main = new ArrayList<>();
        List<Modrinth.Hit> tail = new ArrayList<>();
        for (Modrinth.Hit h : hits) {
            if (want || "anchor".equals(h.matchedBy) || !looksLocalization(h)) main.add(h);
            else tail.add(h);
        }
        main.addAll(tail);
        return main;
    }

    /** 用户是不是在找汉化包。 */
    public static boolean wantsLocalization(String q) {
        if (q == null) return false;
        String s = q.toLowerCase();
        return s.contains("汉化") || s.contains("漢化") || s.contains("翻译")
                || s.contains("chinese") || s.contains("中文") || s.contains("localization");
    }

    /** 这个项目看起来像汉化 / 翻译包吗。 */
    public static boolean looksLocalization(Modrinth.Hit h) {
        if (h == null) return false;
        String t = (h.title == null ? "" : h.title).toLowerCase();
        String d = (h.description == null ? "" : h.description).toLowerCase();
        String s = t + " " + d;
        return s.contains("汉化") || s.contains("漢化") || s.contains("翻译")
                || s.contains("localization") || s.contains("translation")
                || s.contains("译名") || s.contains("zh_cn") || s.contains("zh-cn")
                || s.contains("chinese");
    }

    /**
     * 从源站的标题里抽出中文名：{@code "机械动力 - Create"} → {@code "机械动力"}，
     * {@code "钠 - Sodium"} → {@code "钠"}。
     *
     * <p>标题格式通常是「中文名 - 英文名」，取第一个中英分隔符之前的部分。
     * 抽不出满意结果（没汉字、太长、太短）时返回空串，表示这个标题不适合当名字用。
     */
    public static String zhPart(String title) {
        if (Compat.isBlank(title)) return "";
        String t = title.trim();
        int cut = t.length();
        String[] seps = {" - ", " | ", " – ", " — ", "／", " / "};
        for (int i = 0; i < seps.length; i++) {
            int p = t.indexOf(seps[i]);
            if (p > 0 && p < cut) cut = p;
        }
        String head = t.substring(0, cut).trim();
        // 去掉结尾的括号补充，例如「机械动力（1.20.1）」
        int paren = head.indexOf('（');
        if (paren > 0) head = head.substring(0, paren).trim();
        paren = head.indexOf('(');
        if (paren > 0) head = head.substring(0, paren).trim();
        if (head.length() < 1 || head.length() > 16) return "";
        if (!ZhSearch.hasCjk(head)) return "";
        // 纯标点 / 纯数字的没用
        boolean letter = false;
        for (int i = 0; i < head.length(); ) {
            int cp = head.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                letter = true;
                break;
            }
            i += Character.charCount(cp);
        }
        return letter ? head : "";
    }

    /**
     * 带负缓存的「Modrinth slug → CurseForge 项目」查询。
     *
     * <p>为什么要负缓存：中文索引给出的候选 slug 里，大多数在 CF 上并不存在
     * （实测约一半）。如果不记住「这个 slug 查过了、没有」，每次搜索都要为同一批
     * 不存在的 slug 重发一轮请求，白白翻倍延迟。
     *
     * <p>缓存只在进程内，重启即失效——CF 上的项目确实会新增，不想让用户因为几个小时的
     * 陈旧缓存以为某个模组不存在。
     */
    private static final class CfSlugCache {
        private final Map<String, Modrinth.Hit> positive = new LinkedHashMap<>();
        private final Map<String, Boolean> negative = new LinkedHashMap<>();

        /** @return CF 上的项目；不存在或查询失败都返回 null */
        synchronized Modrinth.Hit slug(String slug, String projectType) {
            if (Compat.isBlank(slug)) return null;
            String key = slug.toLowerCase() + "@" + (projectType == null ? "mod" : projectType);
            if (positive.containsKey(key)) return positive.get(key);
            if (negative.containsKey(key)) return null;
            try {
                Modrinth.Hit h = CurseForge.bySlug(slug, projectType);
                if (h != null) {
                    positive.put(key, h);
                    return h;
                }
                negative.put(key, Boolean.TRUE);
            } catch (IOException e) {
                // 网络抖动不该让用户等到下一次还重试同一个；但也不永久判定它不存在，
                // 所以这里什么都不做——本次当未命中，下次还会再试。
                Log.warn("按 slug 查 CurseForge 失败（" + slug + "）：" + e.getMessage());
            }
            return null;
        }
    }
}
