package mcl;

import mcl.util.Dirs;
import mcl.util.Compat;

import mcl.config.Account;
import mcl.config.Settings;
import mcl.game.Game;
import mcl.install.Installer;
import mcl.meta.VersionManifest;
import mcl.meta.VersionMeta;
import mcl.net.Source;
import mcl.ui.MainWindow;
import mcl.util.Log;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** MCL 入口：无参数启动图形界面，带参数时可做自检 / 安装 / 启动。 */
public final class MCL {
    public static final String VERSION = "0.3.0";

    public static void main(String[] args) {
        forceNativeConsole();
        Path home = detectHome();
        Log.setFile(home.resolve("logs").resolve("mcl.log"));
        Log.info("MCL " + VERSION + " 启动，主目录：" + home);
        // 代理与限速必须在使用任何网络之前生效
        try {
            mcl.net.Net.apply(Settings.load(home));
        } catch (Exception e) {
            Log.warn("应用网络设置失败：" + e.getMessage());
        }

        List<String> list = Arrays.asList(args);
        try {
            if (list.contains("--help") || list.contains("-h")) {
                printHelp();
                return;
            }
            if (list.contains("--check-net")) {
                checkNet(home);
                return;
            }
            if (list.contains("--install")) {
                String id = valueOf(list, "--install");
                install(home, id, !list.contains("--no-assets"));
                return;
            }
            if (list.contains("--fabric")) {
                String game = valueOf(list, "--fabric");
                String loader = list.contains("--loader") ? valueOf(list, "--loader") : null;
                installFabric(home, game, loader);
                return;
            }
            if (list.contains("--forge") || list.contains("--neoforge")) {
                boolean neo = list.contains("--neoforge");
                String game = valueOf(list, neo ? "--neoforge" : "--forge");
                String loader = list.contains("--loader") ? valueOf(list, "--loader") : null;
                installForge(home, game, loader, neo);
                return;
            }
            if (list.contains("--loaders")) {
                String game = valueOf(list, "--loaders");
                listLoaders(game);
                return;
            }
            if (list.contains("--modrinth")) {
                searchModrinth(mcl.mod.ModSource.byId(opt(list, "--source", "modrinth")),
                        valueOf(list, "--modrinth"),
                        opt(list, "--type", "mod"), opt(list, "--mc", ""), opt(list, "--modloader", ""));
                return;
            }
            if (list.contains("--mod")) {
                installMod(home, opt(list, "--version", ""), valueOf(list, "--mod"),
                        opt(list, "--type", "mod"), mcl.mod.ModSource.byId(opt(list, "--source", "modrinth")));
                return;
            }
            if (list.contains("--mods")) {
                listMods(home, opt(list, "--version", ""));
                return;
            }
            if (list.contains("--tidy")) {
                tidyVersions(home);
                return;
            }
            if (list.contains("--modpack")) {
                importModpack(home, java.nio.file.Paths.get(valueOf(list, "--modpack")));
                return;
            }
            if (list.contains("--export")) {
                exportModpack(home, java.nio.file.Paths.get(valueOf(list, "--export")));
                return;
            }
            if (list.contains("--optifine")) {
                installOptiFine(home, valueOf(list, "--optifine"), opt(list, "--edition", ""));
                return;
            }
            if (list.contains("--rename")) {
                versionAction(home, valueOf(list, "--rename"), false);
                return;
            }
            if (list.contains("--copy")) {
                versionAction(home, valueOf(list, "--copy"), true);
                return;
            }
            if (list.contains("--login-ms")) {
                loginMicrosoft(home);
                return;
            }
            if (list.contains("--login-authlib")) {
                loginAuthlib(home, valueOf(list, "--login-authlib"),
                        opt(list, "--username", ""), opt(list, "--password", ""));
                return;
            }
            if (list.contains("--set-proxy")) {
                setProxy(home, valueOf(list, "--set-proxy"),
                        opt(list, "--limit", ""), list.contains("--no-save"));
                return;
            }
            if (list.contains("--skin")) {
                fetchSkin(home, valueOf(list, "--skin"));
                return;
            }
            if (list.contains("--world-export")) {
                worldExport(home, opt(list, "--version", ""), valueOf(list, "--world-export"),
                        java.nio.file.Paths.get(opt(list, "--out", "")));
                return;
            }
            if (list.contains("--world-import-url")) {
                worldImportUrl(home, opt(list, "--version", ""), valueOf(list, "--world-import-url"));
                return;
            }
            if (list.contains("--crash")) {
                crashReport(home, opt(list, "--version", ""));
                return;
            }
            if (list.contains("--servers")) {
                listServers(home);
                return;
            }
            if (list.contains("--server-add")) {
                addServer(home, valueOf(list, "--server-add"), opt(list, "--name", ""));
                return;
            }
            if (list.contains("--server-ping")) {
                pingServer(valueOf(list, "--server-ping"));
                return;
            }
            if (list.contains("--worlds")) {
                listWorlds(home, opt(list, "--version", ""));
                return;
            }
            if (list.contains("--world-backup")) {
                worldBackup(home, opt(list, "--version", ""), valueOf(list, "--world-backup"));
                return;
            }
            if (list.contains("--world-import")) {
                worldImport(home, opt(list, "--version", ""), java.nio.file.Paths.get(valueOf(list, "--world-import")));
                return;
            }
            if (list.contains("--mod-check")) {
                checkMods(home, opt(list, "--version", ""), false);
                return;
            }
            if (list.contains("--mod-update")) {
                checkMods(home, opt(list, "--version", ""), true);
                return;
            }
            if (list.contains("--launch")) {
                String id = valueOf(list, "--launch");
                launch(home, id, list.contains("--run"));
                return;
            }
            startGui(home);
        } catch (Exception e) {
            Log.error("执行失败", e);
            System.exit(1);
        }
    }

