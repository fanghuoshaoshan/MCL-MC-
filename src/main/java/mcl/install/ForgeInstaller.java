package mcl.install;

import mcl.util.Dirs;
import mcl.util.Compat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.meta.Library;
import mcl.net.Downloader;
import mcl.net.Http;
import mcl.net.Source;
import mcl.util.Digest;
import mcl.util.Json;
import mcl.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Forge / NeoForge 安装：解析官方 installer.jar 的 install_profile.json，
 * 下载依赖库，然后按顺序执行 processors（jarsplitter、ForgeAutoRenamingTool、binarypatcher 等）。
 *
 * <p>与现代 Forge 安装器行为一致：安装完成后版本目录里只有版本 JSON，
 * 打过补丁的客户端 jar 落在 libraries/net/minecraftforge/forge/&lt;ver&gt;/ 下，由 BootstrapLauncher 自行加载。
 */
public final class ForgeInstaller {
    public static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    public static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/";

    /** 安装阶段额外回调（执行 processors 时用来显示详细进度）。 */
    public interface Listener extends Installer.Listener {
    }

    private static final Pattern VAR = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private ForgeInstaller() {}

    // ---------------------------------------------------------------- 版本查询

    public static class Loader {
        public String version = "";
        public String mcVersion = "";
        public boolean recommended;
        public String label = "";

        @Override
        public String toString() {
            return label.isEmpty() ? version : label;
        }
    }

    /** 查询某个原版版本可用的 Forge / NeoForge 版本（新的在前）。 */
    public static List<Loader> loaders(String mcVersion, boolean neoforge) throws IOException {
        List<Loader> result = new ArrayList<>();
        if (neoforge) {
            String prefix = neoForgePrefix(mcVersion);
            if (prefix != null) {
                try {
                    String xml = Http.getString(NEOFORGE_MAVEN + "net/neoforged/neoforge/maven-metadata.xml");
                    for (String v : versionsOf(xml)) {
                        if (!v.startsWith(prefix + ".")) continue;
                        result.add(loader(mcVersion, v, false));
                    }
                } catch (IOException e) {
                    Log.warn("读取 NeoForge 版本清单失败：" + e.getMessage());
                }
            }
            // 1.20.1 及更早的 NeoForge 走 net.neoforged:forge
            try {
                String xml = Http.getString(NEOFORGE_MAVEN + "net/neoforged/forge/maven-metadata.xml");
                for (String v : versionsOf(xml)) {
                    if (!v.startsWith(mcVersion + "-")) continue;
                    result.add(loader(mcVersion, v.substring(mcVersion.length() + 1), false));
                }
            } catch (IOException ignored) {
            }
            if (result.isEmpty()) throw new IOException("没有找到 " + mcVersion + " 可用的 NeoForge 版本");
            result.sort(Comparator.comparing((Loader l) -> l.version, ForgeInstaller::compareVersion).reversed());
            return result;
        }

        Set<String> recommended = new LinkedHashSet<>();
        try {
            JsonObject promos = JsonParser.parseString(
                    Http.getString("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"))
                    .getAsJsonObject();
            JsonObject map = Json.obj(promos, "promos");
            if (map != null) {
                for (String key : map.keySet()) {
                    if (!key.equals(mcVersion + "-recommended")) continue;
                    recommended.add(map.get(key).getAsString());
                }
            }
        } catch (Exception e) {
            Log.warn("读取 Forge 推荐版本失败：" + e.getMessage());
        }
        String xml = Http.getString(FORGE_MAVEN + "net/minecraftforge/forge/maven-metadata.xml");
        for (String v : versionsOf(xml)) {
            if (!v.startsWith(mcVersion + "-")) continue;
            String lv = v.substring(mcVersion.length() + 1);
            Loader l = loader(mcVersion, lv, recommended.contains(lv));
            result.add(l);
        }
        if (result.isEmpty()) throw new IOException("没有找到 " + mcVersion + " 可用的 Forge 版本");
        cleanFakeVersions(result);
        result.sort(Comparator.comparing((Loader l) -> l.version, ForgeInstaller::compareVersion).reversed());
        // 推荐版排在同类第一个位置，方便默认选中
        result.sort(Comparator.comparing((Loader l) -> !l.recommended));
        return result;
    }

