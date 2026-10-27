package mcl.game;

import mcl.util.Compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mcl.config.Account;
import mcl.config.Profile;
import mcl.meta.Rules;
import mcl.meta.VersionMeta;
import mcl.util.Log;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 把版本元数据 + 账户 + 配置拼成一条完整的 java 命令行。 */
public final class LaunchCommand {
    public static final String LAUNCHER_NAME = "MCL";
    public static final String LAUNCHER_VERSION = "0.1.0";

    private LaunchCommand() {}

    public static List<String> build(VersionMeta meta, Profile profile, Account account, Path gameDir,
                                     Path assetsDir, Path nativesDir, Path clientJar, List<Path> libraries,
                                     Path javaExe, Path logConfig, int memoryMb) {
        return build(meta, profile, account, gameDir, assetsDir, nativesDir, clientJar, libraries,
                javaExe, logConfig, memoryMb, null, null, null);
    }

    /**
     * @param javaAgent 额外注入的 -javaagent（外置登录用），可为 null
     */
    public static List<String> build(VersionMeta meta, Profile profile, Account account, Path gameDir,
                                     Path assetsDir, Path nativesDir, Path clientJar, List<Path> libraries,
                                     Path javaExe, Path logConfig, int memoryMb, String javaAgent) {
        return build(meta, profile, account, gameDir, assetsDir, nativesDir, clientJar, libraries,
                javaExe, logConfig, memoryMb, javaAgent, null, null);
    }

    /**
     * @param javaAgent    额外注入的 -javaagent（外置登录用），可为 null
     * @param quickServer  从服务器列表"一键进服"时传入的 {@code host} 或 {@code host:port}，
     *                     非空时覆盖配置里的 serverIp / serverPort
     */
    public static List<String> build(VersionMeta meta, Profile profile, Account account, Path gameDir,
                                     Path assetsDir, Path nativesDir, Path clientJar, List<Path> libraries,
                                     Path javaExe, Path logConfig, int memoryMb, String javaAgent,
                                     String quickServer) {
        return build(meta, profile, account, gameDir, assetsDir, nativesDir, clientJar, libraries,
                javaExe, logConfig, memoryMb, javaAgent, quickServer, null);
    }

