package mcl.mod;

/**
 * 内容源：搜索「模组 / 整合包 / 资源包 / 光影」的两个站台。
 *
 * <ul>
 *   <li>{@link #MODRINTH} —— api.modrinth.com。走 Labrinth 协议，不需要鉴权，<b>但只索引英文</b>，
 *       搜「钠」出来的是汉化包</li>
 *   <li>{@link #CURSEFORGE} —— api.curseforge.com。<b>必须带 x-api-key</b>（见
 *       {@link CurseForge#key()}），协议与 Labrinth 完全不同，所以由 {@link CurseForge} 单独实现。
 *       优势是老牌 Forge 模组的覆盖远好于 Modrinth，很多经典模组在 CF 上独家</li>
 * </ul>
 *
 * <p>两个站的数据结构由 {@link ModApi} 统一翻译成本项目的
 * {@link Modrinth.Hit} / {@link Modrinth.VersionInfo}，上层不需要知道数据来自哪边。
 */
public final class ModSource {

    public static final ModSource MODRINTH = new ModSource(
            "modrinth", "Modrinth", "https://api.modrinth.com/v2", "https://modrinth.com");

    public static final ModSource CURSEFORGE = new ModSource(
            "curseforge", "CurseForge", CurseForge.API, "https://www.curseforge.com");

    /** 命令 / 配置里用的短 id。 */
    public final String id;
    /** 界面上显示的名字。 */
    public final String label;
    /** Labrinth 协议的 API 根地址（CurseForge 不用这个）。 */
    public final String base;
    /** 网站首页（拼项目页链接用）。 */
    public final String site;

    private ModSource(String id, String label, String base, String site) {
        this.id = id;
        this.label = label;
        this.base = base;
        this.site = site;
    }

    public static ModSource[] all() {
        return new ModSource[]{MODRINTH, CURSEFORGE};
    }

    /**
     * 能在界面上列出的源。CurseForge 没有可用 API Key 时不出现，
     * 而不是塞进去让用户点了才报错。
     */
    public static ModSource[] available() {
        return CurseForge.available()
                ? new ModSource[]{MODRINTH, CURSEFORGE}
                : new ModSource[]{MODRINTH};
    }

    /** 按 id 找源，认不出就退回 Modrinth。 */
    public static ModSource byId(String id) {
        if (id != null) {
            for (ModSource s : all()) {
                if (s.id.equalsIgnoreCase(id) || s.label.equalsIgnoreCase(id)) return s;
            }
        }
        return MODRINTH;
    }

    public boolean isModrinth() {
        return this == MODRINTH;
    }

    public boolean isCurseForge() {
        return this == CURSEFORGE;
    }

    /** 这个源现在能不能真的发请求（CurseForge 缺 key 时为 false）。 */
    public boolean metaAvailable() {
        return isModrinth() || CurseForge.available();
    }

    /** 项目页地址（提示用户手动下载时用）。 */
    public String projectUrl(String slug, String projectType) {
        if (isCurseForge()) {
            return "https://www.curseforge.com/minecraft/" + cfPathSeg(projectType) + "/" + slug;
        }
        String kind = "mod";
        if (Modrinth.TYPE_MODPACK.equals(projectType)) kind = "modpack";
        else if (Modrinth.TYPE_SHADER.equals(projectType)) kind = "shader";
        else if (Modrinth.TYPE_RESOURCEPACK.equals(projectType)) kind = "resourcepack";
        return site + "/" + kind + "/" + slug;
    }

    private static String cfPathSeg(String projectType) {
        if (Modrinth.TYPE_MODPACK.equals(projectType)) return "modpacks";
        if (Modrinth.TYPE_RESOURCEPACK.equals(projectType)) return "texture-packs";
        if (Modrinth.TYPE_SHADER.equals(projectType)) return "customization";
        return "mc-mods";
    }

    public String typeLabel(String projectType) {
        return Modrinth.typeLabel(projectType);
    }
}
