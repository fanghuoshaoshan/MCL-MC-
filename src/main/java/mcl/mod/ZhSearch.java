package mcl.mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文关键词 → 英文搜索词的映射。
 *
 * <p>Modrinth 的搜索索引里以英文为主，但也夹着大量国内作者上传的中文项目（汉化包、附属模组），
 * 所以直接拿中文去查并不是 0 结果，而是<b>被周边内容淹没</b>——搜「钠」出来的全是
 * 「钠-汉化包」「钠-设备检测」，正主 {@code sodium} 反而排在后面甚至不出现。
 *
 * <p>因此这里维护两份表：
 *
 * <ol>
 *   <li>{@link #TABLE} —— 译名表，把中文按「最长优先」切成英文关键词送去搜索，用于
 *       <b>扩召回</b>（搜「钠」顺带带出 sodium-extra、rubidium 这类同类）</li>
 *   <li>{@link #ANCHORS} —— 锚定表，直接给出该中文名对应的 <b>Modrinth 项目 slug</b>，
 *       用于<b>精确命中</b>：拿 slug 取到项目后钉在结果第一条，用户一眼就能看到正主</li>
 * </ol>
 *
 * <pre>
 *   钠            → sodium      （锚定 slug：sodium）
 *   机械动力      → create      （锚定 slug：create）
 *   小地图        → minimap     （锚定 slug：xaeros-minimap）
 *   钠 光影       → sodium shaders   ← 多词组合，不锚定
 * </pre>
 *
 * <p>用户混着写中英文也没问题（{@code create 机械动力} → 去重后只剩 {@code create}）。
 * 表里没有的词会被跳过，纯中文且一个词都认不出来时返回空列表，
 * 由调用方决定是退回原文搜索还是提示用户。
 */
public final class ZhSearch {

    private ZhSearch() {}

    /** 中文（含少量常见别名）→ 英文关键词。值为空串表示这是个虚词（如「模组」），直接丢掉。 */
    private static final String[][] TABLE = {
            // ---- 优化 / 性能 ----
            {"钠", "sodium"}, {"锂", "lithium"}, {"星光", "starlight"}, {"铷", "rubidium"},
            {"铍", "beryllium"}, {"钽", "tantalum"}, {"镁", "magnesium"}, {"氪", "krypton"},
            {"优化", "optimization"}, {"性能", "performance"}, {"帧数", "fps"}, {"帧率", "fps"},
            {"卡顿", "lag"}, {"流畅", "smooth"}, {"内存", "memory"}, {"内存泄漏", "memory leak"},
            {"渲染优化", "render optimization"}, {"游戏优化", "optimization"}, {"低配", "low end"},
            {"实体剔除", "entity culling"}, {"区块", "chunk"}, {"加速", "fps"},
            {"光影", "shaders"}, {"着色器", "shader"}, {"光影包", "shaderpack"},
            {"材质优化", "texture optimization"},
            // ---- 前置 / 依赖 ----
            {"前置", "api"}, {"依赖", "dependency"}, {"前置库", "library"}, {"库", "library"},
            // ---- 界面 / 客户端 ----
            {"界面", "ui"}, {"界面优化", "interface"}, {"界面美化", "ui"},
            {"小地图", "minimap"}, {"世界地图", "world map"}, {"旅行地图", "journeymap"},
            {"物品管理器", "jei"}, {"配方查看", "recipe"}, {"合成表", "crafting"},
            {"汉化", "chinese"}, {"中文", "chinese"}, {"中文字体", "font"}, {"字体", "font"},
            {"血量显示", "damage indicator"}, {"血量", "health"}, {"背包", "backpack"},
            {"物品栏", "inventory"}, {"装备", "equipment"}, {"提示框", "tooltip"},
            {"工具提示", "tooltip"}, {"快捷栏", "hotbar"}, {"缩放", "zoom"},
            {"视角", "perspective"}, {"第一人称", "first person"}, {"第三人称", "third person"},
            {"按钮", "button"}, {"菜单", "menu"}, {"标题界面", "title screen"},
            {"加载界面", "loading screen"}, {"全屏", "fullscreen"}, {"鼠标", "tweakeroo"},
            {"准星", "crosshair"}, {"聊天", "chat"}, {"计分板", "scoreboard"},
            {"屏幕", "screen"}, {"状态栏", "hud"}, {"抬头显示", "hud"},
            // ---- 科技 / 机械 ----
            {"机械动力", "create"}, {"通用机械", "mekanism"}, {"热力膨胀", "thermal"},
            {"工业时代", "ic2"}, {"工业", "industrial"}, {"沉浸工程", "immersive engineering"},
            {"应用能源", "applied energistics"}, {"精致存储", "refined storage"},
            {"存储抽屉", "storage drawers"}, {"管道", "pipe"}, {"线缆", "cable"},
            {"能源", "energy"}, {"电力", "electric"}, {"发电机", "generator"},
            {"反应堆", "reactor"}, {"核", "nuclear"}, {"自动化", "automation"},
            {"刷怪塔", "mob farm"}, {"农场", "farm"}, {"物流", "logistics"},
            {"传送带", "conveyor"}, {"机器人", "robot"}, {"无人机", "drone"},
            {"电脑", "computer"}, {"编程", "programming"}, {"红石", "redstone"},
            {"无线", "wireless"}, {"太阳能", "solar"}, {"风力", "wind"}, {"水力", "water"},
            {"机械", "machine"}, {"熔炉", "furnace"}, {"矿石", "ore"}, {"挖矿", "mining"},
            // ---- 魔法 ----
            {"神秘时代", "thaumcraft"}, {"植物魔法", "botania"}, {"血魔法", "blood magic"},
            {"星辉魔法", "astral sorcery"}, {"魔法", "magic"}, {"法术", "spell"},
            {"符文", "rune"}, {"祭坛", "altar"}, {"巫术", "witchery"},
            {"附魔", "enchantment"}, {"药水", "potion"}, {"炼金", "alchemy"},
            // ---- 冒险 / 战斗 ----
            {"匠魂", "tinkers"}, {"拔刀剑", "slashblade"}, {"武器", "weapon"},
            {"剑", "sword"}, {"弓", "bow"}, {"枪", "gun"}, {"护甲", "armor"},
            {"战斗", "combat"}, {"怪物", "mob"}, {"生物", "creature"}, {"动物", "animal"},
            {"龙", "dragon"}, {"宝可梦", "pixelmon"}, {"宠物", "pet"}, {"地牢", "dungeon"},
            {"冒险", "adventure"}, {"任务", "quest"}, {"成就", "advancement"},
            {"等级", "level"}, {"技能", "skill"}, {"天赋", "talent"}, {"职业", "class"},
            {"属性", "attribute"}, {"战利品", "loot"}, {"掉落", "drop"}, {"死亡", "death"},
            {"墓碑", "gravestone"}, {"Boss", "boss"},
            // ---- 农业 / 食物 ----
            {"农业", "farming"}, {"农夫乐事", "farmers delight"}, {"潘马斯", "pam"},
            {"料理", "cooking"}, {"食物", "food"}, {"作物", "crop"}, {"种子", "seed"},
            {"蜜蜂", "bee"}, {"鱼", "fish"}, {"钓鱼", "fishing"}, {"自动钓鱼", "autofish"},
            {"厨房", "kitchen"}, {"面包", "bread"}, {"酒", "wine"}, {"咖啡", "coffee"},
            // ---- 建筑 / 装饰 ----
            {"建筑", "build"}, {"装饰", "decoration"}, {"家具", "furniture"},
            {"灯具", "lamp"}, {"窗户", "window"}, {"门", "door"}, {"楼梯", "stairs"},
            {"屋顶", "roof"}, {"建筑手杖", "building gadgets"}, {"结构", "structure"},
            {"村庄", "village"}, {"城市", "city"}, {"城堡", "castle"},
            {"画", "painting"}, {"旗帜", "banner"}, {"方块", "block"},
            // ---- 世界生成 ----
            {"地形", "terrain"}, {"生物群系", "biome"}, {"洞穴", "cave"},
            {"维度", "dimension"}, {"下界", "nether"}, {"地狱", "nether"}, {"末地", "end"},
            {"天空", "sky"}, {"空岛", "skyblock"}, {"星球", "planet"}, {"太空", "space"},
            {"季节", "season"}, {"天气", "weather"}, {"温度", "temperature"},
            {"口渴", "thirst"}, {"物理", "physics"}, {"真实", "realistic"},
            // ---- 存储 / 实用 ----
            {"铁箱", "iron chest"}, {"箱子", "chest"}, {"桶", "barrel"},
            {"垃圾桶", "trash"}, {"存储", "storage"}, {"分类", "sorting"},
            {"传送石", "waystone"}, {"传送门", "portal"}, {"传送", "teleport"},
            {"坐标", "coordinate"}, {"时钟", "clock"}, {"日历", "calendar"},
            {"睡觉", "sleep"}, {"床", "bed"}, {"火把", "torch"}, {"照明", "light"},
            // ---- 项目类型 / 虚词 ----
            {"整合包", "modpack"}, {"资源包", "resource pack"}, {"材质包", "resource pack"},
            {"数据包", "datapack"}, {"光影包", "shaderpack"}, {"地图", "map"},
            {"模组", ""}, {"插件", ""}, {"游戏", ""}, {"我的世界", "minecraft"},
            {"的", ""}, {"和", ""}, {"与", ""},
    };

    private static final Map<String, String> DICT = new HashMap<String, String>();
    /** 按长度倒序，保证「机械动力」不会被拆成「机械」+「动力」。 */
    private static final List<String> KEYS = new ArrayList<String>();

    /**
     * 中文名 → Modrinth 项目 slug。只收「中文名唯一指向某个具体项目」的词，
     * 泛类词（光影、优化、魔法）不放——那种词该走 {@link #TABLE} 做模糊召回。
     *
     * <p>下面每一条都用 {@code GET /v2/projects?ids=[...]} 实际验证过存在，
     * 加了新行记得再验一遍 slug 是否仍指向同一个项目。
     */
    private static final String[][] ANCHORS = {
            // ---- 优化 ----
            {"钠", "sodium", "Sodium"},
            {"钠扩展", "sodium-extra", "Sodium Extra"},
            {"锂", "lithium", "Lithium"},
            {"星光", "starlight", "Starlight"},
            {"铷", "rubidium", "Rubidium"},
            // ---- 科技 / 机械 ----
            {"机械动力", "create", "Create"},
            {"通用机械", "mekanism", "Mekanism"},
            {"热力膨胀", "thermal-foundation", "Thermal Foundation"},
            {"工业时代", "industrial-craft", "Industrial Craft"},
            {"应用能源", "ae2", "Applied Energistics 2"},
            {"精致存储", "refined-storage", "Refined Storage"},
            {"管道", "pipez", "Pipez"},
            {"电脑", "computercraft", "ComputerCraft"},
            // ---- 魔法 ----
            {"植物魔法", "botania", "Botania"},
            {"血魔法", "blood-magic", "Blood Magic"},
            // ---- 战斗 / 冒险 ----
            {"匠魂", "tinkers-construct", "Tinkers' Construct"},
            {"更好的战斗", "better-combat", "Better Combat"},
            {"幸运方块", "lucky-block", "Lucky Block"},
            {"墓碑", "gravestone-mod", "GraveStone Mod"},
            {"起源", "origins", "Origins"},
            {"方块宝可梦", "cobblemon", "Cobblemon"},
            // ---- 农业 / 食物 ----
            {"农夫乐事", "farmers-delight", "Farmer's Delight"},
            // ---- 存储 / 实用 ----
            {"铁箱", "iron-chests", "Iron Chests"},
            {"苹果皮", "appleskin", "AppleSkin"},
            {"物品管理器", "jei", "Just Enough Items"},
            {"配方查看", "rei", "Roughly Enough Items"},
            {"传送石", "waystones", "Waystones"},
            // ---- 地图 ----
            {"小地图", "xaeros-minimap", "Xaero's Minimap"},
            {"世界地图", "xaeros-world-map", "Xaero's World Map"},
            {"旅行地图", "journeymap", "JourneyMap"},
            // ---- 社交 ----
            {"简单语音", "simple-voice-chat", "Simple Voice Chat"},
    };

    private static final Map<String, String[]> ANCHOR = new HashMap<String, String[]>();

    /** 一个锚定结果：中文名 + 项目 slug + 官方英文名。 */
    public static final class Anchor {
        public final String zh;
        public final String slug;
        public final String title;

        Anchor(String zh, String slug, String title) {
            this.zh = zh;
            this.slug = slug;
            this.title = title;
        }

        @Override
        public String toString() {
            return zh + " → " + slug;
        }
    }

    static {
        for (int i = 0; i < TABLE.length; i++) {
            DICT.put(TABLE[i][0], TABLE[i][1]);
        }
        for (int i = 0; i < ANCHORS.length; i++) {
            ANCHOR.put(ANCHORS[i][0], ANCHORS[i]);
        }
        KEYS.addAll(DICT.keySet());
        Collections.sort(KEYS, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                if (a.length() != b.length()) return b.length() - a.length();
                return a.compareTo(b);
            }
        });
    }

    /** 是否含有汉字（CJK 统一表意文字 + 扩展 A + 兼容区）。 */
    public static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (isCjk(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * 把查询串换成英文候选，按「命中率从高到低」排序。
     *
     * @return 不含汉字时返回 {@code null}（表示不需要翻译）；含汉字但一个词都认不出时返回空列表
     */
    public static List<String> candidates(String query) {
        String q = query == null ? "" : query.trim();
        if (!hasCjk(q)) return null;

        // 去掉空白后做最长优先切分
        StringBuilder joined = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        String bestTerm = "";
        int bestKeyLen = 0;
        int i = 0;
        while (i < q.length()) {
            String hit = null;
            for (int k = 0; k < KEYS.size(); k++) {
                String key = KEYS.get(k);
                if (key.length() > 0 && q.startsWith(key, i)) {
                    hit = key;
                    break;
                }
            }
            if (hit != null) {
                flush(joined, ascii);
                String en = DICT.get(hit);
                if (en != null && en.length() > 0) {
                    append(joined, en);
                    if (hit.length() > bestKeyLen) {
                        bestKeyLen = hit.length();
                        bestTerm = en;
                    }
                }
                i += hit.length();
                continue;
            }
            int cp = q.codePointAt(i);
            if (isAsciiWord(cp)) {
                ascii.appendCodePoint(cp);
            } else {
                flush(joined, ascii);
            }
            i += Character.charCount(cp);
        }
        flush(joined, ascii);

        List<String> out = new ArrayList<String>();
        String full = joined.toString().trim();
        if (full.length() > 0) out.add(full);
        // 多词时再补一个「最具体的那个词」，避免两个词组合起来命中不了任何项目
        if (full.indexOf(' ') > 0 && bestTerm.length() > 0 && !bestTerm.equals(full)) {
            out.add(bestTerm);
        }
        return out;
    }

    /** 给界面用的说明：返回实际会去搜的英文词，认不出时返回空串。 */
    public static String explain(String query) {
        List<String> c = candidates(query);
        if (c == null || c.isEmpty()) return "";
        return c.get(0);
    }

    /**
     * 按「最长优先」扫一遍查询，返回命中的译名表词条（去重，按出现顺序）。
     *
     * <p>{@code "钠 光影"} → {@code [钠, 光影]}；{@code "机械动力 模组"} → {@code [机械动力, 模组]}。
     */
    public static List<String> matchedKeys(String query) {
        String q = query == null ? "" : query.trim();
        List<String> out = new ArrayList<String>();
        if (!hasCjk(q)) return out;
        int i = 0;
        while (i < q.length()) {
            String hit = null;
            for (int k = 0; k < KEYS.size(); k++) {
                String key = KEYS.get(k);
                if (key.length() > 0 && q.startsWith(key, i)) {
                    hit = key;
                    break;
                }
            }
            if (hit != null) {
                if (!out.contains(hit)) out.add(hit);
                i += hit.length();
                continue;
            }
            i += Character.charCount(q.codePointAt(i));
        }
        return out;
    }

    /**
     * 查询里的「实义词」：命中的译名表词条中，映射到非空英文的那些（虚词如「模组」「的」被排除）。
     *
     * <p>搜「机械动力 模组」→ {@code [机械动力]}；搜「钠 光影」→ {@code [钠, 光影]}。
     */
    public static List<String> contentKeys(String query) {
        List<String> out = new ArrayList<String>();
        List<String> keys = matchedKeys(query);
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            String en = DICT.get(k);
            if (en != null && en.length() > 0) out.add(k);
        }
        return out;
    }

    /**
     * 中文查询的「精确锚定」：找出对应某个确定项目的中文名。
     *
     * <p>只在查询里<b>恰好有一个实义词</b>时才锚定。多词组合一律不锚定——搜「钠 光影」的
     * 用户要的是光影，把 sodium 钉在第一条会误导；搜「机械动力 模组」这种
     * 「一个正主 + 一个虚词」的则正常锚定。
     *
     * @return 没有唯一锚定词时返回 {@code null}
     */
    public static Anchor anchor(String query) {
        List<String> keys = contentKeys(query);
        if (keys.size() != 1) return null;
        String[] row = ANCHOR.get(keys.get(0));
        return row == null ? null : new Anchor(row[0], row[1], row[2]);
    }

    /** 锚定表里有多少条（自检与文档用）。 */
    public static int anchorCount() {
        return ANCHORS.length;
    }

    /**
     * 译名表对这次查询的覆盖率够不够高，值不值得拿翻译结果去搜。
     *
     * <p>查询越长、识别出的实义词占比越低，翻译就越不可靠。典型反例是「剑与王国」——
     * 表里只认得「剑」，翻成 {@code sword} 搜出来全是「Spells, Swords, And Dungeons」，
     * 与用户要找的内容毫无关系。这种情况不如直接用原文搜（搜不到就换源或换个说法）。
     *
     * <p>两三个汉字的短查询没有误拆空间，一律信任。
     */
    public static boolean trustTranslate(String query) {
        String q = query == null ? "" : query.trim();
        int total = countCjk(q);
        if (total <= 2) return true;
        int covered = 0;
        List<String> keys = contentKeys(q);
        for (int i = 0; i < keys.size(); i++) covered += countCjk(keys.get(i));
        return covered * 2 >= total; // 实义词覆盖过半才信
    }

    private static int countCjk(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (isCjk(cp)) n++;
            i += Character.charCount(cp);
        }
        return n;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF);
    }

    private static void flush(StringBuilder joined, StringBuilder ascii) {
        String token = ascii.toString();
        ascii.setLength(0);
        if (token.length() == 0) return;
        append(joined, token);
    }

    private static void append(StringBuilder joined, String token) {
        // 按空格拆开去重，避免「create 机械动力」变成「create create」
        String[] parts = token.split(" ");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() == 0) continue;
            if (containsWord(joined, p)) continue;
            if (joined.length() > 0) joined.append(' ');
            joined.append(p);
        }
    }

    private static boolean containsWord(StringBuilder sb, String word) {
        int from = 0;
        while (true) {
            int idx = sb.indexOf(word, from);
            if (idx < 0) return false;
            int end = idx + word.length();
            boolean leftOk = idx == 0 || sb.charAt(idx - 1) == ' ';
            boolean rightOk = end == sb.length() || sb.charAt(end) == ' ';
            if (leftOk && rightOk) return true;
            from = idx + 1;
        }
    }

    private static boolean isAsciiWord(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')
                || (cp >= '0' && cp <= '9') || cp == '_' || cp == '-';
    }
}
