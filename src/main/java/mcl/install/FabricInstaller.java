package mcl.install;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.meta.VersionMeta;
import mcl.net.Http;
import mcl.net.Source;
import mcl.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Fabric / Quilt 安装：使用官方 meta 服务生成带 inheritsFrom 的版本 JSON。 */
public final class FabricInstaller {
    public static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/";
    public static final String QUILT_META = "https://meta.quiltmc.org/v3/versions/loader/";

    private FabricInstaller() {}

    public static class Loader {
        public String version = "";
        public boolean stable = true;

        @Override
        public String toString() {
            return version + (stable ? "" : "（不稳定）");
        }
    }

    /** 查询某个游戏版本可用的加载器版本（新的在前）。 */
    public static List<Loader> loaders(String gameVersion, boolean quilt) throws IOException {
        String base = quilt ? QUILT_META : FABRIC_META;
        String body = Http.getString(base + gameVersion);
        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
        List<Loader> result = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            JsonObject loader = Json.obj(o, "loader");
            Loader l = new Loader();
            l.version = Json.str(loader, "version", Json.str(o, "version", ""));
            l.stable = Json.bool(loader, "stable", Json.bool(o, "stable", true));
            if (!l.version.isEmpty()) result.add(l);
        }
        if (result.isEmpty()) throw new IOException("没有找到 " + gameVersion + " 可用的加载器版本");
        return result;
    }

    /** 生成加载器版本 JSON（含 inheritsFrom）。 */
    public static VersionMeta profile(String gameVersion, String loaderVersion, boolean quilt, Source source)
            throws IOException {
        String base = quilt ? QUILT_META : FABRIC_META;
        String url = base + gameVersion + "/" + loaderVersion + "/profile/json";
        String json = Http.getString(url);
        VersionMeta meta = VersionMeta.parse(json);
        if (meta.inheritsFrom == null || meta.inheritsFrom.isEmpty()) meta.inheritsFrom = gameVersion;
        meta.rawJson = json;
        return meta;
    }

    public static String installId(VersionMeta profile) {
        return profile.id;
    }
}
