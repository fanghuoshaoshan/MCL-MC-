package mcl.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** JsonObject 的安全取值工具，避免到处判空。 */
public final class Json {
    private Json() {}

    public static String str(JsonObject o, String key, String def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull() || !e.isJsonPrimitive()) ? def : e.getAsString();
    }

    public static long lng(JsonObject o, String key, long def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsLong();
        } catch (Exception ex) {
            return def;
        }
    }

    public static int integer(JsonObject o, String key, int def) {
        return (int) lng(o, key, def);
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (Exception ex) {
            return def;
        }
    }

    public static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    public static String asString(JsonElement e, String def) {
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : def;
    }
}
