package mcl.meta;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.util.Json;

import java.util.ArrayList;
import java.util.List;

/** 单个版本的完整元数据。 */
public class VersionMeta {
    public String id = "";
    public String type = "";
    public String mainClass = "";
    public String assets = "";
    public String inheritsFrom;
    /**
     * 客户端 jar 从哪个版本拿（Mojang 原生字段）。整合包把自己做成
     * 独立 profile 之后没有 inheritsFrom，就只能靠它指回原版的 jar。
     */
    public String jar;
    public int minimumLauncherVersion;
    public String releaseTime = "";
    public int javaMajor = 0;
    public String javaComponent = "";

    public String assetIndexId;
    public String assetIndexUrl;
    public String assetIndexSha1;
    public long assetIndexSize;
    public long assetTotalSize;

    public String clientUrl;
    public String clientSha1;
    public long clientSize;

    public String logArgument;
    public String logConfigId;
    public String logConfigUrl;
    public String logConfigSha1;
    public long logConfigSize;

    public final List<Library> libraries = new ArrayList<>();
    public JsonArray gameArguments;      // 1.13+
    public JsonArray jvmArguments;       // 1.13+
    public String legacyArguments;       // 1.12-

    public String rawJson = "";

    public static VersionMeta parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        VersionMeta m = new VersionMeta();
        m.rawJson = json;
        m.id = Json.str(o, "id", "");
        m.type = Json.str(o, "type", "release");
        m.mainClass = Json.str(o, "mainClass", "");
        m.assets = Json.str(o, "assets", "");
        m.inheritsFrom = Json.str(o, "inheritsFrom", null);
        m.jar = Json.str(o, "jar", null);
        m.minimumLauncherVersion = Json.integer(o, "minimumLauncherVersion", 0);
        m.releaseTime = Json.str(o, "releaseTime", "");
        m.javaMajor = Json.integer(Json.obj(o, "javaVersion"), "majorVersion", 0);
        m.javaComponent = Json.str(Json.obj(o, "javaVersion"), "component", "");

        JsonObject downloads = Json.obj(o, "downloads");
        JsonObject client = Json.obj(downloads, "client");
        if (client != null) {
            m.clientUrl = Json.str(client, "url", null);
            m.clientSha1 = Json.str(client, "sha1", null);
            m.clientSize = Json.lng(client, "size", 0);
        }

        JsonObject assetIndex = Json.obj(o, "assetIndex");
        if (assetIndex != null) {
            m.assetIndexId = Json.str(assetIndex, "id", null);
            m.assetIndexUrl = Json.str(assetIndex, "url", null);
            m.assetIndexSha1 = Json.str(assetIndex, "sha1", null);
            m.assetIndexSize = Json.lng(assetIndex, "size", 0);
            m.assetTotalSize = Json.lng(assetIndex, "totalSize", 0);
        }

        JsonObject logging = Json.obj(o, "logging");
        JsonObject logClient = Json.obj(logging, "client");
        if (logClient != null) {
            m.logArgument = Json.str(logClient, "argument", null);
            JsonObject file = Json.obj(logClient, "file");
            if (file != null) {
                m.logConfigId = Json.str(file, "id", null);
                m.logConfigUrl = Json.str(file, "url", null);
                m.logConfigSha1 = Json.str(file, "sha1", null);
                m.logConfigSize = Json.lng(file, "size", 0);
            }
        }

        JsonArray libs = Json.arr(o, "libraries");
        if (libs != null) {
            for (JsonElement e : libs) {
                if (e.isJsonObject()) m.libraries.add(Library.parse(e.getAsJsonObject()));
            }
        }
        JsonObject args = Json.obj(o, "arguments");
        if (args != null) {
            m.gameArguments = Json.arr(args, "game");
            m.jvmArguments = Json.arr(args, "jvm");
        }
        m.legacyArguments = Json.str(o, "minecraftArguments", null);
        return m;
    }
}
