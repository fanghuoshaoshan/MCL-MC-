package mcl.install;

import mcl.util.Dirs;
import mcl.util.Compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.game.JavaFinder;
import mcl.net.Downloader;
import mcl.net.Http;
import mcl.net.Source;
import mcl.util.Json;
import mcl.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * 按 Mojang 官方 Java 运行时清单安装 JRE（与官方启动器 / HMCL 使用同一数据源），
 * 解决"本机只有 Java 8、玩不了 1.17+"的问题。
 */
public final class JavaInstaller {
    public interface Listener {
        void onStage(String stage);

        void onProgress(int done, int total, long bytes, long totalBytes, String file);
    }

    private static final String ALL_URL =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private JavaInstaller() {}

    /** 确保运行时存在，返回 java(.exe) 路径。 */
    public static Path ensure(Path runtimeDir, String component, int major, Source source, int threads,
                              Listener listener) throws Exception {
        String comp = (component == null || Compat.isBlank(component)) ? "java-" + major : component;
        Path dir = runtimeDir.resolve(comp);
        Path existing = exeOf(dir);
        if (existing != null) {
            // 只有真能跑起来才算"已装好"：下载到一半被关掉会留下半套文件，
            // exeOf 只看文件在不在，直接用它会得到一个永远启动失败的 Java。
            if (JavaFinder.find(existing) != null) {
                Log.info("已有 Java 运行时：" + existing);
                return existing;
            }
            Log.warn("运行时目录不完整（可能是上次下载中断），删除后重装：" + dir);
            deleteTree(dir);
        }

        if (listener != null) listener.onStage("获取 Java 运行时清单");
        String all = Http.getString(source.map(ALL_URL), ALL_URL);
        JsonObject root = JsonParser.parseString(all).getAsJsonObject();
        String platform = platformKey();
        JsonObject comps = Json.obj(root, platform);
        if (comps == null) throw new IOException("Mojang 未提供平台 " + platform + " 的 Java 运行时");

        String chosen = comp;
        JsonArray entries = Json.arr(comps, chosen);
        if (entries == null || entries.isEmpty()) {
            chosen = null;
            for (String key : comps.keySet()) {
                JsonArray arr = Json.arr(comps, key);
                if (arr == null || arr.isEmpty() || !arr.get(0).isJsonObject()) continue;
                String ver = Json.str(Json.obj(arr.get(0).getAsJsonObject(), "version"), "name", "");
                if (major > 0 && JavaFinder.majorOf(ver) == major) {
                    chosen = key;
                    entries = arr;
                    break;
                }
            }
        }
        if (chosen == null || entries == null || entries.isEmpty()) {
            throw new IOException("找不到匹配 Java " + major + " 的运行时组件（" + comp + "）");
        }
        JsonObject first = entries.get(0).getAsJsonObject();
        JsonObject manifest = Json.obj(first, "manifest");
        String manifestUrl = Json.str(manifest, "url", null);
        if (manifestUrl == null) throw new IOException("Java 运行时清单缺少地址");
        String versionName = Json.str(Json.obj(first, "version"), "name", "");
        Log.info("准备安装 Java " + versionName + "（组件 " + chosen + "，平台 " + platform + "）");

        if (listener != null) listener.onStage("读取 Java " + versionName + " 文件清单");
        String manifestJson = Http.getString(source.map(manifestUrl), manifestUrl);
        JsonObject files = Json.obj(JsonParser.parseString(manifestJson).getAsJsonObject(), "files");
        if (files == null) throw new IOException("Java 运行时文件清单为空");

        Downloader dl = new Downloader(source, threads);
        int fileCount = 0;
        for (Map.Entry<String, JsonElement> e : files.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject f = e.getValue().getAsJsonObject();
            if (!"file".equals(Json.str(f, "type", ""))) continue;
            JsonObject raw = Json.obj(Json.obj(f, "downloads"), "raw");
            String url = Json.str(raw, "url", null);
            if (url == null) continue;
            String sha1 = Json.str(raw, "sha1", null);
            long size = Json.lng(raw, "size", 0);
            Path dest = dir.resolve(e.getKey());
            dl.addDirect(e.getKey(), url, dest, sha1, size);
            fileCount++;
        }
        Log.info("Java 运行时共 " + fileCount + " 个文件，大小约 " + (totalSize(files) / 1048576) + " MB");

        if (listener != null) listener.onStage("下载 Java " + versionName + "（约 " + (totalSize(files) / 1048576) + " MB）");
        Downloader.Listener adapter = listener == null ? null : new Downloader.Listener() {
            @Override
            public void onProgress(int done, int total, long bytes, long bytesTotal, String current) {
                listener.onProgress(done, total, bytes, bytesTotal, current);
            }
        };
        Dirs.ensure(dir);
        int failed = dl.run(adapter);
        if (failed > 0) throw new IOException("有 " + failed + " 个 Java 运行时文件下载失败");

        if (!isWindows()) {
            Path bin = dir.resolve("bin");
            if (Files.isDirectory(bin)) {
                try (java.util.stream.Stream<Path> stream = Files.list(bin)) {
                    stream.forEach(p -> p.toFile().setExecutable(true, false));
                }
            }
        }
        Path exe = exeOf(dir);
        if (exe == null) throw new IOException("Java 运行时安装后未找到可执行文件：" + dir);
        Log.info("Java 运行时安装完成：" + exe);
        if (listener != null) listener.onStage("Java 运行时就绪");
        return exe;
    }

    private static long totalSize(JsonObject files) {
        long total = 0;
        for (Map.Entry<String, JsonElement> e : files.entrySet()) {
            JsonObject f = e.getValue().getAsJsonObject();
            JsonObject raw = Json.obj(Json.obj(f, "downloads"), "raw");
            total += Json.lng(raw, "size", 0);
        }
        return total;
    }

    /** 递归删除（下载中断留下的残骸、重新安装前清理）。 */
    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            Log.warn("清理目录失败：" + dir + " -> " + e.getMessage());
        }
    }

    public static Path exeOf(Path dir) {
        String[] names = isWindows()
                ? new String[]{"bin/java.exe", "bin/javaw.exe", "jre/bin/java.exe"}
                : new String[]{"bin/java", "jre/bin/java"};
        for (String n : names) {
            Path p = dir.resolve(n);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("arm") || arch.contains("aarch64");
        boolean x64 = arch.contains("64");
        if (os.contains("win")) return arm ? "windows-arm64" : (x64 ? "windows-x64" : "windows-x86");
        if (os.contains("mac")) return arm ? "mac-os-arm64" : "mac-os";
        return x64 ? "linux" : "linux-i386";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
