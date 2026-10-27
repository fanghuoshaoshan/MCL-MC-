package mcl.config;

import mcl.util.Compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcl.util.Json;
import mcl.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** MCL 配置（mcl.json），并支持从旧版 hmcl.json 迁移配置与账户。 */
public class Settings {
    /**
     * 配置结构版本：低于当前版本的配置在读入时会做一次迁移。
     *
     * <p>1 → 2：版本隔离改为默认开启（每个版本独立 mods / saves）。
     */
    public int configVersion = CURRENT_CONFIG_VERSION;
    /** 当前配置结构版本，改结构时 +1 并在 {@link #migrate()} 里补一条。 */
    public static final int CURRENT_CONFIG_VERSION = 2;
    public List<Profile> profiles = new ArrayList<>();
    public List<Account> accounts = new ArrayList<>();
    public String selectedAccount = "";
    public String selectedProfile = "Default";
    public String downloadSource = "BMCLAPI";
    public int downloadThreads = 32;
    /**
     * 模组搜索用的内容源：modrinth（国际站）/ curseforge。
     *
     * <p>CurseForge 需要 API Key：留空则用内置的兜底 key，配额是跟别人共用的；
     * 想独享就去 curseforge.com 开发者控制台免费申请一个填这里。
     */
    public String modSource = "modrinth";
    public String themeColor = "#5C6BC0";
    public String backgroundImage = "";
    public int windowWidth = 1000;
    public int windowHeight = 640;
    public boolean showLogWindow = true;
    /**
     * CurseForge API Key：搜索 / 下载 CF 模组与导入 CF 整合包都要用它（官网免费申请）。
     * 留空则用内置的兜底 key——那个是共享配额，可能被限速，有自己的就填这里。
     */
    public String curseforgeApiKey = "";
    /** 网络代理：none / http / socks */
    public String proxyMode = "none";
    public String proxyHost = "";
    public int proxyPort = 7890;
    public String proxyUser = "";
    public String proxyPass = "";
    /** 全局限速（KB/s），0 表示不限速 */
    public int speedLimitKbps = 0;

    public transient Path launcherDir;

    public Settings() {
        profiles.add(new Profile("Default"));
    }

    public static Settings load(Path launcherDir) {
        Path file = launcherDir.resolve("mcl.json");
        Path hmcl = launcherDir.resolve("hmcl.json");
        Settings s = null;
        if (Files.isRegularFile(file)) {
            try {
                s = new Gson().fromJson(Compat.readString(file), Settings.class);
            } catch (Exception e) {
                Log.error("读取 mcl.json 失败，将使用默认配置", e);
            }
        }
        if (s == null && Files.isRegularFile(hmcl)) {
            s = importHmcl(hmcl);
        }
        boolean fresh = s == null;
        if (s == null) s = new Settings();
        s.launcherDir = launcherDir;
        s.normalize();
        if (s.migrate()) s.save();
        // 首次启动就把配置落盘：这是「别人第一次拿到 MCL.exe」的场景，
        // 目录里有个 mcl.json，用户（和我们排查时）才知道配置在哪儿、有哪些项。
        // 原来只在用户改过设置后才写，第一次打开时目录里只有 logs 和 .minecraft。
        if (fresh) s.save();
        // 让「设置里填的 CurseForge key」立刻生效：之后 CurseForge.key()
        // 就会优先用这个值，而不是内置的兜底 key
        mcl.mod.CurseForge.setCustomKey(s.curseforgeApiKey);
        return s;
    }

    private void normalize() {
        if (profiles == null) profiles = new ArrayList<>();
        if (accounts == null) accounts = new ArrayList<>();
        if (profiles.isEmpty()) profiles.add(new Profile("Default"));
        boolean hasDefault = false;
        for (Profile p : profiles) if ("Default".equals(p.name)) hasDefault = true;
        if (!hasDefault) profiles.add(0, new Profile("Default"));
        if (profile() == null) selectedProfile = profiles.get(0).name;
        if (account() == null && !accounts.isEmpty()) selectedAccount = accounts.get(0).username;
    }

    /**
     * 把旧配置升到 {@link #CURRENT_CONFIG_VERSION}，返回是否改动过（改动过才需要落盘）。
     *
     * <p>只在「读到的版本号比当前低」时执行，所以用户自己关掉版本隔离之后不会被再次打开。
     */
    private boolean migrate() {
        boolean changed = false;
        if (configVersion < 2) {
            // 旧版默认 isolate=false，JSON 里躺着一个明确的 false。
            // 直接改字段默认值对已存在的配置文件无效，必须在这里覆盖一遍。
            for (Profile p : profiles) {
                if (p != null) p.isolate = true;
            }
            configVersion = 2;
            changed = true;
            Log.info("配置升级到 v2：已启用版本隔离（每个版本独立 mods / saves）");
        }
        if (configVersion != CURRENT_CONFIG_VERSION) configVersion = CURRENT_CONFIG_VERSION;
        return changed;
    }

