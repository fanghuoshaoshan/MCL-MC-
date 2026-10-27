package mcl.game;

import mcl.util.Dirs;
import mcl.util.Compat;

import mcl.config.Account;
import mcl.config.Profile;
import mcl.config.Settings;
import mcl.install.Installer;
import mcl.install.JavaInstaller;
import mcl.meta.VersionManifest;
import mcl.meta.VersionMeta;
import mcl.net.Http;
import mcl.net.Source;
import mcl.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 安装 / 启动的门面，UI 与 CLI 共用。 */
public class Game {
    /** 长流程（下载 Java 运行时等）的阶段与进度回调。 */
    public interface Stage {
        void onStage(String stage);

        default void onProgress(int done, int total, long bytes, long totalBytes, String file) {
        }
    }

    private final Settings settings;

    /** 离线账户用本地皮肤时起的临时服务，游戏退出就关掉。 */
    private mcl.auth.LocalSkinServer skinServer;

    public Game(Settings settings) {
        this.settings = settings;
    }

    /** 关掉本地皮肤服务（启动失败、只打印命令、退出游戏时都会走到）。 */
    public void stopSkinServer() {
        mcl.auth.LocalSkinServer s = skinServer;
        skinServer = null;
        if (s != null) s.stop();
    }

    public Settings settings() {
        return settings;
    }

    public Source source() {
        return Source.of(settings.downloadSource);
    }

    public Path gameDir() {
        return settings.gameDir(settings.profile());
    }

    /**
     * 实际运行目录。开启版本隔离后每个版本使用 versions/&lt;id&gt; 作为工作目录，
     * 存档、模组、配置互不干扰（与 HMCL 的版本隔离一致）。
     * 整合包实例目录里带有 .mcl-isolate 标记，即使全局没开隔离也始终独立。
     */
    public Path runDir(String versionId) {
        Profile p = settings.profile();
        boolean isolated = p != null && p.isolate;
        if (!isolated && versionId != null && !Compat.isBlank(versionId)) {
            isolated = Files.isRegularFile(gameDir().resolve("versions").resolve(versionId).resolve(".mcl-isolate"));
        }
        if (isolated && versionId != null && !Compat.isBlank(versionId)) {
            return gameDir().resolve("versions").resolve(versionId);
        }
        return gameDir();
    }

    /** 某个版本的模组目录（遵循版本隔离）。 */
    public Path modsDir(String versionId) {
        return runDir(versionId).resolve("mods");
    }

    /** 某个版本的资源包 / 光影目录。 */
    public Path contentDir(String versionId, String projectType) {
        return runDir(versionId).resolve(mcl.mod.Modrinth.dirOf(projectType));
    }

    /**
     * 为某个版本准备好运行目录，返回「从共用目录接管了什么」。
     *
     * <p>不隔离的版本只保证目录存在，什么都不复制。隔离的版本会补齐
     * {@code mods / saves / config} 等子目录，并在**第一次**初始化时把共用目录里
     * 已有的存档、资源包、options.txt 复制过来 —— 否则用户开隔离后看到的就是「存档全没了」。
     *
     * <p>有文件复制，属于重活，只能在后台线程 / 启动前调用。
     */
    public Isolation.Result prepareVersionDir(String versionId) {
        if (Compat.isBlank(versionId)) return Isolation.Result.none();
        Path run = runDir(versionId);
        Path versionDir = gameDir().resolve("versions").resolve(versionId);
        try {
            Dirs.ensure(run);
        } catch (IOException e) {
            Log.warn("创建运行目录失败：" + e.getMessage());
            return Isolation.Result.none();
        }
        if (!run.equals(versionDir)) return Isolation.Result.none();   // 没开隔离，共用目录不需要准备
        try {
            return Isolation.prepareAndAdopt(gameDir(), run);
        } catch (IOException e) {
            Log.warn("初始化版本隔离目录失败：" + e.getMessage());
            return Isolation.Result.none();
        }
    }

    public VersionManager versions() {
        return new VersionManager(gameDir());
    }

    /** 从版本清单拉取某个版本的元数据（不落盘）。 */
    public VersionMeta fetchMeta(String versionId) throws IOException {
        VersionManifest manifest = VersionManifest.fetch(source());
        VersionManifest.Entry entry = manifest.find(versionId);
        if (entry == null) throw new IOException("版本清单中找不到 " + versionId);
        String json = Http.getString(source().map(entry.url), entry.url);
        return VersionMeta.parse(json);
    }

