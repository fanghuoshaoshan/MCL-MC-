package mcl.meta;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mcl.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 版本 JSON 中的一个依赖库。 */
public class Library {
    /** 单个可下载文件。 */
    public static class DownloadInfo {
        public String path;
        public String url;
        public String sha1;
        public long size;
    }

    public String name;
    public JsonArray rules;
    public DownloadInfo artifact;
    public final Map<String, DownloadInfo> classifiers = new LinkedHashMap<>();
    public final Map<String, String> natives = new LinkedHashMap<>();
    public final List<String> extractExclude = new ArrayList<>();
    public String mavenBase;   // 老版本库只有 url 字段
    public String ext = "jar";

    public static Library parse(JsonObject o) {
        Library lib = new Library();
        lib.name = Json.str(o, "name", "");
        lib.rules = Json.arr(o, "rules");
        lib.mavenBase = Json.str(o, "url", null);

        JsonObject downloads = Json.obj(o, "downloads");
        JsonObject art = Json.obj(downloads, "artifact");
        if (art != null) {
            lib.artifact = new DownloadInfo();
            lib.artifact.path = Json.str(art, "path", null);
            lib.artifact.url = Json.str(art, "url", null);
            lib.artifact.sha1 = Json.str(art, "sha1", null);
            lib.artifact.size = Json.lng(art, "size", 0);
        }
        JsonObject cls = Json.obj(downloads, "classifiers");
        if (cls != null) {
            for (Map.Entry<String, JsonElement> e : cls.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject c = e.getValue().getAsJsonObject();
                DownloadInfo di = new DownloadInfo();
                di.path = Json.str(c, "path", null);
                di.url = Json.str(c, "url", null);
                di.sha1 = Json.str(c, "sha1", null);
                di.size = Json.lng(c, "size", 0);
                lib.classifiers.put(e.getKey(), di);
            }
        }
        JsonObject natives = Json.obj(o, "natives");
        if (natives != null) {
            for (Map.Entry<String, JsonElement> e : natives.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    lib.natives.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        JsonObject extract = Json.obj(o, "extract");
        JsonArray exclude = Json.arr(extract, "exclude");
        if (exclude != null) {
            for (JsonElement e : exclude) if (e.isJsonPrimitive()) lib.extractExclude.add(e.getAsString());
        }
        if (lib.name.contains("@")) lib.ext = lib.name.substring(lib.name.indexOf('@') + 1);
        return lib;
    }

    public boolean allowed(Map<String, Boolean> features) {
        return Rules.evaluate(rules, features);
    }

    /** 例如 natives-windows；没有则返回 null。 */
    public String nativeClassifier() {
        String key = natives.get(Rules.OS);
        if (key == null) return null;
        String arch = System.getProperty("os.arch", "");
        boolean x64 = arch.contains("64") && !arch.toLowerCase().contains("arm");
        String bits = x64 ? "64" : "32";
        return key.replace("${arch}", bits);
    }

    /** maven 坐标：group:artifact:version[:classifier][@ext] -> 相对路径 */
    public static String mavenPath(String coord) {
        return mavenPath(coord, null);
    }

    public static String mavenPath(String coord, String classifierOverride) {
        String ext = "jar";
        String c = coord;
        int at = c.indexOf('@');
        if (at > 0) {
            ext = c.substring(at + 1);
            c = c.substring(0, at);
        }
        String[] parts = c.split(":");
        if (parts.length < 3) return coord.replace(':', '/') + "." + ext;
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? parts[3] : classifierOverride;
        String file = artifact + "-" + version + (classifier != null && !classifier.isEmpty() ? "-" + classifier : "") + "." + ext;
        return group + "/" + artifact + "/" + version + "/" + file;
    }

    public String artifactPath() {
        if (artifact != null && artifact.path != null) return artifact.path;
        return mavenPath(name);
    }

    public String artifactUrl() {
        if (artifact != null && artifact.url != null) return artifact.url;
        String base = mavenBase != null ? mavenBase : "https://libraries.minecraft.net/";
        if (!base.endsWith("/")) base = base + "/";
        return base + artifactPath();
    }

    public String sha1() {
        return artifact == null ? null : artifact.sha1;
    }

    public long size() {
        return artifact == null ? 0 : artifact.size;
    }
}
