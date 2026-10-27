package mcl.mod;

import mcl.util.Compat;

import mcl.util.Digest;
import mcl.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 已安装模组的元数据解析、更新检查与冲突检测。
 *
 * <p>元数据来源按加载器不同：
 * <ul>
 *   <li>Fabric / Quilt：{@code fabric.mod.json}（id / name / version / depends）</li>
 *   <li>Forge / NeoForge：{@code META-INF/mods.toml}（modId / displayName / version / [[dependencies]]）</li>
 *   <li>1.12 及更早：{@code mcmod.info}（JSON 数组）</li>
 * </ul>
 *
 * <p>更新检查用文件 SHA1 反查 Modrinth 版本，再取该项目在目标游戏版本下的最新版本做比较。
 */
public final class ModInspector {

    /** 不算"模组"的依赖 id，检测缺失时忽略。 */
    private static final Set<String> BUILTIN = Compat.setOf(
            "minecraft", "java", "fabricloader", "fabric-loader", "quilt_loader", "quilt-loader",
            "forge", "neoforge", "fml", "mcp", "optifine", "optifabric", "liteloader", "rift");

    private ModInspector() {}

    public static final class Meta {
        public Path path;
        public String file = "";
        public String modId = "";
        public String name = "";
        public String version = "";
        public String loader = "";           // fabric / quilt / forge / neoforge / legacy
        public List<String> required = new ArrayList<>();
        /** 自己声明的 provides，以及 jar 内嵌套 jar 的 modId（Fabric API 这类"合集"模组）。 */
        public List<String> provides = new ArrayList<>();
        public String sha1 = "";
        public long size;

        public String title() {
            if (!name.isEmpty() && !version.isEmpty()) return name + " " + version;
            if (!name.isEmpty()) return name;
            return file;
        }

        public String loaderLabel() {
            if ("fabric".equals(loader)) return "Fabric";
            if ("quilt".equals(loader)) return "Quilt";
            if ("forge".equals(loader)) return "Forge";
            if ("neoforge".equals(loader)) return "NeoForge";
            if ("legacy".equals(loader)) return "Forge(旧版)";
            return "未知";
        }

        @Override
        public String toString() {
            return title();
        }
    }

    // ---------------- 元数据 ----------------