    public Path install(String versionId, boolean withAssets, Installer.Listener listener) throws Exception {
        Path dir = gameDir();
        Dirs.ensure(dir);
        listener.onStage("获取版本信息 " + versionId);
        VersionMeta meta = fetchMeta(versionId);
        Installer installer = new Installer(dir, source(), settings.downloadThreads);
        Path installed = installer.install(meta, withAssets, listener);
        // 重新下载会把 <id>.json 覆盖成纯原版。这个版本要是装了加载器（.mcl-profiles），
        // 那份配置就白丢了 —— 补回去，用户看到的还是「1.21.11 · Fabric」。
        restoreActiveProfile(dir, versionId);
        prepareVersionDir(versionId);   // 新版本立刻拿到独立的 mods / saves 目录
        return installed;
    }

    /** 版本目录里有加载器配置时，把当前生效的那份重新写成 {@code <id>.json}。 */
    private void restoreActiveProfile(Path dir, String versionId) {
        Path vdir = dir.resolve("versions").resolve(versionId);
        if (!VersionProfiles.has(vdir)) return;
        String active = VersionProfiles.active(vdir);
        if (VersionProfiles.VANILLA.equals(active)) return;
        try {
            VersionProfiles.setActive(vdir, versionId, active);
            Log.info("重新下载 " + versionId + " 后已恢复加载器配置「" + VersionProfiles.label(active) + "」");
        } catch (IOException e) {
            Log.warn("恢复加载器配置失败：" + e.getMessage());
        }
    }

    /**
     * 安装 Fabric / Quilt 加载器：原版未安装时先装原版，再写入加载器版本 JSON 并下载其依赖库。
     *
     * @return 新的版本 id（例如 fabric-loader-0.16.9-1.20.2）
     */
    public String installModLoader(String gameVersion, String loaderVersion, boolean quilt,
                                   Installer.Listener listener) throws Exception {
        Path dir = gameDir();
        Dirs.ensure(dir);
        if (!versions().isInstalled(gameVersion)) {
            listener.onStage("先安装原版 " + gameVersion);
            install(gameVersion, true, listener);
        }
        listener.onStage("获取 " + (quilt ? "Quilt" : "Fabric") + " 加载器配置");
        VersionMeta profile = mcl.install.FabricInstaller.profile(gameVersion, loaderVersion, quilt, source());
        listener.onStage("安装 " + profile.id);
        Installer installer = new Installer(dir, source(), settings.downloadThreads);
        installer.install(profile, false, listener);
        // 不 prepareVersionDir：这个版本目录是临时的，马上就会被并进原版。
        // 给它建隔离目录会从共用目录接管一份 saves / options.txt，
        // 清理时它就不再是「空壳」，最后变成主页上多出来的一个 Forge / Fabric 条目。
        return profile.id;
    }

    /**
     * 安装 Forge / NeoForge：原版未安装时先装原版，再解析官方安装器并执行 processors。
     *
     * @return 新的版本 id（例如 1.20.2-forge-48.1.0）
     */
    public String installForge(String gameVersion, String loaderVersion, boolean neoforge,
                               Installer.Listener listener) throws Exception {
        Path dir = gameDir();
        Dirs.ensure(dir);
        if (!versions().isInstalled(gameVersion)) {
            listener.onStage("先安装原版 " + gameVersion);
            install(gameVersion, true, listener);
        }
        Path cache = settings.launcherDir().resolve("cache").resolve("installers");
        String installedId = mcl.install.ForgeInstaller.install(dir, cache, source(), settings.downloadThreads,
                gameVersion, loaderVersion, neoforge, listener);
        // 同上：加载器版本目录只是 processors 的运行场地，别给它建隔离目录
        return installedId;
    }

    /**
     * 按整合包要求准备"原版 + 加载器"，返回最终可启动的版本 id。
     * kind 为 vanilla 时只保证原版安装。
     */
    public String installLoaderChain(String mcVersion, String kind, String loaderVersion,
                                     Installer.Listener listener) throws Exception {
        if (!versions().isInstalled(mcVersion)) {
            listener.onStage("先安装原版 " + mcVersion);
            install(mcVersion, true, listener);
        }
        if (kind == null || Compat.isBlank(kind) || "vanilla".equalsIgnoreCase(kind)) return mcVersion;
        String version = loaderVersion;
        boolean quilt = "quilt".equalsIgnoreCase(kind);
        boolean neo = "neoforge".equalsIgnoreCase(kind);
        boolean forge = "forge".equalsIgnoreCase(kind) || neo;
        if (version == null || Compat.isBlank(version)) {
            if (forge) {
                version = mcl.install.ForgeInstaller.loaders(mcVersion, neo).get(0).version;
            } else {
                version = mcl.install.FabricInstaller.loaders(mcVersion, quilt).get(0).version;
            }
            Log.info("未指定加载器版本，使用最新 " + kind + " " + version);
        }
        return forge ? installForge(mcVersion, version, neo, listener)
                : installModLoader(mcVersion, version, quilt, listener);
    }