    private static Loader loader(String mc, String loaderVersion, boolean recommended) {
        Loader l = new Loader();
        l.version = loaderVersion;
        l.mcVersion = mc;
        l.recommended = recommended;
        l.label = mc + " · " + loaderVersion + (recommended ? "（推荐）" : "");
        return l;
    }

    /** maven-metadata.xml 里偶有 1.20.2-48.1.0 之类的正常版本，也有纯 mc 版本号，过滤掉异常项。 */
    private static void cleanFakeVersions(List<Loader> list) {
        list.removeIf(l -> l.version.isEmpty() || !Character.isDigit(l.version.charAt(0)));
    }

    private static List<String> versionsOf(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }

    /** NeoForge 版本号与 MC 版本对应：1.20.2 -> 20.2，1.21 -> 21.0。 */
    private static String neoForgePrefix(String mc) {
        String[] p = mc.split("\\.");
        if (p.length < 2) return null;
        String minor = p[1];
        String patch = p.length > 2 ? p[2] : "0";
        return minor + "." + patch;
    }

    /** 版本号比较：按 . 和 - 分段，数字按数值比，其余按字符串比。 */
    static int compareVersion(String a, String b) {
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            Integer ix = asInt(x);
            Integer iy = asInt(y);
            int c;
            if (ix != null && iy != null) c = Integer.compare(ix, iy);
            else c = x.compareToIgnoreCase(y);
            if (c != 0) return c;
        }
        return 0;
    }

    private static Integer asInt(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 安装 Forge / NeoForge。
     *
     * @param gameDir   .minecraft 目录
     * @param cacheDir  缓存目录（存放 installer.jar 与解压出来的 data 文件）
     * @return 安装后的版本 id
     */
    public static String install(Path gameDir, Path cacheDir, Source source, int threads,
                                 String mcVersion, String loaderVersion, boolean neoforge,
                                 Installer.Listener listener) throws Exception {
        Path versionsDir = gameDir.resolve("versions");
        Path librariesDir = gameDir.resolve("libraries");
        Dirs.ensure(versionsDir);
        Dirs.ensure(librariesDir);
        Dirs.ensure(cacheDir);

        String group = neoforge ? "net.neoforged" : "net.minecraftforge";
        String artifact = neoforge ? "neoforge" : "forge";
        String fullVersion = neoforge ? loaderVersion : mcVersion + "-" + loaderVersion;
        String base = neoforge ? NEOFORGE_MAVEN : FORGE_MAVEN;
        String url = base + group.replace('.', '/') + "/" + artifact + "/" + fullVersion + "/"
                + artifact + "-" + fullVersion + "-installer.jar";

        Path installerJar = cacheDir.resolve(artifact + "-" + fullVersion + "-installer.jar");
        if (!Files.isRegularFile(installerJar) || Files.size(installerJar) < 100_000) {
            listener.onStage("下载 " + (neoforge ? "NeoForge" : "Forge") + " 安装器 " + fullVersion);
            IOException last = null;
            for (String u : new String[]{source.map(url), url}) {
                for (int i = 0; i < 2; i++) {
                    try {
                        Files.deleteIfExists(installerJar);
                        Http.download(u, installerJar, null, 0, null);
                        last = null;
                        break;
                    } catch (IOException e) {
                        last = e;
                        Log.warn("安装器下载失败：" + u + " -> " + e.getMessage());
                    }
                }
                if (last == null) break;
            }
            if (last != null) throw last;
        }

        try (ZipFile zf = new ZipFile(installerJar.toFile())) {
            String profileJson = readEntry(zf, "install_profile.json");
            if (profileJson == null) throw new IOException("安装器内没有 install_profile.json，可能不是官方安装器");
            JsonObject profile = JsonParser.parseString(profileJson).getAsJsonObject();

            boolean oldFormat = Json.obj(profile, "install") != null && !profile.has("processors");
            JsonObject versionJson;
            String versionId;
            if (oldFormat) {
                JsonObject versionInfo = Json.obj(profile, "versionInfo");
                if (versionInfo == null) throw new IOException("安装器 profile 缺少 versionInfo");
                versionJson = versionInfo;
                versionId = Json.str(versionInfo, "id", mcVersion + "-" + artifact + "-" + loaderVersion);
            } else {
                String entry = Json.str(profile, "json", "/version.json");
                if (entry.startsWith("/")) entry = entry.substring(1);
                String body = readEntry(zf, entry);
                if (body == null) throw new IOException("安装器内缺少版本描述 " + entry);
                versionJson = JsonParser.parseString(body).getAsJsonObject();
                versionId = Json.str(versionJson, "id", Json.str(profile, "version", ""));
            }
            if (versionId.isEmpty()) throw new IOException("无法确定要安装的版本 id");

            listener.onStage("准备 " + versionId + " 的依赖库");
            Downloader dl = new Downloader(source, threads);
            Map<String, String> embedded = new LinkedHashMap<>();
            // 从 Forge 61.x 起，profile 会把「打完补丁的 client.jar」也列进 libraries，
            // 但它的 artifact.url 是空串 —— 这不是要下载的依赖，而是 processors 的产物
            // （{PATCHED}）。不排掉就会拿 maven 地址去撞 404：
            // forge-<mc>-<ver>-client.jar 在 maven 上根本不存在。
            Set<Path> generated = oldFormat
                    ? Collections.<Path>emptySet()
                    : processorOutputs(profile, processorVars(profile, gameDir, librariesDir, cacheDir,
                            mcVersion, installerJar, zf));
            addLibraries(dl, Json.arr(profile, "libraries"), base, librariesDir, embedded, generated);
            addLibraries(dl, Json.arr(versionJson, "libraries"), base, librariesDir, embedded, generated);

            // 老格式：forge universal jar 直接塞在安装器里，同时 install.libraries 里还有一批
            String legacyUniversal = null;
            JsonObject install = Json.obj(profile, "install");
            if (oldFormat && install != null) {
                addLibraries(dl, Json.arr(install, "libraries"), base, librariesDir, embedded, generated);
                String coord = Json.str(install, "path", "");
                String fileName = Json.str(install, "filePath", "");
                if (!coord.isEmpty() && !fileName.isEmpty()) {
                    Path target = librariesDir.resolve(Library.mavenPath(coord)).getParent().resolve(fileName);
                    legacyUniversal = target.toString();
                    extractEntry(zf, fileName, target);
                    Log.info("已释放 " + fileName);
                }
            }

            if (dl.size() > 0) {
                int failed = dl.run(listener);
                if (failed > 0) throw new IOException("有 " + failed + " 个依赖库下载失败，请切换下载源后重试");
            }
            extractEmbedded(zf, embedded, librariesDir, source, threads, listener);

            if (!oldFormat) {
                runProcessors(zf, profile, gameDir, librariesDir, cacheDir, mcVersion, installerJar,
                        listener);
            }

            // 最后写版本 JSON：只有前面全部成功才落盘，避免留下半成品
            Path vdir = versionsDir.resolve(versionId);
            Dirs.ensure(vdir);
            Path jsonFile = vdir.resolve(versionId + ".json");
            Compat.writeString(jsonFile, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                    .create().toJson(versionJson));
            listener.onStage("安装完成 " + versionId);
            Log.info((neoforge ? "NeoForge" : "Forge") + " 安装完成：" + versionId);
            if (legacyUniversal != null) Log.info("通用 jar：" + legacyUniversal);
            return versionId;
        }
    }

    private static void addLibraries(Downloader dl, JsonArray libs, String defaultBase, Path librariesDir,
                                     Map<String, String> embedded, Set<Path> generated) {
        if (libs == null) return;
        Map<String, Boolean> features = Compat.mapOf("has_custom_resolution", true);
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement e : libs) {
            if (!e.isJsonObject()) continue;
            Library lib;
            try {
                lib = Library.parse(e.getAsJsonObject());
            } catch (Exception ex) {
                continue;
            }
            if (lib.name == null || lib.name.isEmpty()) continue;
            if (!lib.allowed(features)) continue;
            if (!seen.add(lib.name)) continue;
            if (lib.artifact == null && lib.mavenBase == null) continue;  // 只有分类器、没有主 jar
            String path = lib.artifactPath();
            // 处理器自己会生成的产物（如新版 Forge 的 client.jar）：不下载、不解压
            if (generated != null && !generated.isEmpty()
                    && generated.contains(librariesDir.resolve(path).toAbsolutePath().normalize())) {
                Log.info("跳过由安装步骤生成的库 " + path);
                continue;
            }
            String u = lib.artifactUrl();
            if (lib.artifact == null || lib.artifact.url == null) {
                String b = lib.mavenBase != null ? lib.mavenBase : defaultBase;
                if (!b.endsWith("/")) b = b + "/";
                u = b + path;
            } else if (Compat.isBlank(lib.artifact.url)) {
                // 1.12.2 这类安装器把 forge jar 直接放在 jar 内的 maven/ 下，URL 是空的
                String b = defaultBase;
                if (!b.endsWith("/")) b = b + "/";
                embedded.put(path, b + path);
                continue;
            }
            dl.add(path, u, librariesDir.resolve(path), lib.sha1(), lib.size());
        }
    }

    // ---------------------------------------------------------------- processors

    /** 处理器可见的变量表：内置的几个 + 安装器 data 里按 client 侧解析出的值。 */
    private static Map<String, String> processorVars(JsonObject profile, Path gameDir, Path librariesDir,
                                                     Path cacheDir, String mcVersion, Path installerJar,
                                                     ZipFile zf) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("SIDE", "client");
        all.put("MINECRAFT_JAR", gameDir.resolve("versions").resolve(mcVersion)
                .resolve(mcVersion + ".jar").toAbsolutePath().toString());
        all.put("MINECRAFT_VERSION", mcVersion);
        all.put("ROOT", gameDir.toAbsolutePath().toString());
        all.put("INSTALLER", installerJar.toAbsolutePath().toString());
        all.put("LIBRARY_DIR", librariesDir.toAbsolutePath().toString());
        all.putAll(resolveData(Json.obj(profile, "data"), librariesDir, zf, cacheDir.resolve("data")));
        return all;
    }

    /**
     * processors 的 outputs 里声明会生成哪些文件（key 是 {VAR} 形式，求值后是绝对路径）。
     * 这些文件必须由安装步骤产出来，既不该下载也不该从安装器里解压。
     */
    private static Set<Path> processorOutputs(JsonObject profile, Map<String, String> vars) {
        Set<Path> out = new LinkedHashSet<>();
        JsonArray processors = Json.arr(profile, "processors");
        if (processors == null) return out;
        for (JsonElement el : processors) {
            if (!el.isJsonObject()) continue;
            JsonObject outputs = Json.obj(el.getAsJsonObject(), "outputs");
            if (outputs == null) continue;
            for (String key : outputs.keySet()) {
                String p = replaceVars(key, vars);
                if (Compat.isBlank(p)) continue;
                try {
                    out.add(java.nio.file.Paths.get(p).toAbsolutePath().normalize());
                } catch (Exception ignored) {
                    // 变量没求值出来（形如 "{X}"）时忽略，不影响其余条目
                }
            }
        }
        return out;
    }

    private static void runProcessors(ZipFile zf, JsonObject profile, Path gameDir, Path librariesDir,
                                      Path cacheDir, String mcVersion, Path installerJar,
                                      Installer.Listener listener) throws Exception {
        JsonArray processors = Json.arr(profile, "processors");
        if (processors == null || processors.isEmpty()) return;

        Map<String, String> all = processorVars(profile, gameDir, librariesDir, cacheDir, mcVersion,
                installerJar, zf);

        Path javaBin = javaExe();
        int index = 0;
        for (JsonElement el : processors) {
            index++;
            if (!el.isJsonObject()) continue;
            JsonObject p = el.getAsJsonObject();
            if (!runsOnClient(p)) continue;
            if (outputsValid(p, all)) {
                Log.info("处理器 " + index + " 已是最新，跳过");
                continue;
            }
            String jarCoord = Json.str(p, "jar", "");
            Path jarPath = librariesDir.resolve(Library.mavenPath(jarCoord));
            if (!Files.isRegularFile(jarPath)) throw new IOException("缺少处理器依赖：" + jarPath);
            String mainClass = mainClassOf(jarPath);
            if (mainClass == null || mainClass.isEmpty()) {
                throw new IOException("处理器 " + jarPath.getFileName() + " 没有 Main-Class");
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin.toString());
            cmd.add("-Xmx1G");
            cmd.add("-cp");
            List<String> cp = new ArrayList<>();
            cp.add(jarPath.toAbsolutePath().toString());
            JsonArray cpArr = Json.arr(p, "classpath");
            if (cpArr != null) {
                Set<String> added = new LinkedHashSet<>();
                for (JsonElement c : cpArr) {
                    if (!c.isJsonPrimitive()) continue;
                    Path path = librariesDir.resolve(Library.mavenPath(c.getAsString()));
                    if (Files.isRegularFile(path) && added.add(path.toString())) {
                        cp.add(path.toAbsolutePath().toString());
                    }
                }
            }
            cmd.add(String.join(java.io.File.pathSeparator, cp));
            cmd.add(mainClass);

            List<String> args = new ArrayList<>();
            JsonArray argsArr = Json.arr(p, "args");
            if (argsArr != null) {
                for (JsonElement a : argsArr) {
                    if (a.isJsonPrimitive()) args.add(resolveArg(a.getAsString(), all, librariesDir));
                }
            }
            cmd.addAll(args);

            listener.onStage("执行安装步骤 " + index + "/" + processors.size() + "：" + mainClass);
            Log.info("处理器 " + index + "：" + mainClass);
            runProcess(cmd, gameDir, index, mainClass);
        }
    }

    /** 把安装器 jar 内 maven/ 目录下的库释放到 libraries（1.12.2 的 forge jar 就是这样给的）。 */
    private static void extractEmbedded(ZipFile zf, Map<String, String> embedded, Path librariesDir,
                                        Source source, int threads, Installer.Listener listener)
            throws Exception {
        if (embedded.isEmpty()) return;
        listener.onStage("释放安装器内置依赖（" + embedded.size() + " 个）");
        Downloader missing = new Downloader(source, threads);
        for (Map.Entry<String, String> e : embedded.entrySet()) {
            Path dest = librariesDir.resolve(e.getKey());
            if (Files.isRegularFile(dest)) continue;
            String entry = "maven/" + e.getKey().replace('\\', '/');
            if (zf.getEntry(entry) != null) {
                extractEntry(zf, entry, dest);
                Log.info("已释放内置库 " + e.getKey());
            } else {
                missing.add(e.getKey(), e.getValue(), dest, null, 0);
            }
        }
        if (missing.size() > 0) {
            int failed = missing.run(listener);
            if (failed > 0) throw new IOException("有 " + failed + " 个内置依赖下载失败");
        }
    }

    private static boolean runsOnClient(JsonObject p) {
        JsonArray sides = Json.arr(p, "sides");
        if (sides == null || sides.isEmpty()) return true;
        for (JsonElement s : sides) if (s.isJsonPrimitive() && "client".equals(s.getAsString())) return true;
        return false;
    }

    private static boolean outputsValid(JsonObject p, Map<String, String> data) {
        JsonObject outputs = Json.obj(p, "outputs");
        if (outputs == null || outputs.size() == 0) return false;
        for (Map.Entry<String, JsonElement> e : outputs.entrySet()) {
            if (!e.getValue().isJsonPrimitive()) return false;
            String outPath = replaceVars(e.getKey(), data);
            String sha = replaceVars(e.getValue().getAsString(), data);
            Path file = java.nio.file.Paths.get(outPath);
            if (!Files.isRegularFile(file)) return false;
            if (sha != null && !Compat.isBlank(sha) && !sha.equalsIgnoreCase(Digest.sha1(file))) return false;
        }
        return true;
    }

    private static void runProcess(List<String> cmd, Path workDir, int index, String name)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        Deque<String> tail = new ArrayDeque<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Compat.isBlank(line)) continue;
                tail.addLast(line);
                if (tail.size() > 30) tail.removeFirst();
            }
        }
        int code = process.waitFor();
        if (code != 0) {
            StringBuilder sb = new StringBuilder("安装步骤 " + index + "（" + name + "）失败，退出码 " + code);
            for (String l : tail) sb.append("\n  ").append(l);
            throw new IOException(sb.toString());
        }
    }

    private static String mainClassOf(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf == null) return null;
            return mf.getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            return null;
        }
    }

    private static Path javaExe() throws IOException {
        String home = System.getProperty("java.home");
        if (home == null) throw new IOException("找不到当前 Java 运行时");
        Path dir = java.nio.file.Paths.get(home, "bin");
        for (String name : new String[]{"java.exe", "java"}) {
            Path p = dir.resolve(name);
            if (Files.isRegularFile(p)) return p;
        }
        throw new IOException("找不到 java 可执行文件：" + dir);
    }

    // ---------------------------------------------------------------- data 解析

    private static Map<String, String> resolveData(JsonObject dataJson, Path librariesDir, ZipFile zf,
                                                   Path extractDir) {
        Map<String, String> out = new HashMap<>();
        if (dataJson == null) return out;
        for (String key : dataJson.keySet()) {
            JsonObject sideValues = Json.obj(dataJson, key);
            if (sideValues == null) continue;
            String raw = Json.str(sideValues, "client", null);
            if (raw == null) continue;
            out.put(key, resolveValue(raw, librariesDir, zf, extractDir));
        }
        return out;
    }

    private static String resolveValue(String raw, Path librariesDir, ZipFile zf, Path extractDir) {
        String v = raw.trim();
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1);
        }
        if (v.length() >= 2 && v.startsWith("[") && v.endsWith("]")) {
            String coord = v.substring(1, v.length() - 1);
            return librariesDir.resolve(Library.mavenPath(coord)).toAbsolutePath().toString();
        }
        if (v.startsWith("/")) {
            String entry = v.substring(1);
            Path target = extractDir.resolve(entry);
            try {
                extractEntry(zf, entry, target);
            } catch (IOException e) {
                Log.warn("释放安装器文件失败：" + entry + " -> " + e.getMessage());
            }
            return target.toAbsolutePath().toString();
        }
        return v;
    }

    /**
     * 处理器参数求值：{VAR} 换成数据表里的值，[maven:坐标] 换成库文件绝对路径，
     * 'literal' 去掉引号（与 Forge 安装器的 processArgs 行为一致）。
     */
    private static String resolveArg(String arg, Map<String, String> vars, Path librariesDir) {
        String s = replaceVars(arg, vars);
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) return t.substring(1, t.length() - 1);
        if (t.length() >= 2 && t.startsWith("[") && t.endsWith("]")) {
            String coord = t.substring(1, t.length() - 1);
            return librariesDir.resolve(Library.mavenPath(coord)).toAbsolutePath().toString();
        }
        return s;
    }

    private static String replaceVars(String s, Map<String, String> vars) {
        if (s == null || s.indexOf('{') < 0) return s;
        Matcher m = VAR.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String value = vars.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- zip 工具

    private static String readEntry(ZipFile zf, String name) throws IOException {
        ZipEntry entry = zf.getEntry(name);
        if (entry == null) return null;
        try (InputStream in = zf.getInputStream(entry)) {
            return new String(Compat.readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void extractEntry(ZipFile zf, String name, Path target) throws IOException {
        ZipEntry entry = zf.getEntry(name);
        if (entry == null) throw new IOException("安装器内缺少 " + name);
        Dirs.ensure(target.toAbsolutePath().getParent());
        try (InputStream in = zf.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