    public static List<Meta> scan(Path modsDir) {
        List<Meta> out = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) return out;
        try (java.util.stream.Stream<Path> stream = Files.list(modsDir)) {
            List<Path> jars = stream
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
            for (Path jar : jars) {
                Meta m = inspect(jar);
                if (m != null) out.add(m);
            }
        } catch (IOException e) {
            Log.warn("扫描模组目录失败：" + e.getMessage());
        }
        return out;
    }

    public static Meta inspect(Path jar) {
        Meta m = new Meta();
        m.path = jar;
        m.file = jar.getFileName().toString();
        try {
            m.size = Files.size(jar);
            m.sha1 = Digest.sha1(jar);
        } catch (IOException e) {
            return m;
        }
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry fabric = zf.getEntry("fabric.mod.json");
            if (fabric != null) {
                parseFabric(readText(zf, fabric), m);
                collectNested(zf, m.provides);
                return m;
            }
            ZipEntry neo = zf.getEntry("META-INF/neoforge.mods.toml");
            if (neo != null) {
                parseToml(readText(zf, neo), m, "neoforge");
                return m;
            }
            ZipEntry forge = zf.getEntry("META-INF/mods.toml");
            if (forge != null) {
                parseToml(readText(zf, forge), m, "forge");
                return m;
            }
            ZipEntry legacy = zf.getEntry("mcmod.info");
            if (legacy != null) {
                parseMcmodInfo(readText(zf, legacy), m);
                return m;
            }
        } catch (IOException e) {
            Log.warn("读取 " + m.file + " 失败：" + e.getMessage());
        }
        if (m.loader.isEmpty()) m.loader = "unknown";
        return m;
    }


    /**
     * 收集 jar 内 {@code META-INF/jars/} 里嵌套模组的 modId。
     * Fabric API 就是靠这种方式提供 fabric-* 系列子模块，判断依赖时必须算进去。
     */
    private static void collectNested(ZipFile zf, List<String> out) {
        try {
            java.util.List<? extends java.util.zip.ZipEntry> entries = java.util.Collections.list(zf.entries());
            for (ZipEntry e : entries) {
                String nm = e.getName();
                if (!nm.startsWith("META-INF/jars/") || !nm.endsWith(".jar")) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    readNestedIds(Compat.readAll(in), out, 2);
                } catch (IOException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void readNestedIds(byte[] jarBytes, List<String> out, int depth) {
        if (depth <= 0) return;
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String nm = e.getName();
                if (nm.equals("fabric.mod.json")) {
                    String json = new String(Compat.readAll(zis), StandardCharsets.UTF_8);
                    try {
                        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                        if (o.has("id")) out.add(o.get("id").getAsString());
                        if (o.has("provides")) {
                            for (com.google.gson.JsonElement p : o.getAsJsonArray("provides")) out.add(p.getAsString());
                        }
                    } catch (Exception ignored) {
                    }
                } else if (nm.startsWith("META-INF/jars/") && nm.endsWith(".jar")) {
                    readNestedIds(Compat.readAll(zis), out, depth - 1);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String readText(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            byte[] buf = Compat.readAll(in);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static void parseFabric(String json, Meta m) {
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
            if (!root.isJsonObject()) return;
            com.google.gson.JsonObject o = root.getAsJsonObject();
            m.loader = o.has("quilt_loader") ? "quilt" : "fabric";
            m.modId = str(o, "id");
            m.name = str(o, "name").isEmpty() ? m.modId : str(o, "name");
            m.version = str(o, "version");
            if (o.has("provides") && o.get("provides").isJsonArray()) {
                for (com.google.gson.JsonElement e : o.getAsJsonArray("provides")) {
                    try {
                        String id2 = e.getAsString();
                        if (!Compat.isBlank(id2)) m.provides.add(id2);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (o.has("depends") && o.get("depends").isJsonObject()) {
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("depends").entrySet()) {
                    String id = e.getKey();
                    if (BUILTIN.contains(id)) continue;
                    m.required.add(id);
                }
            }
        } catch (Exception e) {
            m.loader = "fabric";
        }
    }

    private static final Pattern TOML_MODID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOML_NAME = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TOML_VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TOML_DEP_BLOCK = Pattern.compile(
            "(?s)\\[\\[dependencies\\.([^\\]]+)\\]\\](.*?)(?=\\[\\[|\\z)");
    private static final Pattern TOML_DEP_MODID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOML_DEP_MANDATORY = Pattern.compile("(?m)^\\s*mandatory\\s*=\\s*(true|false)");

    private static void parseToml(String toml, Meta m, String loader) {
        m.loader = loader;
        Matcher id = TOML_MODID.matcher(toml);
        if (id.find()) m.modId = id.group(1);
        Matcher name = TOML_NAME.matcher(toml);
        if (name.find()) m.name = name.group(1);
        Matcher ver = TOML_VERSION.matcher(toml);
        if (ver.find()) m.version = ver.group(1);
        // 只取"本模组"的 dependencies 段（[[dependencies.<自己>]]）
        Matcher block = TOML_DEP_BLOCK.matcher(toml);
        while (block.find()) {
            String owner = block.group(1);
            if (!m.modId.isEmpty() && !owner.equals(m.modId)) continue;
            String body = block.group(2);
            Matcher depId = TOML_DEP_MODID.matcher(body);
            if (!depId.find()) continue;
            String dep = depId.group(1);
            if (BUILTIN.contains(dep)) continue;
            Matcher mand = TOML_DEP_MANDATORY.matcher(body);
            boolean mandatory = !mand.find() || "true".equals(mand.group(1));
            if (mandatory && !m.required.contains(dep)) m.required.add(dep);
        }
        if (m.name.isEmpty()) m.name = m.modId;
        if (m.version.isEmpty()) m.version = "";
    }

    private static void parseMcmodInfo(String json, Meta m) {
        m.loader = "legacy";
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
            if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) return;
            com.google.gson.JsonObject o = root.getAsJsonArray().get(0).getAsJsonObject();
            m.modId = str(o, "modid");
            m.name = str(o, "name");
            m.version = str(o, "version");
            if (m.name.isEmpty()) m.name = m.modId;
        } catch (Exception ignored) {
        }
    }

    private static String str(com.google.gson.JsonObject o, String key) {
        try {
            if (o.has(key) && !o.get(key).isJsonNull()) return o.get(key).getAsString();
        } catch (Exception ignored) {
        }
        return "";
    }

    // ---------------- 更新检查 ----------------

    public static final class Update {
        public Meta mod;
        public String projectId = "";
        public String latestVersion = "";
        public String latestName = "";
        public String downloadUrl = "";
        public String latestFile = "";
        public String sha1 = "";
        public long size;
        public boolean newer;

        public String describe() {
            return mod.title() + " → " + (latestVersion.isEmpty() ? latestName : latestVersion);
        }
    }

    /**
     * 检查更新：先用 SHA1 批量反查 Modrinth 版本（拿 project_id），
     * 再取该项目适配当前游戏版本/加载器的最新版本。
     *
     * @param onProgress 每处理完一个模组回调一次（可为 null）
     */
    public static List<Update> checkUpdates(List<Meta> mods, String mcVersion, String loader,
                                            java.util.function.BiConsumer<Integer, Integer> onProgress) {
        List<Update> result = new ArrayList<>();
        Map<String, Meta> byHash = new LinkedHashMap<>();
        for (Meta m : mods) {
            if (m.sha1 != null && !m.sha1.isEmpty()) byHash.put(m.sha1, m);
        }
        Map<String, Modrinth.VersionInfo> known;
        try {
            known = Modrinth.versionsByHashes(new ArrayList<>(byHash.keySet()));
        } catch (IOException e) {
            Log.warn("Modrinth 哈希查询失败：" + e.getMessage());
            return result;
        }
        Map<String, String> projectOf = new LinkedHashMap<>();
        for (java.util.Map.Entry<String, Modrinth.VersionInfo> e : known.entrySet()) {
            Modrinth.VersionInfo v = e.getValue();
            if (v.projectId.isEmpty()) continue;
            projectOf.put(e.getKey(), v.projectId);
        }
        int total = projectOf.size();
        int done = 0;
        Map<String, Modrinth.VersionInfo> latestCache = new LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> e : projectOf.entrySet()) {
            Meta meta = byHash.get(e.getKey());
            String projectId = e.getValue();
            done++;
            try {
                Modrinth.VersionInfo latest = latestCache.get(projectId);
                if (latest == null) {
                    latest = Modrinth.bestVersion(projectId, mcVersion, loader);
                    if (latest != null) latestCache.put(projectId, latest);
                }
                if (latest == null) continue;
                Update u = new Update();
                u.mod = meta;
                u.projectId = projectId;
                u.latestVersion = latest.versionNumber;
                u.latestName = latest.name;
                Modrinth.FileInfo f = latest.primaryFile();
                if (f != null) {
                    u.downloadUrl = f.url;
                    u.latestFile = f.filename;
                    u.sha1 = f.sha1;
                    u.size = f.size;
                }
                u.newer = !sameVersion(meta.version, latest.versionNumber);
                if (u.newer) result.add(u);
            } catch (IOException ex) {
                Log.warn("检查 " + meta.file + " 更新失败：" + ex.getMessage());
            } finally {
                if (onProgress != null) onProgress.accept(done, total);
            }
        }
        return result;
    }

    /** 版本号宽松比较：忽略大小写与 "v" 前缀，包含关系也算相同。 */
    private static boolean sameVersion(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.trim().toLowerCase().replaceFirst("^v", "");
        String y = b.trim().toLowerCase().replaceFirst("^v", "");
        if (x.isEmpty() || y.isEmpty()) return false;
        return x.equals(y) || x.contains(y) || y.contains(x);
    }

    // ---------------- 冲突检测 ----------------

    public static final class Issue {
        public String level;   // 错误 / 警告
        public String title;
        public String detail;

        public Issue(String level, String title, String detail) {
            this.level = level;
            this.title = title;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return "[" + level + "] " + title + " - " + detail;
        }
    }

    public static List<Issue> checkConflicts(List<Meta> mods) {
        List<Issue> issues = new ArrayList<>();

        // 1. 同一个 modId 装了多份（最常见的是新版旧版同时留在 mods 里）
        Map<String, List<Meta>> byId = new LinkedHashMap<>();
        for (Meta m : mods) {
            if (m.modId == null || Compat.isBlank(m.modId)) continue;
            byId.computeIfAbsent(m.modId.toLowerCase(), k -> new ArrayList<>()).add(m);
        }
        for (java.util.Map.Entry<String, List<Meta>> e : byId.entrySet()) {
            List<Meta> list = e.getValue();
            if (list.size() < 2) continue;
            StringBuilder sb = new StringBuilder();
            for (Meta m : list) sb.append("\n  · ").append(m.file);
            issues.add(new Issue("错误", "重复安装：" + list.get(0).name + "（" + e.getKey() + "）",
                    "同一个模组存在 " + list.size() + " 份文件，游戏可能无法启动，建议只保留一份：" + sb));
        }

        // 2. 混装不同加载器的模组（Fabric 目录里放 Forge 模组之类）
        Set<String> loaders = new HashSet<>();
        for (Meta m : mods) if (m.loader != null && !m.loader.equals("unknown")) loaders.add(m.loader);
        boolean hasFabricFamily = loaders.contains("fabric") || loaders.contains("quilt");
        boolean hasForgeFamily = loaders.contains("forge") || loaders.contains("neoforge");
        if (hasFabricFamily && hasForgeFamily) {
            issues.add(new Issue("错误", "加载器混装",
                    "当前目录里同时有 Fabric/Quilt 与 Forge/NeoForge 模组，这两套加载器不能共存"));
        }

        // 3. 缺失必需依赖
        Set<String> installed = new HashSet<>();
        for (Meta m : mods) {
            if (m.modId != null && !Compat.isBlank(m.modId)) installed.add(m.modId.toLowerCase());
            for (String p : m.provides) {
                if (p != null && !Compat.isBlank(p)) installed.add(p.toLowerCase());
            }
        }
        for (Meta m : mods) {
            List<String> missing = new ArrayList<>();
            for (String dep : m.required) {
                if (!installed.contains(dep.toLowerCase())) missing.add(dep);
            }
            if (!missing.isEmpty()) {
                issues.add(new Issue("警告", "缺少依赖：" + m.title(),
                        m.file + " 需要这些前置模组：" + String.join(", ", missing)));
            }
        }
        return issues;
    }
}