    /**
     * @param librariesDir 库根目录（{@code .minecraft/libraries}）。隔离实例的运行目录是
     *                     {@code versions/<id>}，Forge 的 {@code ${library_directory}} 必须
     *                     指向真正的库根，否则 {@code -p} 上的模块路径全是空路径，Forge 会退回
     *                     classpath 以 unnamed module 加载，接着 {@code --add-opens ...=cpw.mods.securejarhandler}
     *                     就对不上，直接 {@code InaccessibleObjectException} 起不来。
     *                     传 null 时从 libraries 列表自动推导。
     */
    public static List<String> build(VersionMeta meta, Profile profile, Account account, Path gameDir,
                                     Path assetsDir, Path nativesDir, Path clientJar, List<Path> libraries,
                                     Path javaExe, Path logConfig, int memoryMb, String javaAgent,
                                     String quickServer, Path librariesDir) {
        boolean legacyAssets = meta.assetIndexId == null || "legacy".equals(meta.assetIndexId)
                || "pre-1.6".equals(meta.assetIndexId);
        Path gameAssets = legacyAssets
                ? assetsDir.resolve("virtual").resolve(meta.assetIndexId == null ? "legacy" : meta.assetIndexId)
                : assetsDir;

        Map<String, Boolean> features = new HashMap<>();
        boolean customRes = !profile.fullscreen && profile.width > 0 && profile.height > 0;
        features.put("has_custom_resolution", customRes);
        features.put("is_demo_user", false);
        features.put("has_quick_plays_support", false);
        features.put("is_quick_play_singleplayer", false);
        features.put("is_quick_play_multiplayer", false);
        features.put("is_quick_play_realms", false);

        Set<Path> cpSet = new LinkedHashSet<>();
        if (clientJar != null && Files.isRegularFile(clientJar)) cpSet.add(clientJar.toAbsolutePath());
        for (Path p : libraries) if (Files.isRegularFile(p)) cpSet.add(p.toAbsolutePath());
        StringBuilder cp = new StringBuilder();
        for (Path p : cpSet) {
            if (cp.length() > 0) cp.append(File.pathSeparator);
            cp.append(p);
        }

        Map<String, String> vars = new HashMap<>();
        String playerName = account == null || Compat.isBlank(account.username) ? "Player" : account.username;
        // 没配账户时也要给出合法 UUID / token，否则 1.16+ 会在启动时抛 Invalid UUID string
        String playerUuid = account == null || account.uuid == null || Compat.isBlank(account.uuid)
                ? Account.offlineUuid(playerName) : account.uuid;
        String accessToken = account == null || account.accessToken == null || Compat.isBlank(account.accessToken)
                ? "0" : account.accessToken;
        vars.put("auth_player_name", playerName);
        vars.put("version_name", meta.id);
        vars.put("game_directory", gameDir.toAbsolutePath().toString());
        vars.put("assets_root", assetsDir.toAbsolutePath().toString());
        vars.put("game_assets", gameAssets.toAbsolutePath().toString());
        vars.put("assets_index_name", meta.assets != null && !meta.assets.isEmpty()
                ? meta.assets : (meta.assetIndexId == null ? "legacy" : meta.assetIndexId));
        vars.put("auth_uuid", playerUuid);
        vars.put("auth_access_token", accessToken);
        vars.put("auth_session", account == null ? "-" : "token:" + accessToken + ":" + playerUuid);
        vars.put("user_properties", "{}");
        vars.put("user_type", account == null ? "legacy" : account.userType());
        vars.put("version_type", meta.type == null ? "release" : meta.type);
        vars.put("natives_directory", nativesDir.toAbsolutePath().toString());
        vars.put("launcher_name", LAUNCHER_NAME);
        vars.put("launcher_version", LAUNCHER_VERSION);
        vars.put("classpath", cp.toString());
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", libraryRootFor(libraries, librariesDir, gameDir).toString());
        vars.put("resolution_width", String.valueOf(profile.width));
        vars.put("resolution_height", String.valueOf(profile.height));
        vars.put("clientid", "");
        vars.put("auth_xuid", "");
        vars.put("primary_jar", clientJar == null ? "" : clientJar.toAbsolutePath().toString());

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toAbsolutePath().toString());
        cmd.add("-Xmx" + Math.max(512, memoryMb) + "M");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");

        List<String> jvm = new ArrayList<>();
        if (javaAgent != null && !Compat.isBlank(javaAgent)) jvm.add(javaAgent);
        if (meta.jvmArguments != null) {
            jvm.addAll(evaluateArgs(meta.jvmArguments, features, vars));
        }
        if (jvm.isEmpty()) {
            jvm.add("-Djava.library.path=${natives_directory}");
            jvm.add("-cp");
            jvm.add("${classpath}");
        }
        if (logConfig != null && Files.isRegularFile(logConfig) && meta.logArgument != null) {
            jvm.add(meta.logArgument.replace("${path}", logConfig.toAbsolutePath().toString()));
        }
        if (profile.extraJvmArgs != null && !Compat.isBlank(profile.extraJvmArgs)) {
            jvm.addAll(splitArgs(profile.extraJvmArgs));
        }
        cmd.addAll(jvm);

        cmd.add(meta.mainClass);

