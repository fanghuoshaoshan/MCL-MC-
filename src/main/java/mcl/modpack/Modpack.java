package mcl.modpack;

import mcl.util.Dirs;
import mcl.util.Compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.game.Game;
import mcl.game.Isolation;
import mcl.game.VersionManager;
import mcl.game.VersionProfiles;
import mcl.install.Installer;
import mcl.net.Downloader;
import mcl.net.Http;
import mcl.util.Json;
import mcl.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 整合包：识别 / 安装 / 导出。
 *
 * <p>支持三种格式：
 * <ul>
 *   <li><b>Modrinth</b>（.mrpack）：modrinth.index.json + overrides</li>
 *   <li><b>CurseForge</b>（.zip）：manifest.json + overrides（HMCL 导出的也是这个格式）</li>
 *   <li><b>裸压缩包</b>：没有清单，把 mods/config 等直接解压到实例目录</li>
 * </ul>
 *
 * <p>安装后会在 versions/&lt;实例名&gt; 下建立独立实例（含 inheritsFrom 的版本 JSON），
 * 并写入 .mcl-isolate 标记，因此模组、配置、存档天然隔离，与 HMCL 的整合包实例一致。
 */
public final class Modpack {

    public enum Format {MRPACK, CURSEFORGE, PLAIN}

    /** 整合包信息。 */
    public static final class Info {
        public Format format = Format.PLAIN;
        public String name = "整合包";
        public String version = "1.0";
        public String author = "";
        public String summary = "";
        public String mcVersion = "";
        public String loaderKind = "vanilla";   // vanilla / fabric / quilt / forge / neoforge
        public String loaderVersion = "";
        public int fileCount;

        public String loaderLabel() {
            String lv = Compat.isBlank(loaderVersion) ? "" : " " + loaderVersion;
            if ("fabric".equals(loaderKind)) return "Fabric" + lv;
            if ("quilt".equals(loaderKind)) return "Quilt" + lv;
            if ("forge".equals(loaderKind)) return "Forge" + lv;
            if ("neoforge".equals(loaderKind)) return "NeoForge" + lv;
            return "原版";
        }

        public String describe() {
            return name + " " + version + "  ·  " + mcVersion + "  ·  " + loaderLabel()
                    + (fileCount > 0 ? "  ·  " + fileCount + " 个文件" : "");
        }
    }

    /** 需要外部下载的一个文件。 */
    private static final class PackFile {
        String path;
        String url;
        String sha1;
        long size;
        String projectId;
        String fileId;
        int classId;
    }

    private Modpack() {}

    // ---------------- 识别 ----------------

