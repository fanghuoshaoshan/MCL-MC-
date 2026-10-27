package mcl.meta;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.net.Http;
import mcl.net.Source;
import mcl.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 版本清单（Mojang / BMCLAPI 同构）。 */
public class VersionManifest {
    public static class Entry {
        public String id = "";
        public String type = "release";
        public String url = "";
        public String releaseTime = "";
        public String sha1 = "";

        @Override
        public String toString() {
            return id;
        }
    }

    public String latestRelease = "";
    public String latestSnapshot = "";
    public final List<Entry> versions = new ArrayList<>();

    public static VersionManifest fetch(Source source) throws IOException {
        // 优先当前源，镜像挂掉时自动回退到另一套地址（实测 BMCLAPI 偶尔会整站不可用）。
        // 每个地址只试一次、并把失败计入熔断，避免镜像挂掉时每次操作都白等几十秒。
        List<String> urls = new ArrayList<>();
        Source fallback = source == Source.MOJANG ? Source.BMCLAPI : Source.MOJANG;
        if (source.available()) {
            for (String u : source.manifestUrls()) urls.add(u);
        }
        for (String u : fallback.manifestUrls()) urls.add(u);
        if (!source.available()) {
            for (String u : source.manifestUrls()) urls.add(u);
        }
        String body = null;
        IOException last = null;
        for (String u : urls) {
            try {
                body = mcl.net.Http.getStringOnce(u);
                source.recordSuccess();
                break;
            } catch (IOException e) {
                last = e;
                if (u.startsWith(source.baseUrl)) source.recordFailure();
                mcl.util.Log.warn("清单地址失败：" + u + " -> " + e.getMessage());
            }
        }
        if (body == null) throw last != null ? last : new IOException("无法获取版本清单");
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        VersionManifest m = new VersionManifest();
        JsonObject latest = Json.obj(o, "latest");
        m.latestRelease = Json.str(latest, "release", "");
        m.latestSnapshot = Json.str(latest, "snapshot", "");
        JsonArray arr = Json.arr(o, "versions");
        if (arr != null) {
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject v = e.getAsJsonObject();
                Entry en = new Entry();
                en.id = Json.str(v, "id", "");
                en.type = Json.str(v, "type", "release");
                en.url = Json.str(v, "url", "");
                en.releaseTime = Json.str(v, "releaseTime", "");
                en.sha1 = Json.str(v, "sha1", "");
                if (!en.id.isEmpty() && !en.url.isEmpty()) m.versions.add(en);
            }
        }
        // 统一按发布时间倒序。
        // Mojang 官方清单本身就是新版在前，但 BMCLAPI 镜像返回的**恰好相反**（旧版在前），
        // 直接沿用服务端顺序的话，切到镜像后列表从 1.0 / 1.1 开始，用户滚到 1.16
        // 就以为"没有更新的版本了"。排序不能依赖服务端。
        sortNewestFirst(m.versions);
        return m;
    }

    /**
     * 按发布时间倒序（最新在前），同一天再按 id 兜底保证顺序稳定。
     *
     * <p>抽成独立方法是为了能被自检直接覆盖 —— 这段逻辑一旦被"优化"掉，
     * 表现就是列表从远古版本开始，很难在别处发现。
     */
    public static void sortNewestFirst(List<Entry> versions) {
        java.util.Collections.sort(versions, new java.util.Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                String ta = a == null || a.releaseTime == null ? "" : a.releaseTime;
                String tb = b == null || b.releaseTime == null ? "" : b.releaseTime;
                int c = tb.compareTo(ta);
                return c != 0 ? c : String.valueOf(a == null ? "" : a.id).compareTo(String.valueOf(b == null ? "" : b.id));
            }
        });
    }

    /**
     * 取出全部正式版 id（保持传入顺序）。
     *
     * <p>抽出来是为了能被自检直接覆盖：早先调用方自己写到「前 40 个就停」，
     * 而 40 个正式版只够退到 1.14~1.16，用户在模组页的版本下拉里滚到底会以为
     * 「这个启动器只支持到 1.16」。任何时候都不要在这里加数量上限。
     */
    public static java.util.List<String> releaseIds(java.util.List<Entry> versions) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (versions == null) return out;
        for (Entry e : versions) {
            if (e != null && "release".equals(e.type) && e.id != null) out.add(e.id);
        }
        return out;
    }

    public Entry find(String id) {
        for (Entry e : versions) if (e.id.equals(id)) return e;
        return null;
    }
}