    private static void forceNativeConsole() {
        // 只有在真实控制台里才切换编码；输出被重定向（管道/文件）时保持 UTF-8。
        if (System.console() == null) return;
        String nativeEnc = System.getProperty("native.encoding");
        String outEnc = System.getProperty("stdout.encoding");
        if (nativeEnc == null || nativeEnc.equalsIgnoreCase(outEnc)) return;
        try {
            java.io.PrintStream out = new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), true, nativeEnc);
            java.io.PrintStream err = new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.err), true, nativeEnc);
            System.setOut(out);
            System.setErr(err);
        } catch (Exception ignored) {
        }
    }

    private static String valueOf(List<String> args, String key) {
        int i = args.indexOf(key);
        if (i < 0 || i + 1 >= args.size()) throw new IllegalArgumentException(key + " 缺少参数");
        return args.get(i + 1);
    }

    private static Path detectHome() {
        String prop = System.getProperty("mcl.home");
        if (prop != null && !Compat.isBlank(prop)) return java.nio.file.Paths.get(prop).toAbsolutePath();
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !Compat.isBlank(appPath)) {
            Path p = java.nio.file.Paths.get(appPath).toAbsolutePath().getParent();
            if (p != null) return p;
        }
        try {
            Path p = java.nio.file.Paths.get(MCL.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(p)) p = p.getParent();
            if (p != null && !p.getFileName().toString().equals("classes")) return p.toAbsolutePath();
        } catch (Exception ignored) {
        }
        return java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    private static void printHelp() {
        String[] lines = {
                "MCL - Minecraft 启动器",
                "用法：",
                "  MCL                        启动图形界面",
                "  MCL --check-net            检查官方源/镜像接口与版本解析",
                "  MCL --install <版本>       安装指定版本（--no-assets 跳过素材）",
                "  MCL --fabric <版本>        安装 Fabric（--loader <加载器版本> 可指定）",
                "  MCL --forge <版本>         安装 Forge（--loader 指定版本）",
                "  MCL --neoforge <版本>      安装 NeoForge",
                "  MCL --loaders <版本>       列出该版本可用的加载器",
                "  MCL --optifine <版本>      安装 OptiFine（--edition HD_U_I6 可指定）",
                "                            已装版本有 Forge/Fabric 时作为模组放进 mods，原版则另开版本",
                "  MCL --modrinth <关键词>    搜索模组/整合包/资源包/光影",
                "                             加 --source curseforge 走 CurseForge",
                "  MCL --mod <slug>           安装模组到当前版本（--version 指定版本）",
                "  MCL --mods                 列出当前版本的本地模组",
                "  MCL --tidy                 整理版本目录（把加载器并回它所属的版本）",
                "  MCL --modpack <文件>       导入整合包（.mrpack / .zip）",
                "  MCL --export <文件>        把当前版本导出为整合包",
                "  MCL --login-ms             微软正版登录（设备代码，按提示在浏览器授权）",
                "  MCL --login-authlib <地址> 外置登录（--username/--password，皮肤站）",
                "  MCL --crash                分析最近一次崩溃（读最新日志给结论）",
                "  MCL --servers              列出 servers.dat 里的服务器",
                "  MCL --server-add <地址>    添加服务器（--name 可指定名字，自动抓图标）",
                "  MCL --server-ping <地址>   查询服务器状态（MOTD / 在线人数 / 延迟）",
                "  MCL --worlds               列出当前版本的存档",
                "  MCL --world-backup <名称>  把存档备份成 zip",
                "  MCL --world-import <zip>   导入存档压缩包",
                "  MCL --world-import-url <url> 从链接下载存档 zip 并导入",
                "  MCL --world-export <名称>  导出存档为 zip（--out 指定路径）",
                "  MCL --skin <账户名>        拉取并缓存该账户的皮肤 / 披风",
                "  MCL --set-proxy <地址>     设置代理（host:port / socks5://user:pass@host:port，空串关闭）",
                "                             可加 --limit <KB/s> 限速，--no-save 不写入配置",
                "  MCL --mod-check            检查模组重复/混装/缺失依赖",
                "  MCL --mod-update           检查模组在 Modrinth 上的更新",
                "  MCL --rename <新id>        重命名当前版本",
                "  MCL --copy <新id>          复制当前版本",
                "  MCL --launch <版本>        显示启动命令（--run 直接启动）",
                "  MCL --help                 显示帮助",
                "可选参数：",
                "  --source <modrinth|curseforge>  搜索/安装走哪个内容源，默认 modrinth",
                "  --type <mod|modpack|resourcepack|shader>  搜索的项目类型",
                "  -Dmcl.home=<目录>          指定启动器主目录",
                "  -Dmcl.curseforge.apikey=<key>  指定 CurseForge API Key",
        };
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append('\n');
        System.out.print(sb);
    }

    private static void startGui(Path home) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> Log.error("界面线程异常（" + t.getName() + "）", e));
        try {
            System.setProperty("flatlaf.useWindowDecorations", "true");
            com.formdev.flatlaf.FlatLightLaf.setup();
            UIManager.put("Component.arc", 10);
            UIManager.put("Button.arc", 10);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.trackArc", 999);
        } catch (Throwable t) {
            Log.warn("FlatLaf 初始化失败，使用默认外观：" + t.getMessage());
        }
        SwingUtilities.invokeLater(() -> {
            Settings settings = Settings.load(home);
            MainWindow window = new MainWindow(settings);
            window.setVisible(true);
        });
    }

    /** 探测两套下载源是否能通（只读，不改任何本地文件）。 */
    private static void checkNet(Path home) throws Exception {
        System.out.println("== MCL 网络探测 ==");
        for (Source src : Source.all()) {
            long t0 = System.currentTimeMillis();
            VersionManifest m = VersionManifest.fetch(src);
            System.out.printf("[%s] %s 版本数=%d 最新正式版=%s 用时=%dms%n",
                    src.displayName, m.versions.isEmpty() ? "失败" : "OK", m.versions.size(),
                    m.latestRelease, System.currentTimeMillis() - t0);
            if (m.versions.isEmpty()) continue;
            VersionManifest.Entry e = m.find(m.latestRelease);
            if (e == null) continue;
            VersionMeta meta = VersionMeta.parse(mcl.net.Http.getString(e.url));
            int allowed = 0;
            for (mcl.meta.Library lib : meta.libraries) if (lib.allowed(java.util.Collections.<String, Boolean>emptyMap())) allowed++;
            System.out.printf("  版本 %s：Java %d，库 %d/%d 可通过规则，素材索引 %s（%d 个对象估算），客户端 %.1f MB%n",
                    meta.id, meta.javaMajor, allowed, meta.libraries.size(), meta.assetIndexId,
                    meta.assetTotalSize, meta.clientSize / 1024.0 / 1024.0);
        }
        System.out.println("\n== 本机 Java ==");
        for (mcl.game.JavaFinder.JavaInfo j : mcl.game.JavaFinder.findAll()) {
            System.out.printf("  Java %-10s 主版本 %-3d %s%n", j.version, j.major, j.exe);
        }
        System.out.println("\n自检结束。");
    }

    private static void install(Path home, String id, boolean assets) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        long t0 = System.currentTimeMillis();
        game.install(id, assets, new Installer.Listener() {
            private long last;

            @Override
            public void onStage(String stage) {
                System.out.println("[阶段] " + stage);
            }

            @Override
            public void onProgress(int done, int total, long bytes, long bytesTotal, String current) {
                long now = System.currentTimeMillis();
                if (now - last < 500 && done < total) return;
                last = now;
                String pct = bytesTotal > 0 ? String.format("%.1f%%", bytes * 100.0 / bytesTotal) : "-";
                System.out.printf("\r  文件 %d/%d  %s  %.1f/%.1f MB  %s      ", done, total, pct,
                        bytes / 1048576.0, bytesTotal / 1048576.0, current);
            }
        });
        System.out.printf("%n安装完成 %s，用时 %d 秒%n", id, (System.currentTimeMillis() - t0) / 1000);
        System.out.println("游戏目录：" + game.gameDir());
    }

    private static void installFabric(Path home, String gameVersion, String loaderVersion) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String loader = loaderVersion;
        if (loader == null) {
            java.util.List<mcl.install.FabricInstaller.Loader> loaders = mcl.install.FabricInstaller.loaders(gameVersion, false);
            loader = loaders.get(0).version;
            System.out.println("使用最新 Fabric 加载器 " + loader);
        }
        String id = game.installModLoader(gameVersion, loader, false, new Installer.Listener() {
            private long last;

            @Override
            public void onStage(String stage) {
                System.out.println("[阶段] " + stage);
            }

            @Override
            public void onProgress(int done, int total, long bytes, long bytesTotal, String current) {
                long now = System.currentTimeMillis();
                if (now - last < 500 && done < total) return;
                last = now;
                System.out.printf("\r  %d/%d  %.1f/%.1f MB  %s      ", done, total,
                        bytes / 1048576.0, bytesTotal / 1048576.0, current);
            }
        });
        String finalId = game.adoptLoader(gameVersion, id);
        System.out.println("\n安装完成：" + finalId + "（加载器已并进这个版本的配置）");
    }

    private static void installForge(Path home, String gameVersion, String loaderVersion, boolean neo)
            throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String kind = neo ? "NeoForge" : "Forge";
        String loader = loaderVersion;
        if (loader == null) {
            java.util.List<mcl.install.ForgeInstaller.Loader> loaders = mcl.install.ForgeInstaller.loaders(gameVersion, neo);
            loader = loaders.get(0).version;
            System.out.println("使用最新 " + kind + " " + loader);
        }
        String id = game.installForge(gameVersion, loader, neo, new Installer.Listener() {
            private long last;

            @Override
            public void onStage(String stage) {
                System.out.println("\n[阶段] " + stage);
            }

            @Override
            public void onProgress(int done, int total, long bytes, long bytesTotal, String current) {
                long now = System.currentTimeMillis();
                if (now - last < 500 && done < total) return;
                last = now;
                System.out.printf("\r  %d/%d  %.1f/%.1f MB  %s      ", done, total,
                        bytes / 1048576.0, bytesTotal / 1048576.0, current);
            }
        });
        String finalId = game.adoptLoader(gameVersion, id);
        System.out.println("\n安装完成：" + finalId + "（加载器已并进这个版本的配置）");
    }

    private static void listLoaders(String gameVersion) throws Exception {
        System.out.println("== " + gameVersion + " 可用加载器 ==");
        try {
            java.util.List<mcl.install.FabricInstaller.Loader> fabric = mcl.install.FabricInstaller.loaders(gameVersion, false);
            System.out.println("Fabric   最新 " + fabric.get(0).version + "，共 " + fabric.size() + " 个");
        } catch (Exception e) {
            System.out.println("Fabric   查询失败：" + e.getMessage());
        }
        try {
            java.util.List<mcl.install.FabricInstaller.Loader> quilt = mcl.install.FabricInstaller.loaders(gameVersion, true);
            System.out.println("Quilt    最新 " + quilt.get(0).version + "，共 " + quilt.size() + " 个");
        } catch (Exception e) {
            System.out.println("Quilt    查询失败：" + e.getMessage());
        }
        try {
            java.util.List<mcl.install.ForgeInstaller.Loader> forge = mcl.install.ForgeInstaller.loaders(gameVersion, false);
            mcl.install.ForgeInstaller.Loader recommended = forge.stream().filter(l -> l.recommended).findFirst().orElse(null);
            System.out.println("Forge    最新 " + forge.get(0).version
                    + (recommended == null ? "" : "，推荐 " + recommended.version)
                    + "，共 " + forge.size() + " 个");
        } catch (Exception e) {
            System.out.println("Forge    查询失败：" + e.getMessage());
        }
        try {
            java.util.List<mcl.install.ForgeInstaller.Loader> neo = mcl.install.ForgeInstaller.loaders(gameVersion, true);
            System.out.println("NeoForge 最新 " + neo.get(0).version + "，共 " + neo.size() + " 个");
        } catch (Exception e) {
            System.out.println("NeoForge 查询失败：" + e.getMessage());
        }
    }

    private static String opt(List<String> args, String key, String def) {
        int i = args.indexOf(key);
        return (i >= 0 && i + 1 < args.size()) ? args.get(i + 1) : def;
    }

    private static String currentVersion(Settings settings, String explicit) {
        if (explicit != null && !Compat.isBlank(explicit)) return explicit;
        String v = settings.profile() == null ? "" : settings.profile().selectedVersion;
        if (v == null || Compat.isBlank(v)) throw new IllegalArgumentException("请用 --version 指定版本 id");
        return v;
    }

    private static Installer.Listener echoListener() {
        return new Installer.Listener() {
            private long last;

            @Override
            public void onStage(String stage) {
                System.out.print("\r\033[K");
                System.out.println("[阶段] " + stage);
            }

            @Override
            public void onProgress(int done, int total, long bytes, long bytesTotal, String current) {
                long now = System.currentTimeMillis();
                if (now - last < 400 && done < total) return;
                last = now;
                System.out.printf("\r  %d/%d  %.1f/%.1f MB  %s      ", done, total,
                        bytes / 1048576.0, bytesTotal / 1048576.0, current);
            }
        };
    }

    private static void searchModrinth(mcl.mod.ModSource src, String query, String type, String mc,
                                       String loader) throws Exception {
        // 中文查询走多路召回（锚定 / 本地中文索引 / 译名扩召回 / 原文兜底）
        mcl.mod.ZhRecall.Result r = mcl.mod.ZhRecall.search(src, query, type, mc, loader,
                "relevance", 0, 15);
        StringBuilder head = new StringBuilder("== ").append(src.label).append(" 搜索「").append(query).append("」");
        String note = r.summary();
        if (!note.isEmpty()) head.append("（").append(note).append("）");
        head.append(" 类型=").append(mcl.mod.Modrinth.typeLabel(type))
                .append("  共 ").append(r.merged.totalHits).append(" 个结果 ==");
        System.out.println(head);
        int i = 0;
        for (mcl.mod.Modrinth.Hit h : r.merged.hits) {
            i++;
            String tag = "";
            if ("anchor".equals(h.matchedBy)) tag = "   ★ 精确匹配";
            else if ("zhindex".equals(h.matchedBy)) tag = "   · 中文索引";
            String slug = h.slug.length() > 25 ? h.slug.substring(0, 25) : h.slug;
            System.out.printf("  %2d. %-26s %-10s %10d 次下载  %s%s%n",
                    i, slug, mcl.mod.Modrinth.typeLabel(h.projectType), h.downloads, h.displayTitle(), tag);
        }
        if (r.merged.hits.isEmpty()) {
            System.out.println("  （没有结果，试试放宽 --mc / --modloader，或用 --source 换个内容源）");
        }
    }

    private static void installMod(Path home, String version, String slug, String type,
                                   mcl.mod.ModSource src) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String id = currentVersion(settings, version);
        System.out.println("把 " + slug + " 安装到版本 " + id + "（源：" + src.label + "）");
        mcl.mod.ModInstaller.Result result = game.installModrinth(id, slug, type, src, true, echoListener());
        System.out.println();
        System.out.println(result.summary());
        System.out.println("目录：" + game.contentDir(id, type));
        for (String f : result.installed) System.out.println("  + " + f);
    }

    private static void listMods(Path home, String version) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String id = currentVersion(settings, version);
        mcl.game.VersionManager.Info info = game.versions().describe(id);
        java.util.List<mcl.mod.Mods.Entry> list = mcl.mod.Mods.list(game.modsDir(id));
        System.out.println("== " + id + "（" + info + "）的模组 ==");
        System.out.println("目录：" + game.modsDir(id));
        if (list.isEmpty()) System.out.println("  （空）");
        for (mcl.mod.Mods.Entry m : list) {
            System.out.printf("  [%s] %-50s %s%n", m.enabled ? "启用" : "禁用", m.displayName(),
                    mcl.ui.Ui.bytes(m.size));
        }
    }

    /** 整理版本目录：整合包的加载器收进实例、独立加载器版本并回它所属的原版。 */
    private static void tidyVersions(Path home) {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        System.out.println("游戏目录：" + game.gameDir());
        for (String id : mcl.modpack.Modpack.tidyInstances(game.gameDir())) {
            System.out.println("  整合包的加载器已收进实例目录：" + id);
        }
        for (String id : game.migrateStandaloneLoaders()) {
            System.out.println("  独立加载器版本已并回原版：" + id);
        }
        for (mcl.game.VersionManager.InstalledVersion v : game.versions().list()) {
            System.out.println("  " + v.id + "  ——  " + game.versions().describe(v.id));
        }
    }

    private static void loginMicrosoft(Path home) throws Exception {
        Settings settings = Settings.load(home);
        mcl.auth.MicrosoftAuth.DeviceCode code = mcl.auth.MicrosoftAuth.start();
        System.out.println("== 微软正版登录 ==");
        System.out.println("1. 打开浏览器访问：" + code.verificationUri);
        System.out.println("2. 输入代码：" + code.userCode);
        System.out.println("3. 用要登录的微软账户完成授权（" + (code.expiresIn / 60) + " 分钟内有效）");
        System.out.println("等待授权…");
        long deadline = System.currentTimeMillis() + code.expiresIn * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                mcl.auth.MicrosoftAuth.MsaToken token = mcl.auth.MicrosoftAuth.poll(code);
                Account a = mcl.auth.MicrosoftAuth.toAccount(token);
                saveAccount(settings, a);
                System.out.println("登录成功：" + a.username + "（" + a.uuid + "）");
                return;
            } catch (mcl.auth.MicrosoftAuth.PendingException e) {
                Thread.sleep(Math.max(2, code.interval) * 1000L);
            }
        }
        throw new java.io.IOException("授权超时，请重新运行 --login-ms");
    }

    private static void loginAuthlib(Path home, String apiRoot, String username, String password) throws Exception {
        Settings settings = Settings.load(home);
        mcl.auth.AuthlibInjector.Server server = mcl.auth.AuthlibInjector.metadata(apiRoot);
        System.out.println("皮肤站：" + server.serverName + "（" + server.apiRoot + "）");
        if (Compat.isBlank(username) || Compat.isBlank(password)) {
            System.out.println("该服务器需要账号密码：请加 --username <账号> --password <密码>");
            return;
        }
        Account a = mcl.auth.AuthlibInjector.authenticate(server, username, password, "");
        saveAccount(settings, a);
        Path jar = mcl.auth.AuthlibInjector.ensureJar(settings.launcherDir.resolve("libraries").resolve("mcl"));
        System.out.println("登录成功：" + a.username + "（" + a.uuid + "）");
        System.out.println("启动时会加参数：" + mcl.auth.AuthlibInjector.agentArg(jar, a.apiRoot));
    }

    private static void saveAccount(Settings settings, Account account) {
        settings.accounts.removeIf(x -> x.username.equalsIgnoreCase(account.username)
                && account.type.equals(x.type));
        settings.accounts.add(account);
        settings.selectedAccount = account.username;
        settings.save();
    }

    private static void crashReport(Path home, String version) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String id = currentVersion(settings, version);
        mcl.meta.VersionMeta meta = null;
        try {
            meta = game.versions().load(id);
        } catch (Exception ignored) {
        }
        Path run = game.runDir(id);
        mcl.game.CrashAnalyzer.Report report = mcl.game.CrashAnalyzer.analyze(run, -1, meta);
        System.out.println("== " + id + " 崩溃分析 ==");
        System.out.println("目录：" + run);
        System.out.println();
        System.out.print(report.toText());
    }

    private static void listServers(Path home) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        Path dir = game.gameDir();
        java.util.List<mcl.game.Servers.Server> servers = mcl.game.Servers.list(dir);
        System.out.println("== 服务器列表（" + mcl.game.Servers.file(dir) + "）==");
        if (servers.isEmpty()) System.out.println("  （空）");
        for (mcl.game.Servers.Server s : servers) {
            System.out.printf("  %-24s %s%n", s.label(), s.ip);
        }
    }

    private static void addServer(Path home, String address, String name) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        mcl.game.Servers.Server s = new mcl.game.Servers.Server();
        s.ip = address.trim();
        s.name = name == null || Compat.isBlank(name) ? s.ip : name.trim();
        mcl.game.ServerPing.Result res = mcl.game.ServerPing.ping(s.ip);
        if (res.ok && !Compat.isBlank(res.favicon)) s.icon = res.favicon;
        mcl.game.Servers.add(game.gameDir(), s);
        System.out.println("已写入 servers.dat：" + s.label() + " -> " + s.ip
                + (res.ok ? "（" + res.shortText() + "）" : "（当前连不上：" + res.error + "）"));
    }

    private static void pingServer(String address) throws Exception {
        mcl.game.ServerPing.Result res = mcl.game.ServerPing.ping(address);
        System.out.println("== " + address + " ==");
        if (!res.ok) {
            System.out.println("  连接失败：" + res.error);
            return;
        }
        System.out.println("  MOTD：" + (Compat.isBlank(res.motd) ? "（空）" : res.motd));
        System.out.println("  在线：" + res.playersText());
        System.out.println("  版本：" + (Compat.isBlank(res.versionName) ? "未知" : res.versionName));
        System.out.println("  延迟：" + res.latencyMs + " ms");
        System.out.println("  图标：" + (Compat.isBlank(res.favicon) ? "无" : res.favicon.length() + " 字节 base64"));
    }

    private static void listWorlds(Path home, String version) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String id = currentVersion(settings, version);
        Path run = game.runDir(id);
        java.util.List<mcl.game.Worlds.World> list = mcl.game.Worlds.list(run);
        System.out.println("== " + id + " 的存档 ==");
        System.out.println("目录：" + mcl.game.Worlds.savesDir(run));
        if (list.isEmpty()) System.out.println("  （空）");
        for (mcl.game.Worlds.World w : list) {
            System.out.printf("  %-24s %-55s %s%n", w.name, w.subtitle(), mcl.ui.Ui.bytes(w.size));
        }
    }

    private static void worldBackup(Path home, String version, String folder) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String id = currentVersion(settings, version);
        Path run = game.runDir(id);
        mcl.game.Worlds.World target = null;
        for (mcl.game.Worlds.World w : mcl.game.Worlds.list(run)) {
            if (w.folder.equals(folder) || w.name.equals(folder)) {
                target = w;
                break;
            }
        }
        if (target == null) throw new IllegalArgumentException("找不到存档：" + folder);
        Path zip = mcl.game.Worlds.backup(game.gameDir(), target);
        System.out.println("已备份 " + target.name + " -> " + zip + "（" + Files.size(zip) / 1024 + " KB）");
    }

    private static void worldImport(Path home, String version, Path zip) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String id = currentVersion(settings, version);
        mcl.game.Worlds.World w = mcl.game.Worlds.importZip(game.runDir(id), zip);
        System.out.println("已导入存档：" + w.name + " -> " + w.dir);
    }

    /** 设置 / 查看网络代理与限速（--set-proxy "host:port"，--set-proxy "" 关闭）。 */
    private static void setProxy(Path home, String spec, String limit, boolean noSave) throws Exception {
        Settings settings = Settings.load(home);
        boolean on = mcl.net.Net.configureFromString(settings, spec);
        if (limit != null && !Compat.isBlank(limit)) {
            settings.speedLimitKbps = Integer.parseInt(limit.trim());
        }
        mcl.net.Net.apply(settings);
        if (!noSave) settings.save();
        System.out.println("代理：" + mcl.net.Http.proxyLabel());
        System.out.println("限速：" + (settings.speedLimitKbps <= 0 ? "不限" : settings.speedLimitKbps + " KB/s"));
        if (!on) System.out.println("（未启用代理，仅保留直连）");
        System.out.println("连通性：" + mcl.net.Net.probe());
    }

    /** 拉取账户皮肤并缓存，同时打印纹理信息。 */
    private static void fetchSkin(Path home, String username) throws Exception {
        Settings settings = Settings.load(home);
        Account a = null;
        for (Account x : settings.accounts) {
            if (x.username.equalsIgnoreCase(username)) {
                a = x;
                break;
            }
        }
        if (a == null) {
            System.out.println("找不到账户：" + username);
            System.out.print("现有账户：");
            for (Account x : settings.accounts) System.out.print(x.username + "(" + x.typeLabel() + ") ");
            System.out.println();
            return;
        }
        System.out.println("账户：" + a.username + " · " + a.typeLabel() + " · UUID " + a.uuid);
        if ("offline".equals(a.type)) {
            System.out.println("离线账户没有远端皮肤（界面里会显示内置 Steve 占位）");
            return;
        }
        if ("msa".equals(a.type) && mcl.auth.MicrosoftAuth.ensureValid(a)) {
            settings.save();
            System.out.println("访问令牌已续期");
        }
        mcl.auth.ProfileSkins.Skin s = mcl.auth.ProfileSkins.fetch(a);
        System.out.println("名称：" + s.name);
        System.out.println("皮肤：" + (s.skinUrl.isEmpty() ? "未设置" : s.skinUrl));
        System.out.println("模型：" + s.modelLabel());
        System.out.println("披风：" + (s.hasCape() ? s.capeUrl : "无"));
        java.awt.image.BufferedImage img = mcl.auth.ProfileSkins.sync(s, settings.launcherDir());
        if (img == null) {
            System.out.println("纹理下载失败或该账户没有皮肤");
        } else {
            System.out.println("已缓存：" + img.getWidth() + "x" + img.getHeight()
                    + " -> " + mcl.auth.ProfileSkins.cacheDir(settings.launcherDir()));
        }
    }

    private static void worldExport(Path home, String version, String folder, Path out) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String id = currentVersion(settings, version);
        Path run = game.runDir(id);
        mcl.game.Worlds.World target = null;
        for (mcl.game.Worlds.World w : mcl.game.Worlds.list(run)) {
            if (w.folder.equalsIgnoreCase(folder) || w.name.equalsIgnoreCase(folder)) {
                target = w;
                break;
            }
        }
        if (target == null) {
            System.out.println("找不到存档：" + folder);
            return;
        }
        Path dest = out == null || Compat.isBlank(out.toString())
                ? settings.launcherDir().resolve("backup").resolve(target.folder + ".zip")
                : out;
        if (!dest.toString().toLowerCase().endsWith(".zip")) dest = java.nio.file.Paths.get(dest + ".zip");
        Dirs.ensure(dest.toAbsolutePath().getParent());
        mcl.game.Worlds.zipDir(target.dir, dest);
        System.out.println("已导出：" + dest + "（" + Files.size(dest) / 1024 + " KB）");
    }

    private static void worldImportUrl(Path home, String version, String url) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String id = currentVersion(settings, version);
        Path dir = settings.launcherDir().resolve("cache").resolve("worlds");
        Dirs.ensure(dir);
        String name = url.substring(url.lastIndexOf('/') + 1);
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        if (!name.toLowerCase().endsWith(".zip")) name = "world-" + System.currentTimeMillis() + ".zip";
        Path tmp = dir.resolve(name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        System.out.println("下载 " + url);
        mcl.net.Http.download(url, tmp, null, 0, null);
        System.out.println("已下载 " + Files.size(tmp) / 1024 + " KB，开始导入");
        mcl.game.Worlds.World w = mcl.game.Worlds.importZip(game.runDir(id), tmp);
        System.out.println("已导入存档：" + w.name + " -> " + w.dir);
    }

    private static void checkMods(Path home, String version, boolean updates) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String id = currentVersion(settings, version);
        mcl.game.VersionManager.Info info = game.versions().describe(id);
        Path dir = game.modsDir(id);
        java.util.List<mcl.mod.ModInspector.Meta> mods = mcl.mod.ModInspector.scan(dir);
        System.out.println("== " + id + "（" + info + "）模组检查 ==");
        System.out.println("目录：" + dir + "，共 " + mods.size() + " 个 jar");
        for (mcl.mod.ModInspector.Meta m : mods) {
            System.out.printf("  [%-8s] %-34s %-16s 依赖=%s%n", m.loaderLabel(),
                    m.name.isEmpty() ? m.file : m.name,
                    m.version.isEmpty() ? "?" : m.version,
                    m.required.isEmpty() ? "-" : String.join(",", m.required));
        }
        java.util.List<mcl.mod.ModInspector.Issue> issues = mcl.mod.ModInspector.checkConflicts(mods);
        System.out.println("\n== 冲突检测 ==");
        if (issues.isEmpty()) System.out.println("  没有发现问题");
        for (mcl.mod.ModInspector.Issue i : issues) System.out.println("  [" + i.level + "] " + i.title + "\n      " + i.detail);
        if (!updates) return;
        System.out.println("\n== 更新检查（Modrinth）==");
        java.util.List<mcl.mod.ModInspector.Update> ups = mcl.mod.ModInspector.checkUpdates(mods, info.mcVersion, info.modrinthLoader(), null);
        if (ups.isEmpty()) System.out.println("  没有可用更新（或模组不在 Modrinth 上）");
        for (mcl.mod.ModInspector.Update u : ups) {
            System.out.printf("  %s  %s -> %s  (%s)%n", u.mod.name, u.mod.version, u.latestVersion, u.mod.file);
        }
    }

    private static void importModpack(Path home, Path zip) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        mcl.modpack.Modpack.Info info = mcl.modpack.Modpack.inspect(zip);
        System.out.println("整合包：" + info.describe() + "  格式=" + info.format);
        String id = game.importModpack(zip, "", null, null, echoListener());
        settings.profile().selectedVersion = id;
        settings.save();
        System.out.println("\n安装完成，实例版本 id：" + id);
        System.out.println("实例目录：" + game.runDir(id));
    }

    private static void exportModpack(Path home, Path target) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String id = currentVersion(settings, "");
        Path out = game.exportModpack(id, target, id, "1.0", "MCL", echoListener());
        System.out.println("已导出整合包：" + out + "（" + Files.size(out) / 1024 + " KB）");
    }

    private static void installOptiFine(Path home, String mcVersion, String edition) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        // 传进来的如果是已装版本，就按「给这个版本加 OptiFine」处理：
        // 它有 Forge / Fabric 时是往 mods 里放一个 jar，不会另开版本。
        if (game.versions().isInstalled(mcVersion)) {
            String result = game.installOptiFineInto(mcVersion, edition, echoListener());
            if (game.versions().isInstalled(result)) {
                settings.profile().selectedVersion = result;
                settings.save();
                System.out.println("\nOptiFine 安装完成：" + result);
            } else {
                System.out.println("\n" + result);
            }
            return;
        }
        String id = game.installOptiFine(mcVersion, edition, echoListener());
        settings.profile().selectedVersion = id;
        settings.save();
        System.out.println("\nOptiFine 安装完成：" + id);
    }

    private static void versionAction(Path home, String newId, boolean copy) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        String id = currentVersion(settings, "");
        if (copy) {
            game.versions().copy(id, newId);
        } else {
            game.versions().rename(id, newId);
        }
        settings.profile().selectedVersion = newId;
        settings.save();
        System.out.println((copy ? "已复制为 " : "已重命名为 ") + newId);
    }

    private static void launch(Path home, String id, boolean run) throws Exception {
        Settings settings = Settings.load(home);
        Game game = new Game(settings);
        List<String> cmd = game.buildCommand(id, new Game.Stage() {
            @Override
            public void onStage(String stage) {
                System.out.println("[阶段] " + stage);
            }

            @Override
            public void onProgress(int done, int total, long bytes, long bytesTotal, String file) {
                System.out.printf("\r  %d/%d  %s  %s      ", done, total, Ui_bytes(bytes), Ui_bytes(bytesTotal));
            }
        });
        System.out.println();
        System.out.println("启动命令（" + cmd.size() + " 段）：");
        for (String s : cmd) System.out.println("  " + s);
        if (run) {
            Process process = game.launchWith(cmd, id, new mcl.game.GameLauncher.Listener() {
                @Override
                public void onLog(String line) {
                    System.out.println(line);
                }

                @Override
                public void onExit(int code) {
                    System.out.println("游戏退出，代码 " + code);
                }
            });
            int code = process.waitFor();
            // 给日志线程一点时间把最后几行刷完
            Thread.sleep(500);
            System.out.println("游戏进程结束，退出码 " + code);
        } else {
            // 只打印命令时 buildCommand 也起过本地皮肤服务，这里得收掉
            game.stopSkinServer();
        }
    }

    private static String Ui_bytes(long b) {
        if (b <= 0) return "-";
        if (b < 1048576) return (b / 1024) + " KB";
        return String.format("%.1f MB", b / 1048576.0);
    }
}