    /**
     * 给一个**已安装的原版**装加载器，结果并进它自己的目录。
     *
     * <p>界面上用户做的是「给 1.21.11 装 Fabric」，那就不该多出一个
     * {@code fabric-loader-0.20.1-1.21.11} 摆在版本列表里。所以流程是：
     * 先在临时目录把加载器装好（Forge 的 processors 必须有个真实版本目录才跑得起来），
     * 再把它的 mainClass / 库 / 参数合进原版目录的一份 profile，
     * 设为生效，最后删掉那个临时版本。
     *
     * @return 传入的 gameVersion（版本列表里还是同一个版本）
     */
    public String installLoaderInto(String gameVersion, String kind, String loaderVersion,
                                    Installer.Listener listener) throws Exception {
        Path dir = gameDir();
        Dirs.ensure(dir);
        if (!versions().isInstalled(gameVersion)) {
            listener.onStage("先安装原版 " + gameVersion);
            install(gameVersion, true, listener);
        }
        Path vdir = dir.resolve("versions").resolve(gameVersion);
        // 装第一个加载器时把原版 JSON 存一份：后面再装别的加载器不能拿合并过的当原版
        VersionProfiles.ensureVanilla(vdir, gameVersion);

        boolean neo = "neoforge".equalsIgnoreCase(kind);
        boolean forge = "forge".equalsIgnoreCase(kind) || neo;
        String loaderId = forge
                ? installForge(gameVersion, loaderVersion, neo, listener)
                : installModLoader(gameVersion, loaderVersion, "quilt".equalsIgnoreCase(kind), listener);

        listener.onStage("把 " + kind + " 并进 " + gameVersion);
        return adoptLoader(gameVersion, loaderId);
    }

    /**
     * 把刚装好的独立加载器版本并进 {@code baseId} 的配置，并删掉那个临时版本。
     *
     * <p>装加载器必须有个真实的版本目录（Forge 的 processors 在那儿跑），
     * 但用户不该在版本列表里看到它 —— 这一步就是把「装好的东西」收归到目标版本名下。
     *
     * @return 传回的 baseId
     */
    public String adoptLoader(String baseId, String loaderId) throws Exception {
        Path dir = gameDir();
        Path vdir = dir.resolve("versions").resolve(baseId);
        VersionProfiles.ensureVanilla(vdir, baseId);
        String name = VersionProfiles.absorb(dir, baseId, loaderId);
        VersionProfiles.setActive(vdir, baseId, name);
        try {
            // 临时目录里可能还留着隔离初始化接管来的东西，搬进版本目录再删，别直接删
            VersionProfiles.discard(dir, vdir, loaderId);
            Log.info("已清理临时加载器版本 " + loaderId);
        } catch (IOException e) {
            Log.warn("清理临时加载器版本失败（不影响使用）：" + e.getMessage());
        }
        prepareVersionDir(baseId);
        return baseId;
    }

