package mcl.game;

import mcl.util.Dirs;
import mcl.util.Compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import mcl.meta.Library;
import mcl.meta.VersionMeta;
import mcl.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 已安装版本管理：扫描、读取（含 inheritsFrom 合并）、删除。 */
public class VersionManager {

    /**
     * 整合包自带的加载器放在实例目录内的这个子目录下：{@code <实例>/loader/<加载器 id>/<id>.json}。
     *
     * <p>整合包安装时会把 Forge / Fabric 收进自己的目录 —— 用户眼里「整合包文件夹里就该有
     * 它需要的加载器」，而不是在 {@code versions/} 里再摆一个共享的 {@code 1.20.1-forge-47.4.20}。
     * 只有版本 JSON 会搬进来（几百 KB）；依赖库仍然共用 {@code libraries/}，
     * 否则每个整合包都要多占几百 MB。
     */
    public static final String LOCAL_LOADER = "loader";

    public static class InstalledVersion {
        public String id = "";
        public String type = "release";
        public String releaseTime = "";
        public Path dir;
        public Path json;
        public Path jar;
        public boolean hasJar;

        @Override
        public String toString() {
            return id;
        }
    }

    private final Path gameDir;

    public VersionManager(Path gameDir) {
        this.gameDir = gameDir;
    }

    public Path gameDir() {
        return gameDir;
    }

    public Path versionsDir() {
        return gameDir.resolve("versions");
    }

