package mcl.mod;

import mcl.util.Compat;
import mcl.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文名 → 项目 slug 的本地索引。
 *
 * <p><b>为什么要有这一层。</b>Modrinth 与 CurseForge 的搜索索引里中文内容既少又偏：
 * 搜「钠」返回的全是「钠-汉化包」这类周边，正主 {@code sodium} 排不进来。
 * 而 MC 社区站点把同一个项目的标题写成了中文（{@code 钠 - Sodium}），
 * 相当于一份现成的中文名对照表。这里把全站项目的标题抓下来、抠出中文名、
 * 再到 Modrinth 反查 slug 是否存在，生成一份随 jar 一起发布的索引，运行时零网络开销。
 *
 * <p>这份索引<b>对两个下载源通用</b>：里面的 slug 都是 Modrinth 上真实存在的，
 * 而同一个 slug 约一半在 CurseForge 上也同名存在，
 * 所以同一个中文查询既能命中 Modrinth 结果，也能通过 {@link ZhRecall} 桥接到 CurseForge。
 *
 * <p><b>只收能下载的条目。</b>索引早期还收过「Modrinth 上没有、只靠 MCBBS 下载」的项目，
 * MCBBS 源移除后那批就成了死数据（点了必然失败，还会把可下载的结果挤下去），
 * 已由 {@code tools/prune_zhindex.py} 整体剔除。
 *
 * <p><b>匹配分四档</b>，分数越高越靠前：
 * <pre>
 *   全等            100   钠 = 钠
 *   别名以查询开头   90-   机械动力 → 机械动力:齿轮盛宴
 *   查询以别名开头   85-   机械动力整合包 → 机械动力
 *   子串包含        70-   齿轮 → 机械动力:齿轮盛宴
 * </pre>
 * 单字别名（钠、锂）只在<b>全等</b>时才算命中，否则任何含这个字的查询都会命中它。
 *
 * <p>资源文件由 {@code tools/build_zhindex.py} 产出、{@code tools/prune_zhindex.py} 整理，不要手改。
 */
public final class ZhIndex {

    private ZhIndex() {}

    /** 索引里的一条记录。 */
    public static final class Entry {
        /** Modrinth 上真实存在的 slug，同时也是桥接到 CurseForge 的键。 */
        public final String slug;
        /** 项目类型，与 Modrinth 的 project_type 一致（mod / modpack / …）。 */
        public final String type;
        /** 来源站点的下载量，用作热度排序依据。 */
        public final long downloads;
        /** 这个项目的中文别名。 */
        public final String[] names;
        /**
         * 来源优先级：2 = 手写锚定表（人工核对过，最可信），
         * 1 = 从社区站标题挖出来并已验证存在于 Modrinth。
         */
        public final int src;

        Entry(String slug, String type, long downloads, String[] names, int src) {
            this.slug = slug;
            this.type = type;
            this.downloads = downloads;
            this.names = names;
            this.src = src;
        }

        @Override
        public String toString() {
            return slug + "(" + (names.length > 0 ? names[0] : "?") + ")";
        }
    }

    /** 一个匹配结果。 */
    public static final class Match {
        public final Entry entry;
        /** 命中的是哪个中文别名。 */
        public final String name;
        public final int score;

        Match(Entry entry, String name, int score) {
            this.entry = entry;
            this.name = name;
            this.score = score;
        }

        @Override
        public String toString() {
            return entry.slug + " ← " + name + "(" + score + ")";
        }
    }

    private static final Entry[] ENTRIES = loadIndex();
    private static final Map<String, List<String>> PINYIN = loadPinyin();

    /** 索引条目数（自检与诊断用）。 */
    public static int count() {
        return ENTRIES.length;
    }

    /** 拼音倒排索引里的键数量（自检与诊断用）。 */
    public static int pinyinKeys() {
        return PINYIN.size();
    }