    /**
     * 把以前装成独立版本的加载器（{@code versions/fabric-loader-x-y} 这种）收进原版目录。
     *
     * <p>老版本装加载器会多出一个版本条目。现在改成「加载器属于某个版本」，
     * 启动时顺手迁移一次：并进原版的 profile，再删掉那个目录。
     * 被整合包引用着的（它自己有私有副本，用不着外面这份）不动；
     * 目录里剩下的存档 / 模组会先搬进原版目录 —— 删的是条目，不是用户的东西。
     *
     * @return 迁移掉的版本 id
     */
    public List<String> migrateStandaloneLoaders() {
        List<String> migrated = new ArrayList<>();
        Path versions = gameDir().resolve("versions");
        for (VersionManager.InstalledVersion v : versions().list()) {
            VersionManager.Info self = VersionManager.idInfo(v.id);
            if ("vanilla".equals(self.loaderKind)) continue;               // 原版 / 整合包
            if (Files.isRegularFile(v.dir.resolve(Isolation.FORCED_MARKER))) continue;
            if (Compat.isBlank(self.mcVersion) || !versions().isInstalled(self.mcVersion)) continue;
            if (referencedByOthers(v.id)) continue;
            try {
                Path base = versions.resolve(self.mcVersion);
                VersionProfiles.ensureVanilla(base, self.mcVersion);
                String name = VersionProfiles.absorb(gameDir(), self.mcVersion, v.id);
                VersionProfiles.setActive(base, self.mcVersion, name);
                VersionProfiles.discard(gameDir(), base, v.id);
                // 配置里选中的就是这个加载器版本的话（比如用户建了个「防火」配置），
                // 要改成它并进去的那个版本；否则下次启动会掉到列表第一个版本上，
                // 点启动进的就不是自己的存档了
                retargetProfiles(v.id, self.mcVersion);
                migrated.add(v.id);
                Log.info("已把独立加载器版本 " + v.id + " 并进 " + self.mcVersion + "（" + name + "）");
            } catch (Exception e) {
                Log.warn("迁移加载器版本 " + v.id + " 失败：" + e.getMessage());
            }
        }
        return migrated;
    }

    /** 把配置文件里指向 loaderId 的「选中版本」改成 baseId。 */
    private void retargetProfiles(String loaderId, String baseId) {
        if (settings == null || settings.profiles == null) return;
        boolean changed = false;
        for (mcl.config.Profile p : settings.profiles) {
            if (p != null && loaderId.equals(p.selectedVersion)) {
                p.selectedVersion = baseId;
                changed = true;
            }
        }
        if (changed) {
            settings.save();
            Log.info("配置里选中的 " + loaderId + " 已改为 " + baseId);
        }
    }

    /** 除自己以外，还有没有别的已装版本靠 inheritsFrom 压在这个 id 上（只看全局目录）。 */
    private boolean referencedByOthers(String id) {
        Path versions = gameDir().resolve("versions");
        for (VersionManager.InstalledVersion v : versions().list()) {
            if (v.id.equals(id)) continue;
            if (Files.isRegularFile(versions.resolve(v.id).resolve(VersionManager.LOCAL_LOADER)
                    .resolve(id).resolve(id + ".json"))) {
                continue;                                   // 自己有私有副本，跟这个无关
            }
            String cur = v.id;
            for (int depth = 0; depth < 8 && cur != null; depth++) {
                Path json = versions.resolve(cur).resolve(cur + ".json");
                if (!Files.isRegularFile(json)) break;
                String parent;
                try {
                    parent = mcl.util.Json.str(com.google.gson.JsonParser
                            .parseString(Compat.readString(json)).getAsJsonObject(), "inheritsFrom", "");
                } catch (Exception e) {
                    break;
                }
                if (Compat.isBlank(parent)) break;
                if (id.equals(parent)) return true;
                cur = parent;
            }
        }
        return false;
    }

    /**
     * 安装 OptiFine（官方安装器无界面执行），返回生成的版本 id。
     *
     * <p>只在**原版**上用：原版没有 mod 加载能力，只能让官方安装器把 OptiFine
     * 打进一个独立版本里。已经装了 Forge / Fabric 的版本请走
     * {@link #installOptiFineInto(String, String, Installer.Listener)}。
     *
     * @param edition 例如 HD_U_I6；为空时取该游戏版本最新的一个
     */
    public String installOptiFine(String mcVersion, String edition, Installer.Listener listener)
            throws Exception {
        if (!versions().isInstalled(mcVersion)) {
            listener.onStage("先安装原版 " + mcVersion);
            install(mcVersion, true, listener);
        }
        mcl.install.OptiFineInstaller.Version target = pickOptiFine(mcVersion, edition, listener);
        listener.onStage("下载 " + target.fileName);
        Path jar = mcl.install.OptiFineInstaller.download(target,
                settings.launcherDir().resolve("cache").resolve("optifine"));
        VersionMeta vanilla = versions().load(mcVersion);
        JavaFinder.JavaInfo java = resolveJava(settings.profile(), vanilla);
        if (java == null) java = ensureJava(vanilla, null);
        if (java == null) throw new IOException("没有找到可用的 Java，无法运行 OptiFine 安装器");
        String optifineId = mcl.install.OptiFineInstaller.install(gameDir(), jar, java.exe, launcherEntry(),
                listener::onStage);
        prepareVersionDir(optifineId);
        return optifineId;
    }

