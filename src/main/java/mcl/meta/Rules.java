package mcl.meta;

import mcl.util.Compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/** Minecraft 版本 JSON 的 rules 求值（操作系统 / 架构 / 特性）。 */
public final class Rules {
    public static final String OS = detectOs();
    public static final String ARCH = detectArch();

    private Rules() {}

    private static String detectOs() {
        String n = System.getProperty("os.name", "").toLowerCase();
        if (n.contains("win")) return "windows";
        if (n.contains("mac") || n.contains("darwin")) return "osx";
        return "linux";
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        if (a.contains("64")) return "x64";
        if (a.contains("arm") || a.contains("aarch64")) return "arm64";
        return "x86";
    }

    public static boolean allows(JsonArray rules) {
        return evaluate(rules, Compat.mapOf());
    }

    public static boolean evaluate(JsonArray rules, Map<String, Boolean> features) {
        if (rules == null || rules.isEmpty()) return true;
        boolean allow = false;
        for (JsonElement el : rules) {
            if (!el.isJsonObject()) continue;
            JsonObject rule = el.getAsJsonObject();
            if (!matches(rule, features)) continue;
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
            allow = "allow".equals(action);
        }
        return allow;
    }

    private static boolean matches(JsonObject rule, Map<String, Boolean> features) {
        JsonObject os = rule.has("os") && rule.get("os").isJsonObject() ? rule.getAsJsonObject("os") : null;
        if (os != null) {
            String name = os.has("name") ? os.get("name").getAsString() : null;
            if (name != null && !name.equals(OS)) return false;
            String arch = os.has("arch") ? os.get("arch").getAsString() : null;
            if (arch != null && !arch.equals(ARCH)) return false;
            String version = os.has("version") ? os.get("version").getAsString() : null;
            if (version != null) {
                try {
                    if (!System.getProperty("os.version", "").matches(version)) return false;
                } catch (Exception ignored) {
                }
            }
        }
        JsonObject feat = rule.has("features") && rule.get("features").isJsonObject()
                ? rule.getAsJsonObject("features") : null;
        if (feat != null) {
            for (Map.Entry<String, JsonElement> e : feat.entrySet()) {
                boolean want = e.getValue().isJsonPrimitive() && e.getValue().getAsBoolean();
                boolean have = Boolean.TRUE.equals(features.get(e.getKey()));
                if (want != have) return false;
            }
        }
        return true;
    }
}