    /**
     * 按中文查询找项目，返回按相关度（同分时按热度）降序的结果。
     *
     * @param query       用户输入的查询，会先去掉空格
     * @param projectType 期望的项目类型（{@link Modrinth#TYPE_MOD} 等）；空串表示不限
     * @param limit       最多返回多少条
     */
    public static List<Match> match(String query, String projectType, int limit) {
        List<Match> out = new ArrayList<Match>();
        if (Compat.isBlank(query)) return out;
        String q = query.trim().replaceAll("\\s+", "");
        for (int i = 0; i < ENTRIES.length; i++) {
            Entry e = ENTRIES[i];
            if (!typeOk(e.type, projectType)) continue;
            int best = 0;
            String bestName = "";
            for (int j = 0; j < e.names.length; j++) {
                int s = scoreOf(q, e.names[j]);
                if (s > best) {
                    best = s;
                    bestName = e.names[j];
                }
            }
            if (best > 0) out.add(new Match(e, bestName, best));
        }
        Collections.sort(out, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                // 手写锚定表 > 自动挖掘：同一个中文名可能对应一堆附属项目，
                // 只有手写表能确定「机械动力」的正主是 create 而不是某个 addon
                if (a.entry.src != b.entry.src) return b.entry.src - a.entry.src;
                if (a.score != b.score) return b.score - a.score;
                if (a.entry.downloads != b.entry.downloads) {
                    return a.entry.downloads > b.entry.downloads ? -1 : 1;
                }
                return a.entry.slug.compareTo(b.entry.slug);
            }
        });
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<Match>(out.subList(0, limit));
        }
        return out;
    }

    /**
     * 拼音查询：输入 {@code jxdl} / {@code jixiedongli} 命中「机械动力」。
     * 索引里同时存了全拼和首字母简拼，键已小写。
     */
    public static List<String> pinyinMatch(String key, int limit) {
        if (Compat.isBlank(key)) return new ArrayList<String>();
        List<String> v = PINYIN.get(key.trim().toLowerCase());
        if (v == null) return new ArrayList<String>();
        if (limit > 0 && v.size() > limit) {
            return new ArrayList<String>(v.subList(0, limit));
        }
        return new ArrayList<String>(v);
    }

    /** 索引里是否查得到这个词（用于提示来源，自检也用）。 */
    public static boolean has(String query) {
        return !match(query, "", 1).isEmpty();
    }

    // ---------------------------------------------------------------- 匹配

    /**
     * 给「查询 ↔ 别名」的相关度打分，0 表示不相关。
     *
     * <p>核心取舍是<b>宁可漏，不要脏</b>：索引里的别名是从标题上切下来的，
     * 混着「超级」「翻新版」这种碎片。如果宽松地做子串匹配，搜「超级无敌钠」
     * 会因为碎片「超级」而命中一个毫不相关的光影包 —— 用户看到的就是一堆噪声，
     * 比搜不到还难受。
     */
    private static int scoreOf(String q, String name) {
        if (q.length() == 0 || name.length() == 0) return 0;
        if (q.equals(name)) return 100;                    // 全等

        int ql = q.length(), nl = name.length();
        if (ql < 2 || nl < 2) return 0;                      // 单字只认全等

        // 别名以查询开头/结尾：机械 → 机械动力。差得越多分越低
        if (name.startsWith(q) || name.endsWith(q)) {
            return Math.max(35, 88 - Math.min(20, nl - ql));
        }
        // 查询以别名开头：机械动力整合包 → 机械动力。别名太短就不算，
        // 否则「超级」会命中「超级无敌钠」这种查询
        if (q.startsWith(name) || q.endsWith(name)) {
            if (nl * 2 < ql) return 0;
            return Math.max(35, 86 - Math.min(20, ql - nl));
        }
        // 中间子串：要求至少 3 字，2 字的中间片段指向性太弱
        if (ql >= 3 && name.indexOf(q) >= 0) return Math.max(30, 70 - (nl - ql) * 2);
        if (nl >= 3 && q.indexOf(name) >= 0) return Math.max(28, 66 - (ql - nl) * 2);
        return 0;
    }

    private static boolean typeOk(String entryType, String projectType) {
        if (Compat.isBlank(projectType)) return true;
        return projectType.equals(normType(entryType));
    }

    /** 老索引文件里类型可能写成单字母，统一归一到完整名。 */
    private static String normType(String t) {
        String v = (t == null ? "" : t.trim().toLowerCase());
        if (v.length() == 0) return "mod";
        if (v.equals("m") || v.equals("mod")) return "mod";
        if (v.equals("p") || v.equals("modpack")) return "modpack";
        if (v.equals("r") || v.equals("resourcepack")) return "resourcepack";
        if (v.equals("s") || v.equals("shader")) return "shader";
        if (v.equals("d") || v.equals("datapack")) return "datapack";
        return "mod";
    }

    // ---------------------------------------------------------------- 加载

    private static Entry[] loadIndex() {
        List<Entry> list = new ArrayList<Entry>();
        try {
            read("/mcl/zhindex.txt", new LineHandler() {
                @Override
                public void handle(String line) {
                    // src \t slug \t type \t downloads \t name,name
                    String[] p = line.split("\t", -1);
                    if (p.length < 5) return;
                    long dl = 0;
                    try {
                        dl = Long.parseLong(p[3].trim());
                    } catch (NumberFormatException ignored) {
                        // 热度只用于排序，解析失败按 0 处理
                    }
                    int src = 0;
                    try {
                        src = Integer.parseInt(p[0].trim());
                    } catch (NumberFormatException ignored) {
                        // 认不出的来源按最低优先级处理
                    }
                    // src=0 是历史上「只靠 MCBBS 下载」的条目。文件里已经由
                    // tools/prune_zhindex.py 剔除，这里再兜一层，免得旧文件混进来
                    // 又变成点了下不动的结果。
                    if (src <= 0) return;
                    list.add(new Entry(p[1].trim(), normType(p[2]), dl,
                            p[4].split(","), src));
                }
            });
        } catch (IOException e) {
            Log.warn("中文索引加载失败，中文搜索将退化为译名表：" + e.getMessage());
        }
        return list.toArray(new Entry[0]);
    }

    private static Map<String, List<String>> loadPinyin() {
        final Map<String, List<String>> map = new HashMap<String, List<String>>();
        try {
            read("/mcl/zhpy.txt", new LineHandler() {
                @Override
                public void handle(String line) {
                    String[] p = line.split("\t", -1);
                    if (p.length < 2) return;
                    map.put(p[0].trim(), Arrays.asList(p[1].split(",")));
                }
            });
        } catch (IOException e) {
            Log.warn("拼音索引加载失败：" + e.getMessage());
        }
        return map;
    }

    private interface LineHandler {
        void handle(String line);
    }

    private static void read(String resource, LineHandler h) throws IOException {
        InputStream in = ZhIndex.class.getResourceAsStream(resource);
        if (in == null) throw new IOException("资源不存在：" + resource);
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                h.handle(line);
            }
        } finally {
            if (r != null) r.close();
            else in.close();
        }
    }
}