    /**
     * 给「已经装好的版本」加 OptiFine，返回人类可读的结果。
     *
     * <p>OptiFine 是模组不是加载器 —— 它那个 jar 里带着
     * {@code META-INF/mods.toml}（{@code modId="optifine"}），Forge 会当普通 mod 加载。
     * 所以目标版本已经有 Forge / NeoForge / Fabric / Quilt 时，这里只是往它的
     * {@code mods/} 里放一个文件，已有模组一个都不受影响；Fabric / Quilt 再补一个 OptiFabric。
     *
     * <p>只有目标是原版时才退回官方安装器（另开一个带 OptiFine 的版本）。
     */
    public String installOptiFineInto(String versionId, String edition, Installer.Listener listener)
            throws Exception {
        VersionManager.Info info = versions().describe(versionId);
        String mc = Compat.isBlank(info.mcVersion) ? versionId : info.mcVersion;
        if (mcl.install.OptiFineInstaller.shapeFor(info.loaderKind)
                == mcl.install.OptiFineInstaller.Shape.INSTALLER) {
            listener.onStage("原版没有模组支持，改为安装带 OptiFine 的独立版本");
            return installOptiFine(mc, edition, listener);
        }
        mcl.install.OptiFineInstaller.Version target = pickOptiFine(mc, edition, listener);
        listener.onStage("下载 " + target.fileName);
        Path jar = mcl.install.OptiFineInstaller.download(target,
                settings.launcherDir().resolve("cache").resolve("optifine"));

        prepareVersionDir(versionId);
        Path mods = modsDir(versionId);
        Path dest = mcl.install.OptiFineInstaller.placeAsMod(jar, mods);

        if (mcl.install.OptiFineInstaller.needsOptiFabric(info.loaderKind)
                && mcl.install.OptiFineInstaller.existingOptiFabric(mods) == null) {
            listener.onStage("下载 OptiFabric（" + info.loaderLabel() + " 下 OptiFine 要靠它加载）");
            try {
                installModrinth(versionId, "optifabric", "mod", false, listener);
            } catch (Exception e) {
                throw new IOException("OptiFabric 没装上，OptiFine 在 " + info.loaderLabel()
                        + " 下无法单独工作：" + e.getMessage(), e);
            }
        }
        return "OptiFine " + target.edition + " 已放进 " + dest.getParent().getFileName()
                + "（" + info.loaderLabel() + " 版本，与已有模组共存）";
    }

    /** 在 OptiFine 官方列表里挑一个：指定 edition 优先，否则取最新的。 */
    private mcl.install.OptiFineInstaller.Version pickOptiFine(String mcVersion, String edition,
                                                               Installer.Listener listener) throws IOException {
        listener.onStage("查询 OptiFine " + mcVersion + " 的版本");
        List<mcl.install.OptiFineInstaller.Version> candidates = mcl.install.OptiFineInstaller.list(mcVersion);
        if (candidates.isEmpty()) {
            StringBuilder hint = new StringBuilder();
            try {
                java.util.List<String> all = new java.util.ArrayList<>();
                for (mcl.install.OptiFineInstaller.Version v : mcl.install.OptiFineInstaller.all()) {
                    if (!all.contains(v.mcVersion)) all.add(v.mcVersion);
                }
                if (!all.isEmpty()) {
                    hint.append("（OptiFine 有这些版本：")
                            .append(String.join("、", all.subList(0, Math.min(10, all.size()))))
                            .append(" 等）");
                }
            } catch (Exception ignored) {
            }
            throw new IOException("OptiFine 官方没有发布 " + mcVersion + " 的版本" + hint);
        }
        mcl.install.OptiFineInstaller.Version target = candidates.get(0);
        if (edition != null && !Compat.isBlank(edition)) {
            for (mcl.install.OptiFineInstaller.Version v : candidates) {
                if (v.edition.equalsIgnoreCase(edition)) {
                    target = v;
                    break;
                }
            }
        }
        return target;
    }