    public List<InstalledVersion> list() {
        List<InstalledVersion> result = new ArrayList<>();
        Path dir = versionsDir();
        if (!Files.isDirectory(dir)) return result;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(d -> {
                String id = d.getFileName().toString();
                Path json = d.resolve(id + ".json");
                if (!Files.isRegularFile(json)) return;
                InstalledVersion v = new InstalledVersion();
                v.id = id;
                v.dir = d;
                v.json = json;
                v.jar = d.resolve(id + ".jar");
                v.hasJar = Files.isRegularFile(v.jar);
                if (!v.hasJar) {
                    // 加载器版本（1.20.1-forge-47.4.20）和整合包实例（BMC4）自己目录里
                    // 没有 jar，客户端是靠 inheritsFrom 从原版继承来的。
                    // 只看自己的目录会一律判成「缺少客户端 jar」——列表上看着像坏了。
                    Path inherited = inheritedJar(dir, id);
                    if (inherited != null) {
                        v.jar = inherited;
                        v.hasJar = true;
                    }
                }
                try {
                    VersionMeta meta = VersionMeta.parse(Compat.readString(json));
                    v.type = meta.type;
                    v.releaseTime = meta.releaseTime;
                } catch (Exception e) {
                    v.type = "unknown";
                }
                result.add(v);
            });
        } catch (IOException e) {
            Log.warn("读取版本目录失败：" + e.getMessage());
        }
        result.sort((a, b) -> b.releaseTime.compareTo(a.releaseTime));
        return result;
    }

    public boolean isInstalled(String id) {
        return versionJson(id) != null;
    }

    /**
     * 版本 JSON 的真实位置。
     *
     * <p>先找全局的 {@code versions/<id>/<id>.json}；找不到再找实例自带的
     * {@code versions/<实例>/loader/<id>/<id>.json}（整合包把加载器收进了自己目录）。
     */
    public Path versionJson(String id) {
        return jsonOf(versionsDir(), id, versionsDir().resolve(id));
    }

    /** 实例目录里的加载器目录（整合包用）。 */
    public Path localLoaderDir(String instanceId) {
        return versionsDir().resolve(instanceId).resolve(LOCAL_LOADER);
    }

    private static Path jsonOf(Path versionsDir, String id, Path context) {
        if (id == null || id.isEmpty()) return null;
        Path global = versionsDir.resolve(id).resolve(id + ".json");
        if (Files.isRegularFile(global)) return global;
        if (context != null) {
            Path local = context.resolve(LOCAL_LOADER).resolve(id).resolve(id + ".json");
            if (Files.isRegularFile(local)) return local;
        }
        return null;
    }

    /** 读一个版本 JSON 的 inheritsFrom（读不出来返回 null）。 */
    private static String inheritsFromOf(Path json) {
        try {
            return mcl.util.Json.str(com.google.gson.JsonParser
                    .parseString(Compat.readString(json)).getAsJsonObject(), "inheritsFrom", null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 顺着 inheritsFrom 往上找客户端 jar。返回 null 表示整条链上都没有。
     *
     * <p>先看自己的目录，再看 {@code "jar"} 字段（整合包把自己做成独立 profile、
     * 没有 inheritsFrom 时靠它指回原版），最后才顺着继承链走。
     */
    private static Path inheritedJar(Path versionsDir, String id) {
        return clientJarOf(versionsDir, id, versionsDir.resolve(id), 0);
    }

    private static Path clientJarOf(Path versionsDir, String id, Path context, int depth) {
        if (id == null || id.isEmpty() || depth > 8) return null;
        Path own = versionsDir.resolve(id).resolve(id + ".jar");
        if (Files.isRegularFile(own)) return own;
        Path json = jsonOf(versionsDir, id, context);
        if (json == null) return null;
        String jarId = jsonStringOf(json, "jar");
        if (!Compat.isBlank(jarId)) {
            Path viaJar = versionsDir.resolve(jarId).resolve(jarId + ".jar");
            if (Files.isRegularFile(viaJar)) return viaJar;
        }
        String parent = inheritsFromOf(json);
        if (Compat.isBlank(parent)) return null;
        return clientJarOf(versionsDir, parent, context, depth + 1);
    }

    private static String jsonStringOf(Path json, String key) {
        try {
            return mcl.util.Json.str(com.google.gson.JsonParser
                    .parseString(Compat.readString(json)).getAsJsonObject(), key, "");
        } catch (Exception e) {
            return "";
        }
    }

    public VersionMeta load(String id) throws IOException {
        Path dir = versionsDir().resolve(id);
        Path json = jsonOf(versionsDir(), id, dir);
        if (json == null) throw new IOException("版本 " + id + " 未安装");
        return load(json, dir);
    }

    /**
     * @param context 链查找的上下文目录：整合包实例目录，用来找它自带的加载器 JSON
     */
    private VersionMeta load(Path json, Path context) throws IOException {
        VersionMeta meta = VersionMeta.parse(Compat.readString(json));
        if (meta.inheritsFrom != null && !meta.inheritsFrom.isEmpty()) {
            Path parent = jsonOf(versionsDir(), meta.inheritsFrom, context);
            if (parent != null) {
                merge(load(parent, context), meta);
            } else {
                Log.warn("版本 " + meta.id + " 依赖的父版本 " + meta.inheritsFrom + " 未安装");
            }
        }
        return meta;
    }

    /** 把父版本（原版）信息合并进子版本（Forge/Fabric 等）。 */
    private static void merge(VersionMeta parent, VersionMeta child) {
        if (child.mainClass.isEmpty()) child.mainClass = parent.mainClass;
        if (child.assets.isEmpty()) child.assets = parent.assets;
        if (child.assetIndexId == null) {
            child.assetIndexId = parent.assetIndexId;
            child.assetIndexUrl = parent.assetIndexUrl;
            child.assetIndexSha1 = parent.assetIndexSha1;
            child.assetIndexSize = parent.assetIndexSize;
            child.assetTotalSize = parent.assetTotalSize;
        }
        if (child.clientUrl == null) {
            child.clientUrl = parent.clientUrl;
            child.clientSha1 = parent.clientSha1;
            child.clientSize = parent.clientSize;
        }
        if (child.logConfigUrl == null) {
            child.logConfigUrl = parent.logConfigUrl;
            child.logConfigId = parent.logConfigId;
            child.logConfigSha1 = parent.logConfigSha1;
            child.logConfigSize = parent.logConfigSize;
            child.logArgument = parent.logArgument;
        }
        if (child.javaMajor == 0) {
            child.javaMajor = parent.javaMajor;
            child.javaComponent = parent.javaComponent;
        }
        if (child.legacyArguments == null) child.legacyArguments = parent.legacyArguments;
        // 参数按"父在前、子在后"拼接：加载器的 profile JSON 常带空数组，直接覆盖会丢掉原版参数
        child.gameArguments = concat(parent.gameArguments, child.gameArguments);
        child.jvmArguments = concat(parent.jvmArguments, child.jvmArguments);

        Map<String, Library> libs = new LinkedHashMap<>();
        for (Library l : parent.libraries) libs.put(l.name, l);
        for (Library l : child.libraries) libs.put(l.name, l);
        child.libraries.clear();
        child.libraries.addAll(libs.values());
    }

    public static List<Path> libraryFiles(Path gameDir, VersionMeta meta, Map<String, Boolean> features) {
        List<Path> result = new ArrayList<>();
        for (Library lib : meta.libraries) {
            if (!lib.allowed(features)) continue;
            Path p = gameDir.resolve("libraries").resolve(lib.artifactPath());
            if (Files.isRegularFile(p)) result.add(p);
        }
        return result;
    }

    public static JsonArray concat(JsonArray a, JsonArray b) {
        if (a == null) return b;
        if (b == null) return a;
        JsonArray out = new JsonArray();
        for (JsonElement e : a) out.add(e);
        for (JsonElement e : b) out.add(e);
        return out;
    }

    /** 版本的关键信息（游戏版本 + 加载器），用于搜索模组时自动填条件。 */
    public static class Info {
        public String mcVersion = "";
        public String loaderKind = "vanilla";
        public String loaderVersion = "";

        public String loaderLabel() {
            if ("fabric".equals(loaderKind)) return "Fabric";
            if ("quilt".equals(loaderKind)) return "Quilt";
            if ("forge".equals(loaderKind)) return "Forge";
            if ("neoforge".equals(loaderKind)) return "NeoForge";
            if ("optifine".equals(loaderKind)) return "OptiFine";
            return "原版";
        }

        /** Modrinth 的加载器标识；原版返回空串表示不按加载器过滤。 */
        public String modrinthLoader() {
            if ("fabric".equals(loaderKind) || "quilt".equals(loaderKind)
                    || "forge".equals(loaderKind) || "neoforge".equals(loaderKind)) {
                return loaderKind;
            }
            return "";
        }

        @Override
        public String toString() {
            return mcVersion + " · " + loaderLabel()
                    + (Compat.isBlank(loaderVersion) ? "" : " " + loaderVersion);
        }
    }

    /**
     * 只看 id 本身能不能认出加载器，**不**顺着 inheritsFrom 往上找。
     *
     * <p>用来区分两种版本：`1.20.1-forge-47.4.20` 自己就是加载器版本（可以单独卸载），
     * 而整合包实例 `BMC4` 只是继承了它 —— 对后者点「卸载加载器」等于删掉整个整合包。
     */
    public static Info idInfo(String id) {
        Info info = new Info();
        if (id != null) parseId(id, info);
        return info;
    }

    /** 沿 inheritsFrom 链推断游戏版本与加载器类型。 */
    public Info describe(String id) {
        Info info = new Info();
        // 整合包实例的 id 是用户起的名（BMC4），从名字上看不出加载器；它的 inheritsFrom
        // 指向的加载器版本也可能已经被收进实例目录 / 被清理。所以优先读安装时写下的 mcl 元信息。
        Info tagged = taggedInfo(id);
        if (tagged != null) return tagged;
        // 加载器现在收进版本目录了（.mcl-profiles），id 还是 1.21.11 这么干净的名字，
        // 认加载器只能看「当前生效的是哪份配置」
        Info fromProfile = profileInfo(id);
        if (fromProfile != null) return fromProfile;
        String current = id;
        Path context = versionsDir().resolve(id);
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (parseId(current, info)) return info;
            Path json = jsonOf(versionsDir(), current, context);
            if (json == null) break;
            current = inheritsFromOf(json);
        }
        if (info.mcVersion.isEmpty()) info.mcVersion = id;
        return info;
    }

    /** 读 {@code .mcl-profiles} 里当前生效的加载器配置；生效的是原版就返回 null。 */
    private Info profileInfo(String id) {
        Path vdir = versionsDir().resolve(id);
        if (!VersionProfiles.has(vdir)) return null;
        String act = VersionProfiles.active(vdir);
        if (VersionProfiles.VANILLA.equals(act)) return null;
        String[] kv = VersionProfiles.parse(act);
        if (kv == null) return null;
        Info info = new Info();
        info.loaderKind = kv[0];
        info.loaderVersion = kv[1];
        String mc = VersionProfiles.mcVersion(vdir);
        info.mcVersion = Compat.isBlank(mc) ? id : mc;
        return info;
    }

    /** 读版本 JSON 里的 {@code mcl} 元信息（整合包安装时写入）；没有就返回 null。 */
    private Info taggedInfo(String id) {
        Path json = versionJson(id);
        if (json == null) return null;
        try {
            com.google.gson.JsonObject tag = mcl.util.Json.obj(com.google.gson.JsonParser
                    .parseString(Compat.readString(json)).getAsJsonObject(), "mcl");
            if (tag == null) return null;
            String kind = mcl.util.Json.str(tag, "loader", "");
            String mc = mcl.util.Json.str(tag, "mcVersion", "");
            if (Compat.isBlank(kind) && Compat.isBlank(mc)) return null;
            Info info = new Info();
            info.loaderKind = Compat.isBlank(kind) ? "vanilla" : kind;
            info.loaderVersion = mcl.util.Json.str(tag, "loaderVersion", "");
            info.mcVersion = Compat.isBlank(mc) ? id : mc;
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean parseId(String id, Info info) {
        java.util.regex.Matcher m;
        if ((m = java.util.regex.Pattern.compile("^fabric-loader-(.+)-(.+)$").matcher(id)).matches()) {
            info.loaderKind = "fabric";
            info.loaderVersion = m.group(1);
            info.mcVersion = m.group(2);
            return true;
        }
        if ((m = java.util.regex.Pattern.compile("^quilt-loader-(.+)-(.+)$").matcher(id)).matches()) {
            info.loaderKind = "quilt";
            info.loaderVersion = m.group(1);
            info.mcVersion = m.group(2);
            return true;
        }
        if ((m = java.util.regex.Pattern.compile("^(.+)-forge-(.+)$").matcher(id)).matches()) {
            info.loaderKind = "forge";
            info.mcVersion = m.group(1);
            info.loaderVersion = m.group(2);
            return true;
        }
        if ((m = java.util.regex.Pattern.compile("^(.+)-neoforge-(.+)$").matcher(id)).matches()) {
            info.loaderKind = "neoforge";
            info.mcVersion = m.group(1);
            info.loaderVersion = m.group(2);
            return true;
        }
        if ((m = java.util.regex.Pattern.compile("^(.+)-OptiFine_(.+)$").matcher(id)).matches()) {
            info.loaderKind = "optifine";
            info.mcVersion = m.group(1);
            info.loaderVersion = m.group(2);
            return true;
        }
        if (id.matches("^(\\d+\\.\\d+(\\.\\d+)?|\\d{2}\\.\\d+(\\.\\d+)?)([-.].*)?$")) {
            info.mcVersion = id;
            info.loaderKind = "vanilla";
            return true;
        }
        return false;
    }

    /** 重命名版本（目录、json、jar 一起改，并修正其它版本对它的 inheritsFrom 引用）。 */
    public void rename(String oldId, String newId) throws IOException {
        validateId(newId);
        Path from = versionsDir().resolve(oldId);
        Path to = versionsDir().resolve(newId);
        if (!Files.isDirectory(from)) throw new IOException("版本不存在：" + oldId);
        if (Files.exists(to)) throw new IOException("版本 id 已被占用：" + newId);
        Files.move(from, to);
        Path oldJson = to.resolve(oldId + ".json");
        Path oldJar = to.resolve(oldId + ".jar");
        if (Files.isRegularFile(oldJson)) {
            rewriteId(oldJson, newId, oldId, to.resolve(newId + ".json"));
        }
        if (Files.isRegularFile(oldJar)) {
            Files.move(oldJar, to.resolve(newId + ".jar"));
        }
        // 子版本（例如整合包实例）的 inheritsFrom 需要跟着改
        for (InstalledVersion v : list()) {
            if (v.id.equals(newId)) continue;
            try {
                com.google.gson.JsonObject o = com.google.gson.JsonParser
                        .parseString(Compat.readString(v.json)).getAsJsonObject();
                String parent = mcl.util.Json.str(o, "inheritsFrom", null);
                if (parent != null && parent.equals(oldId)) {
                    o.addProperty("inheritsFrom", newId);
                    Compat.writeString(v.json, new com.google.gson.GsonBuilder().setPrettyPrinting()
                            .disableHtmlEscaping().create().toJson(o));
                }
            } catch (Exception ignored) {
            }
        }
        Log.info("版本已重命名：" + oldId + " -> " + newId);
    }

    /** 复制版本（用于做实验版本而不破坏原版本）。 */
    public void copy(String srcId, String newId) throws IOException {
        validateId(newId);
        Path from = versionsDir().resolve(srcId);
        Path to = versionsDir().resolve(newId);
        if (!Files.isDirectory(from)) throw new IOException("版本不存在：" + srcId);
        if (Files.exists(to)) throw new IOException("版本 id 已被占用：" + newId);
        try (java.util.stream.Stream<Path> stream = Files.walk(from)) {
            for (Path src : stream.collect(java.util.stream.Collectors.toList())) {
                Path dest = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Dirs.ensure(dest);
                } else {
                    if (dest.getParent() != null) Dirs.ensure(dest.getParent());
                    Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path oldJson = to.resolve(srcId + ".json");
        Path oldJar = to.resolve(srcId + ".jar");
        if (Files.isRegularFile(oldJson)) rewriteId(oldJson, newId, srcId, to.resolve(newId + ".json"));
        if (Files.isRegularFile(oldJar)) Files.move(oldJar, to.resolve(newId + ".jar"));
        Log.info("版本已复制：" + srcId + " -> " + newId);
    }

    private static void rewriteId(Path json, String newId, String oldId, Path target) throws IOException {
        com.google.gson.JsonObject o = com.google.gson.JsonParser
                .parseString(Compat.readString(json)).getAsJsonObject();
        o.addProperty("id", newId);
        if (o.has("jar") && o.get("jar").isJsonPrimitive() && oldId.equals(o.get("jar").getAsString())) {
            o.addProperty("jar", newId);
        }
        String text = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o);
        Compat.writeString(target == null ? json : target, text);
        if (target != null && !target.equals(json)) Files.deleteIfExists(json);
    }

    private static void validateId(String id) throws IOException {
        if (id == null || Compat.isBlank(id)) throw new IOException("版本 id 不能为空");
        if (!id.matches("[^\\\\/:*?\"<>|]+")) throw new IOException("版本 id 不能包含 \\ / : * ? \" < > |");
    }
}