        List<String> game = new ArrayList<>();
        if (meta.gameArguments != null) {
            game.addAll(evaluateArgs(meta.gameArguments, features, vars));
        } else if (meta.legacyArguments != null) {
            for (String token : splitArgs(meta.legacyArguments)) game.add(substitute(token, vars));
        }
        if (profile.extraGameArgs != null && !Compat.isBlank(profile.extraGameArgs)) {
            for (String token : splitArgs(profile.extraGameArgs)) game.add(substitute(token, vars));
        }
        if (quickServer != null && !Compat.isBlank(quickServer)) {
            game.add("--server");
            game.add(mcl.game.Servers.normalizeHost(quickServer));
            game.add("--port");
            game.add(mcl.game.Servers.normalizePort(quickServer));
        } else if (profile.serverIp != null && !Compat.isBlank(profile.serverIp)) {
            game.add("--server");
            game.add(profile.serverIp.trim());
            game.add("--port");
            game.add(profile.serverPort == null || Compat.isBlank(profile.serverPort) ? "25565" : profile.serverPort.trim());
        }
        if (profile.fullscreen) game.add("--fullscreen");
        cmd.addAll(game);

        // 变量替换（含 classpath 内的路径）
        List<String> out = new ArrayList<>(cmd.size());
        for (String s : cmd) out.add(substitute(s, vars));
        Log.info("启动命令：" + String.join(" ", out));
        return out;
    }

    /**
     * 求 {@code ${library_directory}}。
     *
     * <p>优先级：调用方给的目录（且真实存在）→ 从 libraries 列表里回溯出名为 {@code libraries}
     * 的那一级 → {@code <gameDir>/libraries} 兜底。
     *
     * <p>隔离实例（整合包）的运行目录是 {@code versions/<id>}，直接 resolve("libraries") 会得到
     * 一个不存在的路径，Forge 的 {@code -p} 就整条失效。
     */
    public static Path libraryRootFor(List<Path> libraries, Path librariesDir, Path gameDir) {
        if (librariesDir != null && Files.isDirectory(librariesDir)) {
            return librariesDir.toAbsolutePath();
        }
        if (libraries != null) {
            for (Path p : libraries) {
                if (p == null) continue;
                Path cur = p.toAbsolutePath().getParent();
                while (cur != null) {
                    Path name = cur.getFileName();
                    if (name != null && "libraries".equals(name.toString())) return cur;
                    cur = cur.getParent();
                }
            }
        }
        return gameDir.resolve("libraries").toAbsolutePath();
    }

    /** 解析 arguments 数组（字符串或带 rules 的对象）。 */
    private static List<String> evaluateArgs(JsonArray array, Map<String, Boolean> features, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        if (array == null) return out;
        for (JsonElement el : array) {
            if (el.isJsonPrimitive()) {
                out.add(substitute(el.getAsString(), vars));
                continue;
            }
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            JsonArray rules = o.has("rules") && o.get("rules").isJsonArray() ? o.getAsJsonArray("rules") : null;
            if (rules != null && !Rules.evaluate(rules, features)) continue;
            JsonElement value = o.get("value");
            if (value == null) continue;
            if (value.isJsonArray()) {
                for (JsonElement v : value.getAsJsonArray()) {
                    if (v.isJsonPrimitive()) out.add(substitute(v.getAsString(), vars));
                }
            } else if (value.isJsonPrimitive()) {
                out.add(substitute(value.getAsString(), vars));
            }
        }
        return out;
    }

    public static String substitute(String s, Map<String, String> vars) {
        if (s == null || s.indexOf('$') < 0) return s;
        String out = s;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String key = "${" + e.getKey() + "}";
            if (out.contains(key)) out = out.replace(key, e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    /** 空格切分，支持双引号包裹。 */
    public static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    public static int autoMemoryMb() {
        long totalMb = 0;
        try {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                totalMb = ((com.sun.management.OperatingSystemMXBean) os).getTotalPhysicalMemorySize() / (1024 * 1024);
            }
        } catch (Throwable ignored) {
        }
        if (totalMb <= 0) return 4096;
        long half = totalMb / 2;
        return (int) Math.max(1024, Math.min(half, 8192));
    }
}