    /** 启动器自身的 classpath 入口（jar 或 classes 目录），供子进程加载 hook。 */
    public Path launcherEntry() {
        try {
            return java.nio.file.Paths.get(Game.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return settings.launcherDir().resolve("MCL.jar");
        }
    }

    /** 导出整合包（CurseForge / HMCL 通用水 zip 格式）。 */
    public Path exportModpack(String versionId, Path target, String name, String version, String author,
                              Installer.Listener listener) throws Exception {
        VersionManager.Info info = versions().describe(versionId);
        Path dir = runDir(versionId);
        if (!Files.isDirectory(dir)) throw new IOException("版本目录不存在：" + dir);
        listener.onStage("打包整合包内容");
        return mcl.modpack.Modpack.export(dir, name, version, author, info.mcVersion,
                info.loaderKind, info.loaderVersion, target);
    }

    /** 导入整合包，返回新实例的版本 id。 */
    public String importModpack(Path zip, String overrideMc, String overrideKind, String overrideVersion,
                                Installer.Listener listener) throws Exception {
        listener.onStage("识别整合包格式");
        mcl.modpack.Modpack.Info info = mcl.modpack.Modpack.inspect(zip);
        return mcl.modpack.Modpack.install(this, zip, info, overrideMc == null ? "" : overrideMc,
                overrideKind, overrideVersion, listener);
    }

    /** 安装 Modrinth 上的模组 / 资源包 / 光影。 */
    public mcl.mod.ModInstaller.Result installModrinth(String versionId, String idOrSlug, String projectType,
                                                       boolean withDependencies, Installer.Listener listener)
            throws Exception {
        return installModrinth(versionId, idOrSlug, projectType, mcl.mod.ModSource.MODRINTH,
                withDependencies, listener);
    }

    /** 从指定内容源（Modrinth / CurseForge）安装模组、资源包或光影。 */
    public mcl.mod.ModInstaller.Result installModrinth(String versionId, String idOrSlug, String projectType,
                                                       mcl.mod.ModSource modSource, boolean withDependencies,
                                                       Installer.Listener listener) throws Exception {
        VersionManager.Info info = versions().describe(versionId);
        Path dir = contentDir(versionId, projectType);
        mcl.mod.ModInstaller.Listener bridge = new mcl.mod.ModInstaller.Listener() {
            @Override
            public void onStage(String stage) {
                listener.onStage(stage);
            }

            @Override
            public void onProgress(int done, int total, long bytes, long bytesTotal, String current) {
                listener.onProgress(done, total, bytes, bytesTotal, current);
            }
        };
        return mcl.mod.ModInstaller.installProject(source(), settings.downloadThreads, dir, modSource, idOrSlug,
                projectType, info.mcVersion, info.modrinthLoader(), withDependencies, bridge);
    }

    /** 构建启动命令，不真正启动。 */
    public List<String> buildCommand(String versionId) throws Exception {
        return buildCommand(versionId, null);
    }

    public List<String> buildCommand(String versionId, Stage stage) throws Exception {
        return buildCommand(versionId, stage, null);
    }

    /**
     * @param quickServer 一键进服的目标（host 或 host:port），非空时覆盖配置里的默认服务器
     */
    public List<String> buildCommand(String versionId, Stage stage, String quickServer) throws Exception {
        Profile profile = settings.profile();
        VersionManager vm = versions();
        VersionMeta meta = vm.load(versionId);
        Path dir = gameDir();
        Path run = runDir(versionId);
        // 启动前把隔离目录备齐（首次会顺手接管共用目录的存档）。
        // 放在这里而不是 runDir() 里：runDir 会被列表渲染频繁调用，不能夹着文件复制。
        Isolation.Result adopted = prepareVersionDir(versionId);
        if (adopted.adopted()) Log.info(adopted.summary());

        JavaFinder.JavaInfo java = resolveJava(profile, meta);
        if (java == null || (meta.javaMajor > 0 && java.major < meta.javaMajor)) {
            java = ensureJava(meta, stage);
        }
        if (java == null) {
            String need = meta.javaMajor > 0 ? "（该版本需要 Java " + meta.javaMajor + "+）" : "";
            throw new IOException("没有找到可用的 Java" + need
                    + "。可以在「设置」里指定 java.exe，或在启动时勾选自动下载运行时。");
        }
        Log.info("使用 Java " + java.version + "（" + java.exe + "）");
        if (meta.javaMajor > 0 && java.major < meta.javaMajor) {
            Log.warn("该版本需要 Java " + meta.javaMajor + "，当前使用 " + java.version + "，可能无法启动");
        }
        Path natives = dir.resolve("versions").resolve(versionId).resolve("natives");
        Dirs.ensure(natives);
        Path clientJar = dir.resolve("versions").resolve(versionId).resolve(versionId + ".jar");
        // 独立 profile（整合包私有化之后）没有 inheritsFrom，靠 "jar" 字段指回原版的 jar
        if (!Files.isRegularFile(clientJar) && !Compat.isBlank(meta.jar)) {
            Path viaJar = dir.resolve("versions").resolve(meta.jar).resolve(meta.jar + ".jar");
            if (Files.isRegularFile(viaJar)) clientJar = viaJar;
        }
        // Forge 1.17+ / NeoForge 由 BootstrapLauncher 自己从 libraries 加载打补丁的客户端，
        // 把原版 jar 塞进 classpath 反而会造成模块冲突，这里不做父版本回退。
        boolean bootstrap = meta.mainClass != null && meta.mainClass.endsWith("BootstrapLauncher");
        if (!Files.isRegularFile(clientJar) && meta.inheritsFrom != null && !bootstrap) {
            Path parentJar = dir.resolve("versions").resolve(meta.inheritsFrom)
                    .resolve(meta.inheritsFrom + ".jar");
            if (Files.isRegularFile(parentJar)) clientJar = parentJar;
        }
        Path assets = dir.resolve("assets");
        Path logConfig = meta.logConfigId == null ? null : assets.resolve("log_configs").resolve(meta.logConfigId);

        Map<String, Boolean> features = Compat.mapOf("has_custom_resolution",
                !profile.fullscreen && profile.width > 0 && profile.height > 0);
        List<Path> libs = VersionManager.libraryFiles(dir, meta, features);
        try {
            Installer.ensureNatives(dir, meta, natives, features);
        } catch (IOException e) {
            Log.warn("解压 natives 失败（可能已被占用）：" + e.getMessage());
        }
        Account account = settings.account();
        // 中文名能进主界面、能进服务器列表，但一进世界就会被内部服务器踢掉：
        //   IllegalStateException: Invalid characters in username
        // 用户看到的是「建世界失败 / 进存档闪退」，完全想不到是名字的问题。
        // 与其让他去翻日志，不如在启动前一句话说清楚。
        if (account != null && !"msa".equals(account.type)) {
            String bad = Account.checkName(account.username);
            if (bad != null) {
                throw new IOException("当前账户「" + account.username + "」这个名字游戏不接受："
                        + "\n\n" + bad
                        + "\n\n请在「账户」页选中它，点「改名」换一个英文名（例如 fangshaoshan）。"
                        + "\n已有的存档不受影响，改名只是换个玩家名进游戏。");
            }
        }
        if (account != null && "msa".equals(account.type) && mcl.auth.MicrosoftAuth.ensureValid(account)) {
            settings.save();
            Log.info("已刷新微软账户令牌：" + account.username);
        }
        int memory = profile.autoMemory ? LaunchCommand.autoMemoryMb() : profile.maxMemory;
        stopSkinServer();   // 上一局可能残留（比如启动失败没走到收尾）
        String javaAgent = null;
        if (account != null && "authlib".equals(account.type) && account.apiRoot != null
                && !Compat.isBlank(account.apiRoot)) {
            try {
                Path jar = mcl.auth.AuthlibInjector.ensureJar(gameDir().resolve("libraries").resolve("mcl"));
                javaAgent = mcl.auth.AuthlibInjector.agentArg(jar, account.apiRoot);
                // 皮肤站的皮肤域名必须是 HTTPS 且由 authlib-injector 接管，无需额外参数
            } catch (IOException e) {
                Log.warn("准备 authlib-injector 失败：" + e.getMessage() + "，外置登录可能不生效");
            }
        } else if (account != null && "offline".equals(account.type)) {
            // 离线账户 + 用户导入过本地皮肤：起一个只认这个角色的迷你 Yggdrasil 服务，
            // 让 authlib-injector 把客户端的皮肤请求指过来。没有本地皮肤就完全不注入，
            // 保持「原版行为」——不为了一个用不到的功能给所有人加 agent。
            javaAgent = localSkinAgent(account);
        }
        return LaunchCommand.build(meta, profile, account, run, assets, natives, clientJar, libs,
                java.exe, logConfig, memory, javaAgent, quickServer, dir.resolve("libraries"));
    }

    /** 有本地皮肤就返回 agent 参数，否则返回 null（并保证不起服务）。 */
    private String localSkinAgent(Account account) {
        java.awt.image.BufferedImage skin =
                mcl.auth.ProfileSkins.localSkin(settings.launcherDir(), account.uuid);
        if (skin == null) return null;
        try {
            Path jar = mcl.auth.AuthlibInjector.ensureJar(gameDir().resolve("libraries").resolve("mcl"));
            skinServer = mcl.auth.LocalSkinServer.start(settings.launcherDir(), account, skin);
            return skinServer.agentArg(jar);
        } catch (Exception e) {
            stopSkinServer();
            Log.warn("准备本地皮肤失败：" + e.getMessage() + "，游戏内仍会是默认皮肤");
            return null;
        }
    }

    public Process launch(String versionId, GameLauncher.Listener listener) throws Exception {
        return launch(versionId, listener, null);
    }

    public Process launch(String versionId, GameLauncher.Listener listener, Stage stage) throws Exception {
        return launch(versionId, listener, stage, null);
    }

    /** 启动游戏并直接连到指定服务器（服务器页的一键进服）。 */
    public Process launch(String versionId, GameLauncher.Listener listener, Stage stage,
                          String quickServer) throws Exception {
        return launchWith(buildCommand(versionId, stage, quickServer), versionId, listener);
    }

    /**
     * 用已经构建好的命令启动。
     *
     * <p>CLI 的 {@code --launch} 要先打印命令再运行，如果让它调两次 {@link #buildCommand}，
     * 本地皮肤服务就会被起两次（第一次刚起就被第二次的清理关掉）。所以把「构建」和
     * 「运行」拆开，谁构建谁负责把命令传进来。
     */
    public Process launchWith(List<String> cmd, String versionId, GameLauncher.Listener listener)
            throws Exception {
        Path run = runDir(versionId);
        Path logFile = run.resolve("logs").resolve("mcl-latest.log");
        Process process;
        try {
            Dirs.ensure(run);
            Dirs.ensure(logFile.getParent());
            Files.deleteIfExists(logFile);
            process = GameLauncher.launch(cmd, run, logFile, listener);
        } catch (Exception e) {
            stopSkinServer();
            throw e;
        }
        // 进程起来了就把服务交给它：游戏一退出（不论正常还是崩溃）立刻关端口。
        // Java 8 没有 Process.onExit()，用守护线程轮询，1 秒一次开销可以忽略。
        mcl.auth.LocalSkinServer server = skinServer;
        skinServer = null;
        if (server != null) {
            Thread watcher = new Thread(() -> {
                try {
                    while (process.isAlive()) Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    server.stop();
                }
            }, "mcl-local-skin-watch");
            watcher.setDaemon(true);
            watcher.start();
        }
        return process;
    }

    public JavaFinder.JavaInfo resolveJava(Profile profile, VersionMeta meta) {
        if (profile.javaPath != null && !Compat.isBlank(profile.javaPath)) {
            JavaFinder.JavaInfo info = JavaFinder.find(java.nio.file.Paths.get(profile.javaPath));
            if (info != null) return info;
            Log.warn("配置中指定的 Java 不可用：" + profile.javaPath + "，改用自动检测");
        }
        return JavaFinder.findBest(meta == null ? 0 : meta.javaMajor);
    }

    /**
     * 本机没有合适版本的 Java 时，按 Mojang 官方运行时清单自动下载一份到 <启动器目录>/runtime。
     */
    public JavaFinder.JavaInfo ensureJava(VersionMeta meta, Stage stage) throws Exception {
        Path runtimeDir = settings.launcherDir().resolve("runtime");
        String comp = (meta.javaComponent == null || Compat.isBlank(meta.javaComponent))
                ? "java-" + meta.javaMajor : meta.javaComponent;
        Path cached = JavaInstaller.exeOf(runtimeDir.resolve(comp));
        if (cached != null) {
            JavaFinder.JavaInfo info = JavaFinder.find(cached);
            if (info != null) return info;
        }
        if (meta.javaMajor <= 0) {
            JavaFinder.refresh();
            return JavaFinder.findBest(0);
        }
        JavaInstaller.Listener listener = stage == null ? null : new JavaInstaller.Listener() {
            @Override
            public void onStage(String s) {
                stage.onStage(s);
            }

            @Override
            public void onProgress(int done, int total, long bytes, long bytesTotal, String file) {
                stage.onProgress(done, total, bytes, bytesTotal, file);
            }
        };
        Path exe = JavaInstaller.ensure(runtimeDir, meta.javaComponent, meta.javaMajor,
                source(), settings.downloadThreads, listener);
        JavaFinder.refresh();
        return JavaFinder.find(exe);
    }
}
