package mcl.install;

import mcl.util.Dirs;
import mcl.util.Compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.meta.Library;
import mcl.meta.Rules;
import mcl.meta.VersionMeta;
import mcl.net.Downloader;
import mcl.net.Http;
import mcl.net.Source;
import mcl.util.Json;
import mcl.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 安装一个版本：元数据、客户端、依赖库、natives、素材、日志配置。 */
public class Installer {

    public interface Listener extends Downloader.Listener {
        void onStage(String stage);
    }

    private final Path gameDir;
    private final Path versionsDir;
    private final Path librariesDir;
    private final Path assetsDir;
    private final Source source;
    private final int threads;

    private final List<Path> nativeJars = new ArrayList<>();
    private final Set<String> nativeExcludes = new HashSet<>();
    private VersionMeta meta;

    public Installer(Path gameDir, Source source, int threads) {
        this.gameDir = gameDir;
        this.versionsDir = gameDir.resolve("versions");
        this.librariesDir = gameDir.resolve("libraries");
        this.assetsDir = gameDir.resolve("assets");
        this.source = source;
        this.threads = threads;
    }

    public Path versionDir(String id) {
        return versionsDir.resolve(id);
    }

    public Path clientJar(String id) {
        return versionDir(id).resolve(id + ".jar");
    }

    public Path nativesDir(String id) {
        return versionDir(id).resolve("natives");
    }

    /** 返回本次安装使用的客户端 jar 路径。 */
    public Path install(VersionMeta meta, boolean withAssets, Listener listener) throws Exception {
        this.meta = meta;
        final Path vdir = versionDir(meta.id);
        Dirs.ensure(vdir);
        listener.onStage("写入版本信息 " + meta.id);
        Compat.writeString(vdir.resolve(meta.id + ".json"), meta.rawJson);

        Downloader dl = new Downloader(source, threads);

        // 1. 客户端核心 jar
        Path jar = clientJar(meta.id);
        if (meta.clientUrl != null) {
            listener.onStage("准备下载客户端核心");
            dl.add(meta.id + ".jar", meta.clientUrl, jar, meta.clientSha1, meta.clientSize);
        }

        // 2. 依赖库
        listener.onStage("解析依赖库");
        Map<String, Boolean> features = Compat.mapOf("has_custom_resolution", true);
        Set<String> seen = new LinkedHashSet<>();
        int libCount = 0;
        for (Library lib : meta.libraries) {
            if (!lib.allowed(features)) continue;
            // 有些库（如 jinput-platform）只有 natives 分类器、没有主 jar，不能按坐标硬拼地址去下
            boolean hasMainJar = lib.artifact != null && lib.artifact.url != null;
            boolean hasMavenBase = lib.mavenBase != null;   // 老版本 JSON 只给 url
            String path = lib.artifactPath();
            if ((hasMainJar || hasMavenBase) && seen.add(path)) {
                Path dest = librariesDir.resolve(path);
                dl.add(path, lib.artifactUrl(), dest, lib.sha1(), lib.size());
                libCount++;
            }
            String classifier = lib.nativeClassifier();
            if (classifier != null) {
                Library.DownloadInfo di = lib.classifiers.get(classifier);
                if (di != null) {
                    String cp = di.path != null ? di.path : Library.mavenPath(lib.name, classifier);
                    Path dest = librariesDir.resolve(cp);
                    dl.add(cp, di.url != null ? di.url : lib.artifactUrl(), dest, di.sha1, di.size);
                    if (!nativeJars.contains(dest)) nativeJars.add(dest);
                }
            }
            nativeExcludes.addAll(lib.extractExclude);
        }
        Log.info("版本 " + meta.id + " 需要 " + libCount + " 个依赖库，natives " + nativeJars.size() + " 个");

        // 3. 素材索引
        JsonObject assetObjects = null;
        String assetIndexId = meta.assetIndexId;
        if (withAssets && meta.assetIndexUrl != null && assetIndexId != null) {
            listener.onStage("下载素材索引");
            Path indexFile = assetsDir.resolve("indexes").resolve(assetIndexId + ".json");
            downloadIndex(meta.assetIndexUrl, indexFile, meta.assetIndexSha1, meta.assetIndexSize);
            assetObjects = JsonParser.parseString(Compat.readString(indexFile))
                    .getAsJsonObject();
        }

        // 4. 日志配置
        Path logFile = null;
        if (meta.logConfigUrl != null && meta.logConfigId != null) {
            logFile = assetsDir.resolve("log_configs").resolve(meta.logConfigId);
            dl.add(meta.logConfigId, meta.logConfigUrl, logFile, meta.logConfigSha1, meta.logConfigSize);
        }

        // 5. 执行下载
        listener.onStage("开始下载（" + dl.size() + " 个文件）");
        int failed = dl.run(listener);
        if (failed > 0) {
            throw new IOException("有 " + failed + " 个文件下载失败，请检查网络或切换下载源后重试");
        }

        // 6. 素材对象
        if (assetObjects != null) {
            installAssets(assetObjects, assetIndexId, listener);
        }

        // 7. 解压 natives
        listener.onStage("解压本地库");
        extractNatives(nativesDir(meta.id));

        listener.onStage("安装完成");
        return jar;
    }