    public Profile profile() {
        for (Profile p : profiles) if (p.name.equals(selectedProfile)) return p;
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    public Account account() {
        for (Account a : accounts) if (a.username.equals(selectedAccount)) return a;
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    public Path gameDir(Profile p) {
        String dir = p == null ? ".minecraft" : p.gameDir;
        Path path = java.nio.file.Paths.get(dir);
        return path.isAbsolute() ? path : launcherDir.resolve(dir);
    }

    public Path launcherDir() {
        return launcherDir != null ? launcherDir : java.nio.file.Paths.get(".").toAbsolutePath();
    }

    public void save() {
        try {
            Path file = launcherDir().resolve("mcl.json");
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            Compat.writeString(file, gson.toJson(this));
        } catch (IOException e) {
            Log.error("保存 mcl.json 失败", e);
        }
    }

    /** 从 HMCL 的 hmcl.json 迁移：账户、配置、外观、下载设置。 */
    public static Settings importHmcl(Path hmclFile) {
        Settings s = new Settings();
        s.profiles.clear();
        try {
            JsonObject root = JsonParser.parseString(Compat.readString(hmclFile))
                    .getAsJsonObject();
            // 账户
            JsonArray accounts = Json.arr(root, "accounts");
            if (accounts != null) {
                for (JsonElement e : accounts) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String name = Json.str(o, "username", "");
                    String type = Json.str(o, "type", "offline");
                    if (name.isEmpty()) continue;
                    Account a = new Account(name, "offline");
                    a.uuid = Json.str(o, "uuid", a.uuid);
                    if (!"offline".equals(type)) a.type = type;
                    s.accounts.add(a);
                }
            }
            String sel = Json.str(root, "selectedAccount", "");
            if (!sel.isEmpty()) s.selectedAccount = sel.contains(":") ? sel.substring(0, sel.indexOf(':')) : sel;
            // 配置
            JsonObject configs = Json.obj(root, "configurations");
            if (configs != null) {
                for (String key : configs.keySet()) {
                    JsonObject c = Json.obj(configs, key);
                    if (c == null) continue;
                    Profile p = new Profile(key);
                    p.gameDir = Json.str(c, "gameDir", ".minecraft");
                    p.selectedVersion = Json.str(c, "selectedMinecraftVersion", "");
                    JsonObject g = Json.obj(c, "global");
                    if (g != null) {
                        p.maxMemory = Json.integer(g, "maxMemory", 4096);
                        p.autoMemory = Json.bool(g, "autoMemory", true);
                        p.javaPath = Json.str(g, "defaultJavaPath", "");
                        p.width = Json.integer(g, "width", 854);
                        p.height = Json.integer(g, "height", 480);
                        p.fullscreen = Json.bool(g, "fullscreen", false);
                        p.extraJvmArgs = Json.str(g, "javaArgs", "");
                        p.extraGameArgs = Json.str(g, "minecraftArgs", "");
                        p.serverIp = Json.str(g, "serverIp", "");
                    }
                    s.profiles.add(p);
                }
            }
            String last = Json.str(root, "last", "");
            if (!last.isEmpty() && configs != null && configs.has(last)) s.selectedProfile = last;
            // 外观与下载
            s.themeColor = Json.str(root, "theme", "#5C6BC0");
            s.backgroundImage = Json.str(root, "bgpath", "");
            s.downloadThreads = Json.integer(root, "downloadThreads", 32);
            String dt = Json.str(root, "downloadType", "bmclapi");
            s.downloadSource = "official".equalsIgnoreCase(dt) || "mojang".equalsIgnoreCase(dt) ? "MOJANG" : "BMCLAPI";
            int w = root.has("width") ? (int) root.get("width").getAsDouble() : 1000;
            int h = root.has("height") ? (int) root.get("height").getAsDouble() : 640;
            if (w > 700) s.windowWidth = Math.min(w + 260, 1600);
            if (h > 400) s.windowHeight = Math.min(h + 160, 1000);
            Log.info("已从 hmcl.json 迁移 " + s.accounts.size() + " 个账户、" + s.profiles.size() + " 个配置");
        } catch (Exception e) {
            Log.error("迁移 hmcl.json 失败", e);
        }
        if (s.profiles.isEmpty()) s.profiles.add(new Profile("Default"));
        return s;
    }
}