    public static Info inspect(Path zip) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            if (zf.getEntry("modrinth.index.json") != null) {
                return parseMrpack(zf);
            }
            ZipEntry manifest = zf.getEntry("manifest.json");
            if (manifest != null) {
                return parseCurseForge(zf, manifest);
            }
            Info info = new Info();
            info.format = Format.PLAIN;
            info.name = stripExt(zip.getFileName().toString());
            info.mcVersion = "";
            info.loaderKind = "vanilla";
            return info;
        }
    }

    private static Info parseMrpack(ZipFile zf) throws IOException {
        JsonObject root = JsonParser.parseString(readText(zf, "modrinth.index.json")).getAsJsonObject();
        Info info = new Info();
        info.format = Format.MRPACK;
        info.name = Json.str(root, "name", "整合包");
        info.version = Json.str(root, "versionId", "1.0");
        info.summary = Json.str(root, "summary", "");
        JsonObject deps = Json.obj(root, "dependencies");
        info.mcVersion = Json.str(deps, "minecraft", "");
        if (deps != null) {
            for (String key : deps.keySet()) {
                String kind = compatibleLoader(key);
                if (kind != null) {
                    info.loaderKind = kind;
                    info.loaderVersion = deps.get(key).getAsString();
                }
            }
        }
        JsonArray files = Json.arr(root, "files");
        info.fileCount = files == null ? 0 : files.size();
        return info;
    }

    /** modrinth.index.json 的 dependencies 键 -> 加载器种类；不认识的返回 null。 */
    private static String compatibleLoader(String key) {
        if ("fabric-loader".equals(key)) return "fabric";
        if ("quilt-loader".equals(key)) return "quilt";
        if ("forge".equals(key)) return "forge";
        if ("neoforge".equals(key)) return "neoforge";
        return null;
    }

    private static Info parseCurseForge(ZipFile zf, ZipEntry manifest) throws IOException {
        JsonObject root = JsonParser.parseString(readText(zf, manifest.getName())).getAsJsonObject();
        Info info = new Info();
        info.format = Format.CURSEFORGE;
        info.name = Json.str(root, "name", "整合包");
        info.version = Json.str(root, "version", "1.0");
        info.author = Json.str(root, "author", "");
        JsonObject mc = Json.obj(root, "minecraft");
        info.mcVersion = Json.str(mc, "version", "");
        JsonArray loaders = Json.arr(mc, "modLoaders");
        if (loaders != null && loaders.size() > 0 && loaders.get(0).isJsonObject()) {
            String id = Json.str(loaders.get(0).getAsJsonObject(), "id", "");
            parseLoaderId(info, id);
        }
        JsonArray files = Json.arr(root, "files");
        info.fileCount = files == null ? 0 : files.size();
        return info;
    }

    private static void parseLoaderId(Info info, String id) {
        if (id == null || Compat.isBlank(id)) return;
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("forge-")) {
            info.loaderKind = "forge";
            info.loaderVersion = id.substring("forge-".length());
        } else if (lower.startsWith("neoforge-")) {
            info.loaderKind = "neoforge";
            info.loaderVersion = id.substring("neoforge-".length());
        } else if (lower.startsWith("fabric-")) {
            info.loaderKind = "fabric";
            info.loaderVersion = id.substring("fabric-".length());
        } else if (lower.startsWith("quilt-")) {
            info.loaderKind = "quilt";
            info.loaderVersion = id.substring("quilt-".length());
        }
    }

    // ---------------- 安装 ----------------

    /**
     * 安装整合包，返回新建实例的版本 id。
     *
     * @param overrideMc    用户手动指定的游戏版本（裸压缩包需要）
     * @param overrideKind  用户手动指定的加载器类型
     * @param overrideVer   用户手动指定的加载器版本（可为空表示取最新）
     */
    public static String install(Game game, Path zip, Info info, String overrideMc, String overrideKind,
                                 String overrideVer, Installer.Listener listener) throws Exception {
        if (info == null) info = inspect(zip);
        String mc = !Compat.isBlank(overrideMc) ? overrideMc : info.mcVersion;
        String kind = overrideKind != null && !Compat.isBlank(overrideKind) ? overrideKind : info.loaderKind;
        String loaderVer = overrideVer != null && !Compat.isBlank(overrideVer) ? overrideVer : info.loaderVersion;
        if (mc == null || Compat.isBlank(mc)) {
            throw new IOException("无法确定整合包对应的 Minecraft 版本，请手动选择");
        }
        listener.onStage("整合包需要 Minecraft " + mc + " / " + kind);

        // 1. 记录安装前的版本列表，稍后用于定位新建实例 / 判断加载器是不是这次才装的
        java.util.Set<String> before = versionIdSet(game);
        String parentId = game.installLoaderChain(mc, kind, loaderVer, listener);

        // 2. 建立实例目录
        String instanceId = uniqueInstanceId(game, info.name);
        Path instance = game.gameDir().resolve("versions").resolve(instanceId);
        Dirs.ensure(instance);
        boolean hasLoader = !Compat.isBlank(kind) && !"vanilla".equalsIgnoreCase(kind);
        // 用户没指定加载器版本时 installLoaderChain 会自动取最新，这里以实际装成的 id 为准
        VersionManager.Info parentInfo = VersionManager.idInfo(parentId);
        String iKind = hasLoader
                ? (Compat.isBlank(parentInfo.loaderKind) ? kind : parentInfo.loaderKind) : "";
        String iVer = hasLoader
                ? (Compat.isBlank(parentInfo.loaderVersion) ? loaderVer : parentInfo.loaderVersion) : "";
        writeInstanceJson(instance, instanceId, parentId, iKind, mc, iVer);
        try {
            Compat.writeString(instance.resolve(".mcl-isolate"), "modpack");
        } catch (IOException ignored) {
        }

        // 2.5 把加载器收进实例自己的目录：整合包应该是自包含的，
        //     而不是在 versions/ 里再挂一个共享的 1.20.1-forge-47.4.20
        if (hasLoader) {
            absorbLoader(game.gameDir(), instanceId, parentId, !before.contains(parentId));
        }

        // 3. 按格式下载内容
        if (info.format == Format.MRPACK) {
            installMrpack(game, zip, instance, mc, listener);
        } else if (info.format == Format.CURSEFORGE) {
            installCurseForge(game, zip, instance, mc, listener);
        } else {
            // 裸压缩包：直接把全部内容解压到实例目录（mods/ config/ 等结构原样保留）
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                extractOverrides(zf, instance, null, listener);
            }
        }
        listener.onStage("整合包安装完成");
        return instanceId;
    }

    // ---------------- 加载器收纳 ----------------

    private static java.util.Set<String> versionIdSet(Game game) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (VersionManager.InstalledVersion v : game.versions().list()) ids.add(v.id);
        return ids;
    }

    /**
     * 把加载器版本收进整合包实例目录，让整合包自包含。
     *
     * <p>以前整合包的 {@code versions/BMC4} 只是一个空壳，真正的加载器
     * {@code versions/1.20.1-forge-47.4.20} 摆在共享目录里：用户打开整合包文件夹看不到
     * Forge，删掉 / 拷走整合包时它也带不走，而且版本列表里还多出一个看不懂的条目。
     *
     * <p>这里把加载器的版本 JSON 复制到 {@code versions/<实例>/loader/<加载器 id>/}。
     * 只搬 JSON（几百 KB）；依赖库仍然共用 {@code libraries/}，否则每个整合包都要多占几百 MB。
     *
     * @param createdNow 该加载器版本是不是本次安装才新建的；只有新建的才允许清掉外面那份
     * @return 是否真的搬进去了
     */
    public static boolean absorbLoader(Path gameDir, String instanceId, String loaderId, boolean createdNow) {
        if (Compat.isBlank(instanceId) || Compat.isBlank(loaderId)) return false;
        VersionManager.Info li = VersionManager.idInfo(loaderId);
        if ("vanilla".equals(li.loaderKind)) return false;
        Path versions = gameDir.resolve("versions");
        Path shared = versions.resolve(loaderId);
        Path sharedJson = shared.resolve(loaderId + ".json");
        Path local = versions.resolve(instanceId).resolve(VersionManager.LOCAL_LOADER).resolve(loaderId);
        Path localJson = local.resolve(loaderId + ".json");
        if (!Files.isRegularFile(sharedJson)) return Files.isRegularFile(localJson);   // 已经在实例里了
        // 游戏版本以加载器 JSON 的 inheritsFrom 为准（id 被改过名也不会认错）
        String mc = mcl.util.Json.str(parseOrNull(sharedJson), "inheritsFrom", "");
        if (Compat.isBlank(mc)) mc = li.mcVersion;
        try {
            // 关键：写进去的是一份**完整**配置（原版 + 加载器合并结果），不再写
            // `inheritsFrom: 1.20.1` 那种薄壳。这样整合包不依赖外面那个原版，
            // 外面那份原版哪天被装了别的加载器 / 被删掉，整合包都照跑。
            String base = VersionProfiles.vanillaJson(gameDir, mc);
            String merged = VersionProfiles.merge(base, Compat.readString(sharedJson), loaderId, mc);
            Dirs.ensure(local);
            Compat.writeString(localJson, merged);
            Log.info("加载器 " + loaderId + " 已私有化到 " + instanceId + "/" + VersionManager.LOCAL_LOADER + "/");
        } catch (IOException e) {
            Log.warn("把加载器 " + loaderId + " 私有化进整合包失败：" + e.getMessage());
            // 退一步：至少抄一份 JSON，别让整合包直接起不来
            try {
                Dirs.ensure(local);
                Files.copy(sharedJson, localJson, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                Log.warn("连 JSON 都没抄成：" + e2.getMessage());
                return false;
            }
        }
        // 每个整合包都自带完整一份了，外面那份就没有存在意义 —— 版本列表里也不该再出现。
        // createdNow：这次才装出来的临时版本，里面就算有东西也是隔离初始化接管来的，
        // 直接清；老整合包那份可能是用户自己装的，只有确认是空壳才动。
        if (!referencedByOthers(gameDir, instanceId, loaderId)
                && (createdNow || isBareLoaderDir(shared, loaderId))) {
            Path keeper = Compat.isBlank(mc) ? null : versions.resolve(mc);
            try {
                VersionProfiles.discard(gameDir, keeper != null && Files.isDirectory(keeper)
                        ? keeper : null, loaderId);
                Log.info("已清理共享目录里多余的加载器版本 " + loaderId);
            } catch (IOException e) {
                Log.warn("清理共享加载器版本失败（不影响使用）：" + e.getMessage());
            }
        }
        return true;
    }

    /**
     * 整理已存在的整合包实例：把还摆在共享目录里的加载器收进实例目录。
     *
     * <p>只做「补一份到实例目录」，不删共享那份 —— 老用户的加载器版本可能是自己装的，
     * 删错了就是真丢东西。新导入的整合包走 {@link #absorbLoader} 的 createdNow 分支才会清理。
     *
     * @return 整理过的实例 id 列表
     */
    public static List<String> tidyInstances(Path gameDir) {
        List<String> tidied = new ArrayList<>();
        Path versions = gameDir.resolve("versions");
        if (!Files.isDirectory(versions)) return tidied;
        VersionManager vm = new VersionManager(gameDir);
        // 两轮走：先把所有整合包需要的加载器都复制进各自目录，再统一清理孤儿。
        // 一轮走会在「两个整合包共用同一个加载器」时翻车 —— 处理第一个时看到
        // 只有整合包引用它，就把共享副本删了，第二个还没来得及复制。
        List<String> parents = new ArrayList<>();
        for (VersionManager.InstalledVersion v : vm.list()) {
            Path marker = v.dir.resolve(Isolation.FORCED_MARKER);
            if (!Files.isRegularFile(marker)) continue;      // 只处理整合包实例
            String parent;
            try {
                parent = mcl.util.Json.str(com.google.gson.JsonParser
                        .parseString(Compat.readString(v.json)).getAsJsonObject(), "inheritsFrom", null);
            } catch (Exception e) {
                continue;
            }
            if (Compat.isBlank(parent)) continue;
            if ("vanilla".equals(VersionManager.idInfo(parent).loaderKind)) continue;
            Path localJson = v.dir.resolve(VersionManager.LOCAL_LOADER)
                    .resolve(parent).resolve(parent + ".json");
            if (Files.isRegularFile(localJson)
                    && Compat.isBlank(mcl.util.Json.str(parseOrNull(localJson), "inheritsFrom", ""))) {
                continue;                                    // 已经是自带完整配置的那份了
            }
            boolean sharedAlive = Files.isRegularFile(
                    versions.resolve(parent).resolve(parent + ".json"));
            if (sharedAlive) {
                if (absorbLoader(gameDir, v.id, parent, false)) {
                    tidied.add(v.id);
                    tagInstance(gameDir, v.id, parent);
                    if (!parents.contains(parent)) parents.add(parent);
                }
            } else if (Files.isRegularFile(localJson)) {
                // 外面那份早没了，实例里只剩个 `inheritsFrom: 1.20.1` 的薄壳。
                // 薄壳依赖外面的原版 —— 那个原版一旦被装了别的加载器，整合包就会叠两层。
                // 趁现在把它补成完整配置。
                String mc = vm.describe(v.id).mcVersion;
                if (materializeLocal(gameDir, v.id, parent, mc)) tidied.add(v.id);
            }
        }
        for (String parent : parents) dropOrphanLoader(gameDir, parent);
        return tidied;
    }

    /**
     * 把实例里那份「薄壳」加载器配置补成完整的。
     *
     * <p>薄壳只有 {@code mainClass} / {@code libraries} 加一句 {@code inheritsFrom}，
     * 靠外面的原版版本提供剩下的东西。一旦使用者给那个原版装了别的加载器，
     * 薄壳就会把两层加载器叠在一起。补成完整配置之后就与外界无关了。
     *
     * @return 补成功返回 true
     */
    static boolean materializeLocal(Path gameDir, String instanceId, String loaderId, String mcVersion) {
        Path localJson = gameDir.resolve("versions").resolve(instanceId)
                .resolve(VersionManager.LOCAL_LOADER).resolve(loaderId).resolve(loaderId + ".json");
        if (!Files.isRegularFile(localJson)) return false;
        if (Compat.isBlank(mcl.util.Json.str(parseOrNull(localJson), "inheritsFrom", ""))) return false;
        String mc = Compat.isBlank(mcVersion)
                ? mcl.util.Json.str(parseOrNull(localJson), "inheritsFrom", "")
                : mcVersion;
        if (Compat.isBlank(mc)) return false;
        try {
            String base = VersionProfiles.vanillaJson(gameDir, mc);
            String merged = VersionProfiles.merge(base, Compat.readString(localJson), loaderId, mc);
            Compat.writeString(localJson, merged);
            Log.info("整合包 " + instanceId + " 的加载器配置已补成完整版（不再依赖外面的 " + mc + "）");
            return true;
        } catch (Exception e) {
            Log.warn("补全整合包 " + instanceId + " 的加载器配置失败：" + e.getMessage());
            return false;
        }
    }

    /**
     * 给老整合包补上 {@code mcl} 元信息（游戏版本 / 加载器）。
     *
     * <p>老版本只写了 {@code name}，加载器全靠 {@code inheritsFrom} 一路往上猜。
     * 加载器被收进实例目录后这条链仍然成立，但补上元信息更稳：以后即使加载器版本
     * 被别人清理掉，版本列表里依然能显示「Forge 47.4.20」而不是「原版」。
     */
    private static void tagInstance(Path gameDir, String instanceId, String loaderId) {
        Path json = gameDir.resolve("versions").resolve(instanceId).resolve(instanceId + ".json");
        if (!Files.isRegularFile(json)) return;
        VersionManager.Info li = VersionManager.idInfo(loaderId);
        try {
            com.google.gson.JsonObject o = JsonParser.parseString(Compat.readString(json)).getAsJsonObject();
            com.google.gson.JsonObject tag = mcl.util.Json.obj(o, "mcl");
            if (tag == null) tag = new com.google.gson.JsonObject();
            boolean changed = false;
            if (Compat.isBlank(mcl.util.Json.str(tag, "loader", "")) && !Compat.isBlank(li.loaderKind)) {
                tag.addProperty("loader", li.loaderKind);
                changed = true;
            }
            if (Compat.isBlank(mcl.util.Json.str(tag, "loaderVersion", "")) && !Compat.isBlank(li.loaderVersion)) {
                tag.addProperty("loaderVersion", li.loaderVersion);
                changed = true;
            }
            if (Compat.isBlank(mcl.util.Json.str(tag, "mcVersion", "")) && !Compat.isBlank(li.mcVersion)) {
                tag.addProperty("mcVersion", li.mcVersion);
                changed = true;
            }
            if (!changed) return;
            o.add("mcl", tag);
            Compat.writeString(json, new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .disableHtmlEscaping().create().toJson(o));
        } catch (Exception e) {
            Log.warn("补写整合包元信息失败：" + e.getMessage());
        }
    }

    /**
     * 清掉「只为整合包服务」的加载器版本：它留在 {@code versions/} 里只是给用户添一个
     * 看不懂的条目 —— 整合包自己已经带着一份了。
     *
     * <p>两个前提缺一不可：至少有一个版本引用它、引用它的**全部**是整合包实例。
     * 目录里剩下的存档 / 模组不会跟着丢 —— 先搬进原版目录再删。
     */
    private static void dropOrphanLoader(Path gameDir, String loaderId) {
        Path dir = gameDir.resolve("versions").resolve(loaderId);
        if (!Files.isRegularFile(dir.resolve(loaderId + ".json"))) return;
        VersionManager vm = new VersionManager(gameDir);
        boolean anyReferenced = false;
        for (VersionManager.InstalledVersion v : vm.list()) {
            if (v.id.equals(loaderId)) continue;
            if (!refersTo(vm, v.id, loaderId)) continue;
            anyReferenced = true;
            // 有非整合包版本（用户自己装的加载器版本）在用 → 保留
            if (!Files.isRegularFile(v.dir.resolve(Isolation.FORCED_MARKER))) return;
        }
        if (!anyReferenced) return;
        String mc = VersionManager.idInfo(loaderId).mcVersion;
        Path keeper = Compat.isBlank(mc) ? null : gameDir.resolve("versions").resolve(mc);
        try {
            VersionProfiles.discard(gameDir, keeper != null && Files.isDirectory(keeper) ? keeper : null, loaderId);
            Log.info("已清理只为整合包服务的加载器版本 " + loaderId);
        } catch (IOException e) {
            Log.warn("清理加载器版本失败（不影响使用）：" + e.getMessage());
        }
    }

    /** 版本 id 的 inheritsFrom 链上有没有 target（只看全局目录，实例内私有副本不算）。 */
    private static boolean refersTo(VersionManager vm, String id, String target) {
        Path versions = vm.versionsDir();
        String cur = id;
        for (int depth = 0; depth < 8 && cur != null; depth++) {
            Path json = versions.resolve(cur).resolve(cur + ".json");
            if (!Files.isRegularFile(json)) return false;
            String parent = mcl.util.Json.str(parseOrNull(json), "inheritsFrom", null);
            if (parent == null) return false;
            if (target.equals(parent)) return true;
            cur = parent;
        }
        return false;
    }

    /**
     * 给整合包换一个加载器：改 {@code inheritsFrom}、把新加载器收进实例目录、清掉旧的副本。
     *
     * <p>「更换模组加载器」以前只会往 {@code versions/} 里再装一个版本，整合包本身纹丝不动，
     * 用户以为换成功了，进游戏还是老 Forge。这里把实例真正指到新加载器上。
     *
     * @return 换成功返回 true
     */
    public static boolean retargetInstance(Path gameDir, String instanceId, String newLoaderId) {
        if (Compat.isBlank(instanceId) || Compat.isBlank(newLoaderId)) return false;
        Path versions = gameDir.resolve("versions");
        Path instance = versions.resolve(instanceId);
        Path json = instance.resolve(instanceId + ".json");
        if (!Files.isRegularFile(json)) return false;
        if (!absorbLoader(gameDir, instanceId, newLoaderId, false)) return false;
        try {
            com.google.gson.JsonObject o = JsonParser.parseString(Compat.readString(json)).getAsJsonObject();
            String old = mcl.util.Json.str(o, "inheritsFrom", "");
            o.addProperty("inheritsFrom", newLoaderId);
            VersionManager.Info li = VersionManager.idInfo(newLoaderId);
            com.google.gson.JsonObject tag = mcl.util.Json.obj(o, "mcl");
            if (tag == null) tag = new com.google.gson.JsonObject();
            if (!Compat.isBlank(li.loaderKind)) tag.addProperty("loader", li.loaderKind);
            if (!Compat.isBlank(li.loaderVersion)) tag.addProperty("loaderVersion", li.loaderVersion);
            if (!Compat.isBlank(li.mcVersion)) tag.addProperty("mcVersion", li.mcVersion);
            o.add("mcl", tag);
            Compat.writeString(json, new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .disableHtmlEscaping().create().toJson(o));
            // 旧加载器的副本已经没有版本引用了，留着只是占地方
            if (!Compat.isBlank(old) && !old.equals(newLoaderId)) {
                deleteTree(instance.resolve(VersionManager.LOCAL_LOADER).resolve(old));
            }
            Log.info("整合包 " + instanceId + " 的加载器改为 " + newLoaderId);
            return true;
        } catch (Exception e) {
            Log.warn("切换整合包加载器失败：" + e.getMessage());
            return false;
        }
    }

    /** 除 keepInstance 外，还有没有别的已装版本（顺着 inheritsFrom）压在这个**共享**加载器上。 */
    private static boolean referencedByOthers(Path gameDir, String keepInstance, String loaderId) {
        VersionManager vm = new VersionManager(gameDir);
        Path versions = gameDir.resolve("versions");
        for (VersionManager.InstalledVersion v : vm.list()) {
            if (v.id.equals(keepInstance) || v.id.equals(loaderId)) continue;
            // 自己带了私有副本的，跟共享那份没关系
            if (Files.isRegularFile(versions.resolve(v.id).resolve(VersionManager.LOCAL_LOADER)
                    .resolve(loaderId).resolve(loaderId + ".json"))) {
                continue;
            }
            String cur = v.id;
            for (int depth = 0; depth < 8 && cur != null; depth++) {
                // 只看全局目录：实例内的私有副本不算「引用共享那份」
                Path json = versions.resolve(cur).resolve(cur + ".json");
                if (!Files.isRegularFile(json)) break;
                String parent = mcl.util.Json.str(parseOrNull(json), "inheritsFrom", null);
                if (parent == null) break;
                if (loaderId.equals(parent)) return true;
                cur = parent;
            }
        }
        return false;
    }

    private static com.google.gson.JsonObject parseOrNull(Path json) {
        try {
            return JsonParser.parseString(Compat.readString(json)).getAsJsonObject();
        } catch (Exception e) {
            return new com.google.gson.JsonObject();
        }
    }

    /**
     * 加载器版本的目录是不是「空壳」—— 只有一份 JSON 和自动生成的杂物。
     *
     * <p>只有这种目录才能安全删掉：里面有 mods / saves / resourcepacks 说明用户用过它，
     * 删了就是删用户的东西。
     *
     * <p>{@code options.txt} 一类算杂物：装加载器时 {@code prepareVersionDir} 会顺手从共用目录
     * 接管一份过来，共用目录里原件还在，删掉不会丢东西。
     */
    private static boolean isBareLoaderDir(Path dir, String loaderId) {
        if (!Files.isDirectory(dir)) return false;
        final boolean[] bare = {true};
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.forEach(p -> {
                if (!bare[0] || Files.isDirectory(p)) return;
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (rel.equals(loaderId + ".json")) return;
                if (rel.indexOf('/') < 0) {
                    // 版本目录根上的自动生成物
                    String lower = rel.toLowerCase(Locale.ROOT);
                    if (rel.startsWith(".") || lower.endsWith(".log")) return;
                    if (lower.equals("options.txt") || lower.equals("optionsof.txt")) return;
                    if (lower.equals("servers.dat") || lower.equals("servers.dat_old")) return;
                    if (lower.equals("usercache.json")) return;
                }
                // 日志 / 崩溃报告是纯垃圾
                if (rel.startsWith("logs/") || rel.startsWith("crash-reports/")) return;
                bare[0] = false;
            });
        } catch (IOException e) {
            return false;
        }
        return bare[0];
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            List<Path> paths = stream.sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList());
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }

    private static void installMrpack(Game game, Path zip, Path instance, String mc,
                                      Installer.Listener listener) throws Exception {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            JsonObject root = JsonParser.parseString(readText(zf, "modrinth.index.json")).getAsJsonObject();
            JsonArray files = Json.arr(root, "files");
            List<PackFile> list = new ArrayList<>();
            if (files != null) {
                for (JsonElement e : files) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    JsonObject env = Json.obj(o, "env");
                    if (env != null && "unsupported".equalsIgnoreCase(Json.str(env, "client", "required"))) {
                        continue;
                    }
                    PackFile pf = new PackFile();
                    pf.path = Json.str(o, "path", "");
                    JsonArray downloads = Json.arr(o, "downloads");
                    if (downloads != null && downloads.size() > 0 && downloads.get(0).isJsonPrimitive()) {
                        pf.url = downloads.get(0).getAsString();
                    }
                    pf.size = Json.lng(o, "fileSize", 0);
                    JsonObject hashes = Json.obj(o, "hashes");
                    pf.sha1 = Json.str(hashes, "sha1", "");
                    if (pf.path.isEmpty() || pf.url.isEmpty()) continue;
                    list.add(pf);
                }
            }
            downloadFiles(game, instance, list, listener);

            listener.onStage("解压整合包配置");
            extractOverrides(zf, instance, "overrides/", listener);
            extractOverrides(zf, instance, "client-overrides/", listener);
        }
    }

    private static void installCurseForge(Game game, Path zip, Path instance, String mc,
                                          Installer.Listener listener) throws Exception {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            JsonObject root = JsonParser.parseString(readText(zf, "manifest.json")).getAsJsonObject();
            String overridesDir = Json.str(root, "overrides", "overrides");
            List<PackFile> list = new ArrayList<>();
            JsonArray files = Json.arr(root, "files");
            if (files != null) {
                for (JsonElement e : files) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    PackFile pf = new PackFile();
                    pf.projectId = String.valueOf(Json.lng(o, "projectID", 0));
                    pf.fileId = String.valueOf(Json.lng(o, "fileID", 0));
                    pf.classId = Json.integer(o, "classId", 6);
                    list.add(pf);
                }
            }
            resolveCurseForgeFiles(list, game.settings().curseforgeApiKey, listener);
            if (!list.isEmpty()) downloadFiles(game, instance, list, listener);

            listener.onStage("解压整合包配置");
            extractOverrides(zf, instance, overridesDir.endsWith("/") ? overridesDir : overridesDir + "/", listener);
        }
    }

    /** 通过 CurseForge API 补全文件名与下载地址。 */
    private static void resolveCurseForgeFiles(List<PackFile> files, String key, Installer.Listener listener)
            throws IOException {
        // 设置里没填就用内置兜底 key。早先这里只认用户手填的，结果「没填 key」= 整包
        // 495 个模组全部跳过，用户看到的却是「整合包装好了」，进游戏才发现是空包。
        final String useKey = Compat.isBlank(key) ? mcl.mod.CurseForge.key() : key;
        if (Compat.isBlank(useKey)) {
            listener.onStage("没有可用的 CurseForge API Key，跳过 " + files.size() + " 个模组文件");
            Log.warn("没有可用的 CurseForge API Key，整合包只安装了配置与加载器");
            files.clear();
            return;
        }
        listener.onStage("向 CurseForge 查询 " + files.size() + " 个文件");
        // 495 个文件串行查要一分多钟，界面像卡死；8 路并发压到十秒级
        final List<PackFile> resolved = java.util.Collections.synchronizedList(new ArrayList<PackFile>());
        final int total = files.size();
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        int threads = Math.min(8, Math.max(1, total));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "mcl-cf-resolve");
            t.setDaemon(true);
            return t;
        });
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (final PackFile pf : files) {
            futures.add(pool.submit(() -> {
                try {
                    resolveOne(pf, useKey, resolved);
                } catch (Exception e) {
                    Log.warn("CurseForge 文件 " + pf.projectId + "/" + pf.fileId + " 查询失败：" + e.getMessage());
                } finally {
                    int n = done.incrementAndGet();
                    if (n % 10 == 0 || n == total) listener.onProgress(n, total, 0, 0, "查询文件信息");
                }
                return null;
            }));
        }
        try {
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
        files.clear();
        files.addAll(resolved);
    }

    /** 查一个文件的名字 / 地址 / SHA1，并算出它在整合包里的落点。 */
    private static void resolveOne(PackFile pf, String key, List<PackFile> out) throws IOException {
        String body = Http.getStringWithHeader(
                "https://api.curseforge.com/v1/mods/" + pf.projectId + "/files/" + pf.fileId,
                "x-api-key", key);
        JsonObject data = Json.obj(JsonParser.parseString(body).getAsJsonObject(), "data");
        if (data == null) return;
        String name = Json.str(data, "fileName", "");
        String url = Json.str(data, "downloadUrl", "");
        if (url == null || Compat.isBlank(url)) {
            long fid = Long.parseLong(pf.fileId);
            // 文件名里可能有空格 / 中文 / 井号，不编码拼出来的地址是 404
            String enc = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20");
            url = "https://mediafilez.forgecdn.net/files/" + (fid / 1000) + "/" + (fid % 1000) + "/" + enc;
        }
        JsonArray hashes = Json.arr(data, "hashes");
        if (hashes != null) {
            for (JsonElement h : hashes) {
                if (h.isJsonObject()) {
                    JsonObject ho = h.getAsJsonObject();
                    // CF 这里 algo 可能给数字 1 也可能给字符串 "sha1"
                    String algo = Json.str(ho, "algo", "");
                    if ("sha1".equalsIgnoreCase(algo) || "1".equals(algo)) {
                        pf.sha1 = Json.str(ho, "value", "");
                    }
                }
            }
        }
        if (Compat.isBlank(name) || Compat.isBlank(url)) return;
        if (pf.classId == 12) {
            pf.path = "resourcepacks/" + name;
        } else if (pf.classId == 6552) {
            pf.path = "shaderpacks/" + name;
        } else if (pf.classId == 17) {
            pf.path = "";   // 整合包里的整合包不支持嵌套
        } else {
            pf.path = "mods/" + name;
        }
        pf.url = url;
        pf.size = Json.lng(data, "fileLength", 0);
        if (!pf.path.isEmpty()) out.add(pf);
    }

    private static void downloadFiles(Game game, Path instance, List<PackFile> files,
                                      Installer.Listener listener) throws IOException {
        if (files.isEmpty()) return;
        listener.onStage("准备下载 " + files.size() + " 个整合包文件");
        Downloader dl = new Downloader(game.source(), game.settings().downloadThreads);
        for (PackFile pf : files) {
            Path dest = instance.resolve(pf.path.replace('\\', '/'));
            if (dest.normalize().startsWith(instance.normalize())) {
                dl.addDirect(pf.path, pf.url, dest, pf.sha1, pf.size);
            }
        }
        int failed = dl.run(listener);
        if (failed > 0) {
            Log.warn(failed + " 个整合包文件下载失败，其余内容已安装");
            listener.onStage("已安装，但 " + failed + " 个文件下载失败");
        }
    }

    /** 解压 overrides（只接受目录前缀，防目录穿越）。 */
    private static void extractOverrides(ZipFile zf, Path instance, String prefix, Installer.Listener listener)
            throws IOException {
        Enumeration<? extends ZipEntry> en = zf.entries();
        int count = 0;
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String name = e.getName();
            if (prefix != null && !name.startsWith(prefix)) continue;
            String rel = prefix == null ? name : name.substring(prefix.length());
            if (Compat.isBlank(rel)) continue;
            Path out = instance.resolve(rel).normalize();
            if (!out.startsWith(instance.normalize())) continue;
            if (out.getParent() != null) Dirs.ensure(out.getParent());
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            count++;
        }
        if (count > 0) Log.info("解压 " + count + " 个整合包配置文件");
    }

    /**
     * 写实例的版本 JSON。
     *
     * <p>{@code mcl} 里额外记下游戏版本与加载器：实例 id 是用户看得懂的名字（BMC4），
     * 从 id 上认不出加载器，而加载器版本被收进 {@code loader/} 之后
     * {@code inheritsFrom} 已经不能再当唯一依据了。
     */
    private static void writeInstanceJson(Path instance, String id, String parentId,
                                          String loaderKind, String mcVersion, String loaderVersion)
            throws IOException {
        String now = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("inheritsFrom", parentId);
        o.addProperty("type", "custom");
        o.addProperty("releaseTime", now);
        o.addProperty("time", now);
        JsonObject mcl = new JsonObject();
        mcl.addProperty("name", id);
        if (!Compat.isBlank(loaderKind)) mcl.addProperty("loader", loaderKind);
        if (!Compat.isBlank(loaderVersion)) mcl.addProperty("loaderVersion", loaderVersion);
        if (!Compat.isBlank(mcVersion)) mcl.addProperty("mcVersion", mcVersion);
        o.add("mcl", mcl);
        Compat.writeString(instance.resolve(id + ".json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
    }

    public static String uniqueInstanceId(Game game, String name) {
        String base = sanitize(name);
        Path versions = game.gameDir().resolve("versions");
        String id = base;
        int i = 2;
        while (Files.exists(versions.resolve(id))) id = base + "-" + i++;
        return id;
    }

    private static String sanitize(String name) {
        if (name == null || Compat.isBlank(name)) name = "整合包";
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (s.length() > 48) s = s.substring(0, 48);
        return Compat.isBlank(s) ? "整合包" : s;
    }

    // ---------------- 导出 ----------------

    /** 不会打进整合包的目录/文件。 */
    private static final List<String> EXCLUDE = Compat.listOf(
            "versions", "libraries", "assets", "logs", "crash-reports", "saves", "backups",
            "runtime", "cache", "usercache.json", "launcher_profiles.json", "launcher_accounts.json",
            "optionsof.txt", ".mcl-isolate", VersionManager.LOCAL_LOADER,
            // 启动器自己维护的启动配置，属于本机状态，打进整合包只会带到别人机器上添乱
            VersionProfiles.DIR);

    /**
     * 导出为 CurseForge / HMCL 兼容的整合包 zip（manifest.json + overrides）。
     */
    public static Path export(Path instanceDir, String name, String version, String author,
                              String mcVersion, String loaderKind, String loaderVersion, Path target)
            throws IOException {
        if (target.getParent() != null) Dirs.ensure(target.getParent());
        JsonObject manifest = new JsonObject();
        manifest.addProperty("formatVersion", 1);
        manifest.addProperty("game", "minecraft");
        manifest.addProperty("version", version == null ? "1.0" : version);
        manifest.addProperty("author", author == null ? "MCL" : author);
        manifest.addProperty("name", name);
        JsonObject mc = new JsonObject();
        mc.addProperty("version", mcVersion);
        JsonArray loaders = new JsonArray();
        if (loaderKind != null && !"vanilla".equals(loaderKind) && loaderVersion != null
                && !Compat.isBlank(loaderVersion)) {
            JsonObject l = new JsonObject();
            String prefix;
            if ("fabric".equals(loaderKind)) {
                prefix = "fabric-";
            } else if ("quilt".equals(loaderKind)) {
                prefix = "quilt-";
            } else if ("neoforge".equals(loaderKind)) {
                prefix = "neoforge-";
            } else {
                prefix = "forge-";
            }
            l.addProperty("id", prefix + loaderVersion);
            l.addProperty("primary", true);
            loaders.add(l);
        } else {
            JsonObject l = new JsonObject();
            l.addProperty("id", "vanilla");
            l.addProperty("primary", true);
            loaders.add(l);
        }
        mc.add("modLoaders", loaders);
        manifest.add("minecraft", mc);
        manifest.add("files", new JsonArray());
        manifest.addProperty("overrides", "overrides");

        try (OutputStream fos = Files.newOutputStream(target);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            ZipEntry me = new ZipEntry("manifest.json");
            zos.putNextEntry(me);
            zos.write(new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                    .toJson(manifest).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            String selfJson = instanceDir.getFileName().toString() + ".json";
            if (Files.isDirectory(instanceDir)) {
                try (java.util.stream.Stream<Path> stream = Files.walk(instanceDir)) {
                    List<Path> paths = stream.filter(Files::isRegularFile).sorted().collect(java.util.stream.Collectors.toList());
                    for (Path p : paths) {
                        String rel = instanceDir.relativize(p).toString().replace('\\', '/');
                        if (rel.equals(selfJson) || isExcluded(rel)) continue;
                        ZipEntry entry = new ZipEntry("overrides/" + rel);
                        zos.putNextEntry(entry);
                        Files.copy(p, zos);
                        zos.closeEntry();
                    }
                }
            }
        }
        return target;
    }

    private static boolean isExcluded(String rel) {
        String first = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
        for (String ex : EXCLUDE) {
            if (rel.equals(ex) || first.equalsIgnoreCase(ex)) return true;
        }
        return rel.toLowerCase(Locale.ROOT).endsWith(".log");
    }

    // ---------------- 工具 ----------------

    private static String readText(ZipFile zf, String entry) throws IOException {
        ZipEntry e = zf.getEntry(entry);
        if (e == null) throw new IOException("压缩包内缺少 " + entry);
        try (InputStream in = zf.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