    private void downloadIndex(String url, Path dest, String sha1, long size) throws IOException {
        if (Files.isRegularFile(dest)) return;
        IOException last = null;
        String[] candidates = source.available() ? new String[]{source.map(url), url} : new String[]{url};
        for (String u : candidates) {
            for (int i = 0; i < 3; i++) {
                try {
                    Http.download(u, dest, sha1, size, null);
                    return;
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        throw last != null ? last : new IOException("素材索引下载失败");
    }

    private void installAssets(JsonObject index, String indexId, Listener listener) throws Exception {
        JsonObject objects = Json.obj(index, "objects");
        if (objects == null) return;
        boolean legacy = indexId == null || indexId.equals("legacy") || indexId.equals("pre-1.6");
        listener.onStage("准备素材（" + objects.size() + " 个对象）");
        Downloader dl = new Downloader(source, threads);
        Set<String> hashes = new HashSet<>();
        for (Map.Entry<String, JsonElement> e : objects.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject obj = e.getValue().getAsJsonObject();
            String hash = Json.str(obj, "hash", null);
            long size = Json.lng(obj, "size", 0);
            if (hash == null || hash.length() < 4) continue;
            if (!hashes.add(hash)) continue;
            String path = hash.substring(0, 2) + "/" + hash;
            dl.add("assets/" + path, "https://resources.download.minecraft.net/" + path,
                    assetsDir.resolve("objects").resolve(path), hash, size);
        }
        int failed = dl.run(listener);
        if (failed > 0) throw new IOException("有 " + failed + " 个素材文件下载失败");

        if (legacy) {
            listener.onStage("映射旧版素材目录");
            Path virtual = assetsDir.resolve("virtual").resolve(indexId == null ? "legacy" : indexId);
            Dirs.ensure(virtual);
            boolean mapToResources = "pre-1.6".equals(indexId);
            for (Map.Entry<String, JsonElement> e : objects.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                String hash = Json.str(e.getValue().getAsJsonObject(), "hash", null);
                if (hash == null) continue;
                Path src = assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
                if (!Files.isRegularFile(src)) continue;
                Path dst = virtual.resolve(e.getKey());
                if (dst.getParent() != null) Dirs.ensure(dst.getParent());
                if (!Files.exists(dst)) Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                if (mapToResources) {
                    Path res = gameDir.resolve("resources").resolve(e.getKey());
                    if (res.getParent() != null) Dirs.ensure(res.getParent());
                    if (!Files.exists(res)) Files.copy(src, res, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void extractNatives(Path nativesDir) throws IOException {
        extractNativeJars(nativeJars, nativeExcludes, nativesDir);
    }

    /**
     * 启动前补齐 natives：继承版本（Fabric/Forge/NeoForge）自己的版本目录里没有 natives，
     * 需要按合并后的依赖库重新解压一次。
     */
    public static void ensureNatives(Path gameDir, VersionMeta meta, Path nativesDir,
                                     Map<String, Boolean> features) throws IOException {
        if (Files.isDirectory(nativesDir)) {
            try (java.util.stream.Stream<Path> stream = Files.list(nativesDir)) {
                if (stream.findAny().isPresent()) return;
            }
        }
        List<Path> jars = new ArrayList<>();
        Set<String> excludes = new HashSet<>();
        Path libraries = gameDir.resolve("libraries");
        for (Library lib : meta.libraries) {
            if (!lib.allowed(features)) continue;
            String classifier = lib.nativeClassifier();
            if (classifier == null) continue;
            Library.DownloadInfo di = lib.classifiers.get(classifier);
            String path = di != null && di.path != null ? di.path : Library.mavenPath(lib.name, classifier);
            Path p = libraries.resolve(path);
            if (Files.isRegularFile(p)) jars.add(p);
            excludes.addAll(lib.extractExclude);
        }
        if (jars.isEmpty()) return;
        extractNativeJars(jars, excludes, nativesDir);
    }

    private static void extractNativeJars(List<Path> jars, Set<String> excludes, Path nativesDir)
            throws IOException {
        if (jars.isEmpty()) return;
        Dirs.ensure(nativesDir);
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) continue;
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) continue;
                    String name = ze.getName();
                    if (name.startsWith("META-INF/")) continue;
                    boolean skip = false;
                    for (String ex : excludes) {
                        if (!ex.isEmpty() && name.startsWith(ex)) {
                            skip = true;
                            break;
                        }
                    }
                    if (skip) continue;
                    Path out = nativesDir.resolve(name);
                    if (out.getParent() != null) Dirs.ensure(out.getParent());
                    try (InputStream in = zf.getInputStream(ze)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /** 删除整个版本目录。 */
    public static void delete(Path gameDir, String id) throws IOException {
        Path dir = gameDir.resolve("versions").resolve(id);
        if (!Files.isDirectory(dir)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            List<Path> paths = stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).collect(java.util.stream.Collectors.toList());
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }

    public static List<String> installedVersions(Path gameDir) {
        List<String> result = new ArrayList<>();
        Path versions = gameDir.resolve("versions");
        if (!Files.isDirectory(versions)) return result;
        try (java.util.stream.Stream<Path> stream = Files.list(versions)) {
            stream.filter(Files::isDirectory).forEach(d -> {
                Path json = d.resolve(d.getFileName().toString() + ".json");
                if (Files.isRegularFile(json)) result.add(d.getFileName().toString());
            });
        } catch (IOException e) {
            Log.warn("读取版本目录失败：" + e.getMessage());
        }
        result.sort(String.CASE_INSENSITIVE_ORDER.reversed());
        return result;
    }
}
