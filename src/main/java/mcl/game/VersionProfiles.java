package mcl.game;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.util.Compat;
import mcl.util.Dirs;
import mcl.util.Json;
import mcl.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 版本目录里的「启动配置」集合。
 *
 * <p>装模组加载器（Fabric / Quilt / Forge / NeoForge）本质上是换一份 profile：
 * mainClass、libraries、启动参数全变了。但用户不认为那是「另一个版本」——
 * 给 1.21.11 装 Fabric，主页就该还是只有一个 1.21.11。
 *
 * <p>所以加载器不再是全局的独立版本，而是收进版本目录：
 * <pre>
 * versions/1.21.11/
 * ├── 1.21.11.jar
 * ├── 1.21.11.json             当前生效的那份（原版，或合并了加载器的）
 * └── .mcl-profiles/
 *     ├── index.json            {"mcVersion":"1.21.11","active":"fabric-0.20.1"}
 *     ├── vanilla.json          原版原文，装第一个加载器时备份
 *     └── fabric-0.20.1.json    完整 profile（没有 inheritsFrom）
 * </pre>
 *
 * <p>一份 profile 是**完整**的：从原版 JSON 出发，把加载器的主类、库、参数合并进去，
 * 不靠 inheritsFrom 指外面。所以删掉、改掉外面的任何东西都不影响它，
 * 整合包也能拿它当私有底座（见 {@link #merge(String, String, String, String)}）。
 */
public final class VersionProfiles {

    /** 存放启动配置的目录名（在版本目录下）。 */
    public static final String DIR = ".mcl-profiles";
    /** 原版那份配置的名字。 */
    public static final String VANILLA = "vanilla";

    private VersionProfiles() {
    }

    // ---------------- 目录与索引 ----------------

    public static Path dirOf(Path versionDir) {
        return versionDir.resolve(DIR);
    }

    public static Path profileFile(Path versionDir, String name) {
        return dirOf(versionDir).resolve(name + ".json");
    }

    /** 这个版本有没有多套启动配置（至少备份过原版）。 */
    public static boolean has(Path versionDir) {
        return Files.isRegularFile(profileFile(versionDir, VANILLA));
    }

    private static JsonObject index(Path versionDir) {
        Path f = dirOf(versionDir).resolve("index.json");
        if (!Files.isRegularFile(f)) return new JsonObject();
        try {
            return JsonParser.parseString(Compat.readString(f)).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static void writeIndex(Path versionDir, String mcVersion, String active) throws IOException {
        JsonObject o = index(versionDir);
        if (!Compat.isBlank(mcVersion)) o.addProperty("mcVersion", mcVersion);
        if (!Compat.isBlank(active)) o.addProperty("active", active);
        Dirs.ensure(dirOf(versionDir));
        Compat.writeString(dirOf(versionDir).resolve("index.json"), pretty(o));
    }

    /** 当前生效的配置名；没有记录就是原版。 */
    public static String active(Path versionDir) {
        String a = Json.str(index(versionDir), "active", "");
        return Compat.isBlank(a) ? VANILLA : a;
    }

    /** 这个版本对应的游戏版本（比如整合包改名之后也还认得出是 1.20.1）。 */
    public static String mcVersion(Path versionDir) {
        return Json.str(index(versionDir), "mcVersion", "");
    }

    /**
     * 备份原版 JSON。
     *
     * <p>只做一次：装第二个加载器时不能拿「已经合并过的 JSON」当原版，
     * 否则 Forge 的库和参数会被叠两遍。
     */
    public static void ensureVanilla(Path versionDir, String id) throws IOException {
        Path vanilla = profileFile(versionDir, VANILLA);
        if (Files.isRegularFile(vanilla)) return;
        Path current = versionDir.resolve(id + ".json");
        if (!Files.isRegularFile(current)) return;
        Dirs.ensure(dirOf(versionDir));
        Files.copy(current, vanilla, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 可选的配置名，原版排第一。 */
    public static List<String> names(Path versionDir) {
        List<String> out = new ArrayList<>();
        Path d = dirOf(versionDir);
        if (!Files.isDirectory(d)) return out;
        if (Files.isRegularFile(d.resolve(VANILLA + ".json"))) out.add(VANILLA);
        List<String> rest = new ArrayList<>();
        try (Stream<Path> stream = Files.list(d)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                if (!n.endsWith(".json")) return;
                String base = n.substring(0, n.length() - 5);
                if (base.equals(VANILLA) || base.equals("index")) return;
                rest.add(base);
            });
        } catch (IOException e) {
            Log.warn("读取启动配置失败：" + e.getMessage());
        }
        java.util.Collections.sort(rest);
        out.addAll(rest);
        return out;
    }

    // ---------------- 生效 / 增删 ----------------

    /** 切到某个配置：把它复制成 {@code <id>.json}，版本列表里看到的还是同一个版本。 */
    public static void setActive(Path versionDir, String id, String name) throws IOException {
        Path src = profileFile(versionDir, name);
        if (!Files.isRegularFile(src)) throw new IOException("启动配置 " + name + " 不存在");
        // 版本被复制 / 改名之后，配置里的 id 还是老的那个；启动器按 id 认版本，得对齐
        JsonObject o = JsonParser.parseString(Compat.readString(src)).getAsJsonObject();
        if (!id.equals(Json.str(o, "id", ""))) o.addProperty("id", id);
        Compat.writeString(versionDir.resolve(id + ".json"), pretty(o));
        writeIndex(versionDir, mcVersion(versionDir), name);
    }

    public static void put(Path versionDir, String name, String json) throws IOException {
        Dirs.ensure(dirOf(versionDir));
        Compat.writeString(profileFile(versionDir, name), json);
    }

    /**
     * 删掉一个配置；删的是当前生效的会自动切回原版。
     *
     * @return 是否真的删掉了
     */
    public static boolean remove(Path versionDir, String id, String name) {
        if (Compat.isBlank(name) || VANILLA.equals(name)) return false;
        try {
            boolean deleted = Files.deleteIfExists(profileFile(versionDir, name));
            if (deleted && name.equals(active(versionDir))) {
                setActive(versionDir, id, VANILLA);
            }
            return deleted;
        } catch (IOException e) {
            Log.warn("删除启动配置 " + name + " 失败：" + e.getMessage());
            return false;
        }
    }

    /** 删掉所有加载器配置，回到纯原版。返回删掉几个。 */
    public static int removeAllLoaders(Path versionDir, String id) {
        int removed = 0;
        for (String n : names(versionDir)) {
            if (VANILLA.equals(n)) continue;
            if (remove(versionDir, id, n)) removed++;
        }
        try {
            setActive(versionDir, id, VANILLA);
        } catch (IOException e) {
            Log.warn("切回原版失败：" + e.getMessage());
        }
        return removed;
    }

    // ---------------- 名字 ----------------

    /** "fabric" + "0.20.1" → "fabric-0.20.1"。 */
    public static String profileName(String kind, String version) {
        if (Compat.isBlank(kind) || "vanilla".equalsIgnoreCase(kind) || Compat.isBlank(version)) {
            return VANILLA;
        }
        return kind.toLowerCase(Locale.ROOT) + "-" + version;
    }

    /** "fabric-0.20.1" → {"fabric","0.20.1"}；vanilla 或认不出返回 null。 */
    public static String[] parse(String name) {
        if (Compat.isBlank(name) || VANILLA.equals(name)) return null;
        int i = name.indexOf('-');
        if (i <= 0 || i >= name.length() - 1) return null;
        return new String[]{name.substring(0, i), name.substring(i + 1)};
    }

    public static String label(String name) {
        String[] kv = parse(name);
        if (kv == null) return "原版";
        return kindLabel(kv[0]) + " " + kv[1];
    }

    public static String kindLabel(String kind) {
        if ("fabric".equals(kind)) return "Fabric";
        if ("quilt".equals(kind)) return "Quilt";
        if ("forge".equals(kind)) return "Forge";
        if ("neoforge".equals(kind)) return "NeoForge";
        if ("optifine".equals(kind)) return "OptiFine";
        return "原版";
    }

    // ---------------- 合并 ----------------

    /**
     * 把独立加载器版本（{@code versions/<loaderId>}）收进 {@code baseId} 的配置里。
     *
     * @return 生成的配置名，例如 {@code fabric-0.20.1}
     */
    public static String absorb(Path gameDir, String baseId, String loaderId) throws IOException {
        Path versions = gameDir.resolve("versions");
        Path baseDir = versions.resolve(baseId);
        if (!Files.isRegularFile(baseDir.resolve(baseId + ".json"))) {
            throw new IOException("基础版本 " + baseId + " 不存在");
        }
        Path loaderJson = versions.resolve(loaderId).resolve(loaderId + ".json");
        if (!Files.isRegularFile(loaderJson)) {
            throw new IOException("加载器 " + loaderId + " 不存在");
        }
        ensureVanilla(baseDir, baseId);

        String baseText = Compat.readString(profileFile(baseDir, VANILLA));
        String childText = Compat.readString(loaderJson);
        String merged = merge(baseText, childText, baseId, null);

        VersionManager.Info info = VersionManager.idInfo(loaderId);
        String name = profileName(info.loaderKind, info.loaderVersion);
        put(baseDir, name, merged);

        // 游戏版本优先信加载器 JSON 的 inheritsFrom —— 版本被改过名也不会认错
        String mc = jsonString(loaderJson, "inheritsFrom");
        writeIndex(baseDir, Compat.isBlank(mc) ? info.mcVersion : mc, active(baseDir));
        Log.info("加载器 " + loaderId + " 已并进 " + baseId + " 的「" + label(name) + "」配置");
        return name;
    }

    /**
     * 删掉一个加载器版本目录，但先把里面剩下的真东西搬到 {@code keeper} 目录。
     *
     * <p>加载器一旦并进版本配置，{@code versions/fabric-loader-x-y} 这种目录就该消失 ——
     * 留着就是主页上多一个看不懂的条目。可它里面未必只有一份 JSON：装加载器时
     * {@code prepareVersionDir} 会给它建版本隔离，把共用的 {@code saves} / {@code options.txt}
     * 接管一份进来，用户也可能真在里面玩过。按「空壳」判断会把这些一并删掉。
     *
     * <p>所以这里逐个往下搬：**目标已有同名文件的一律跳过**，以 keeper（原版目录，也就是
     * 加载器现在所属的那个版本）里那份为准，绝不覆盖。搬完再删整个目录。
     *
     * @param keeper 收留内容的目录；null 表示不搬、直接删
     */
    public static void discard(Path gameDir, Path keeper, String loaderId) throws IOException {
        Path dir = gameDir.resolve("versions").resolve(loaderId);
        if (!Files.isDirectory(dir)) return;
        int moved = 0;
        boolean keep = keeper != null && Files.isDirectory(keeper) && !keeper.equals(dir);
        if (keep) {
            try (Stream<Path> stream = Files.list(dir)) {
                for (Path p : stream.collect(java.util.stream.Collectors.toList())) {
                    String name = p.getFileName().toString();
                    if (name.equals(loaderId + ".json") || name.startsWith(".")) continue;
                    if (name.equals("logs") || name.equals("crash-reports")) continue;
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".log") || lower.equals("usercache.json")) continue;
                    moved += mergeInto(p, keeper.resolve(name));
                }
            }
        }
        deleteTree(dir);
        if (moved > 0) {
            Log.info("删掉加载器版本 " + loaderId + " 前，把它剩下的 " + moved + " 个文件搬进了 "
                    + keeper.getFileName() + "（没有覆盖任何已有文件）");
        }
    }

    /** 递归搬：目标不存在才搬，存在就留着目标那份。返回搬过去的文件数。 */
    private static int mergeInto(Path from, Path to) throws IOException {
        if (Files.isDirectory(from)) {
            if (Files.isRegularFile(to)) return 0;              // 一边目录一边文件，算冲突，放弃
            if (!Files.isDirectory(to)) Dirs.ensure(to);
            int n = 0;
            try (Stream<Path> stream = Files.list(from)) {
                for (Path p : stream.collect(java.util.stream.Collectors.toList())) {
                    n += mergeInto(p, to.resolve(p.getFileName().toString()));
                }
            }
            return n;
        }
        if (Files.exists(to)) return 0;
        Files.move(from, to);
        return 1;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * 拿一份**干净**的原版 JSON：优先用备份，没有就用版本目录里的。
     *
     * <p>整合包要拿它当私有底座 —— 不能拿已经被合并过的 JSON，
     * 否则父版本里的加载器会被叠进整合包里。
     */
    public static String vanillaJson(Path gameDir, String mcVersion) throws IOException {
        Path vdir = gameDir.resolve("versions").resolve(mcVersion);
        Path snapshot = profileFile(vdir, VANILLA);
        if (Files.isRegularFile(snapshot)) return Compat.readString(snapshot);
        Path json = vdir.resolve(mcVersion + ".json");
        if (!Files.isRegularFile(json)) throw new IOException("原版 " + mcVersion + " 不存在");
        return Compat.readString(json);
    }

    /**
     * 合并两份版本 JSON：以原版为底，叠加加载器的主类 / 库 / 参数。
     *
     * <p>纯 JSON 层面做，不走 {@link mcl.meta.VersionMeta} —— 那份结构只解析了一部分字段，
     * 序列化回去会丢掉 {@code downloads}、{@code assetIndex}、{@code logging} 之类，
     * 启动直接就坏了。
     *
     * @param id    合并后的版本 id
     * @param jarId 不为空时写 {@code "jar"}：这个 profile 自己不带客户端 jar，去指原版的
     */
    public static String merge(String baseJson, String childJson, String id, String jarId) {
        JsonObject base = JsonParser.parseString(baseJson).getAsJsonObject();
        JsonObject child = JsonParser.parseString(childJson).getAsJsonObject();
        JsonObject out = base.deepCopy();

        out.addProperty("id", id);
        out.remove("inheritsFrom");

        String mainClass = Json.str(child, "mainClass", "");
        if (!Compat.isBlank(mainClass)) out.addProperty("mainClass", mainClass);

        out.add("libraries", mergeLibraries(Json.arr(base, "libraries"), Json.arr(child, "libraries")));

        JsonObject baseArgs = Json.obj(base, "arguments");
        JsonObject childArgs = Json.obj(child, "arguments");
        if (baseArgs != null || childArgs != null) {
            JsonObject args = new JsonObject();
            // 父在前、子在后：加载器的 profile 常带空数组，直接覆盖会丢掉原版参数
            putArray(args, "game", baseArgs, childArgs, "game");
            putArray(args, "jvm", baseArgs, childArgs, "jvm");
            out.add("arguments", args);
        }

        String legacy = Json.str(child, "minecraftArguments", "");
        if (!Compat.isBlank(legacy)) out.addProperty("minecraftArguments", legacy);

        if (!Compat.isBlank(jarId)) out.addProperty("jar", jarId);
        else out.remove("jar");
        return pretty(out);
    }

    private static void putArray(JsonObject target, String key, JsonObject a, JsonObject b, String field) {
        JsonArray out = new JsonArray();
        append(out, a == null ? null : a.get(field));
        append(out, b == null ? null : b.get(field));
        if (out.size() > 0) target.add(key, out);
    }

    private static void append(JsonArray out, JsonElement e) {
        if (e == null || !e.isJsonArray()) return;
        for (JsonElement item : e.getAsJsonArray()) out.add(item);
    }

    /**
     * 库表合并：同一件东西（group:artifact[+classifier]）以加载器的为准。
     *
     * <p>Forge 经常把原版某个库升个版本，两条都留着会让 classpath 上出现两个版本 ——
     * 谁先谁后全看 JVM，属于那种「别人跑得好我这就崩」的 bug。
     */
    private static JsonArray mergeLibraries(JsonArray base, JsonArray child) {
        Map<String, JsonObject> byKey = new LinkedHashMap<>();
        if (base != null) collectLibraries(byKey, base);
        if (child != null) collectLibraries(byKey, child);
        JsonArray out = new JsonArray();
        for (JsonObject o : byKey.values()) out.add(o);
        return out;
    }

    private static void collectLibraries(Map<String, JsonObject> into, JsonArray arr) {
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String name = Json.str(o, "name", "");
            if (Compat.isBlank(name)) continue;
            into.put(libraryKey(o, name), o);
        }
    }

    private static String libraryKey(JsonObject o, String name) {
        String[] p = name.split(":");
        String group = p.length > 0 ? p[0] : name;
        String artifact = p.length > 1 ? p[1] : "";
        String classifier = p.length > 3 ? p[3] : "";
        // 老格式的 natives 条目（带 classifiers）和普通库不是一回事，别合并
        StringBuilder key = new StringBuilder(group).append(':').append(artifact);
        if (!classifier.isEmpty()) key.append('|').append(classifier);
        if (o.has("natives")) key.append("|natives");
        return key.toString();
    }

    private static String jsonString(Path json, String key) {
        try {
            return Json.str(JsonParser.parseString(Compat.readString(json)).getAsJsonObject(), key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String pretty(JsonObject o) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(o);
    }
}
