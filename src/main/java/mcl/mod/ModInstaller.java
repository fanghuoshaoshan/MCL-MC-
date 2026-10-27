package mcl.mod;

import mcl.util.Dirs;
import mcl.util.Compat;

import mcl.net.Downloader;
import mcl.net.Source;
import mcl.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从 Modrinth 安装模组 / 资源包 / 光影：解析版本、递归补齐必需依赖、并行下载到目标目录。
 * 与 HMCL 的 Modrinth 下载行为一致：只下主文件，required 依赖自动补装。
 */
public final class ModInstaller {

    public interface Listener extends Downloader.Listener {
        void onStage(String stage);
    }

    /** 安装结果。 */
    public static final class Result {
        public final List<String> installed = new ArrayList<>();
        public final List<String> failed = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("已安装 ").append(installed.size()).append(" 个文件");
            if (!skipped.isEmpty()) sb.append("，跳过 ").append(skipped.size()).append(" 个依赖");
            if (!failed.isEmpty()) sb.append("，失败 ").append(failed.size()).append(" 个");
            return sb.toString();
        }
    }

    private ModInstaller() {}

    /**
     * 安装指定项目的当前最佳版本（Modrinth 源）。
     *
     * @param destDir 目标目录（mods / resourcepacks / shaderpacks）
     */
    public static Result installProject(Source source, int threads, Path destDir, String idOrSlug,
                                        String projectType, String gameVersion, String loader,
                                        boolean withDependencies, Listener listener) throws Exception {
        return installProject(source, threads, destDir, ModSource.MODRINTH, idOrSlug, projectType,
                gameVersion, loader, withDependencies, listener);
    }

    /** 同上，但可以指定内容源（Modrinth / CurseForge）。 */
    public static Result installProject(Source source, int threads, Path destDir, ModSource modSource,
                                        String idOrSlug, String projectType, String gameVersion, String loader,
                                        boolean withDependencies, Listener listener) throws Exception {
        ModSource src = modSource == null ? ModSource.MODRINTH : modSource;
        Modrinth.VersionInfo v = ModApi.bestVersion(src, idOrSlug, gameVersion, loader);
        if (v == null) throw new IOException(src.label + " 上没有 " + idOrSlug + " 可用版本");
        return installVersion(source, threads, destDir, src, v, gameVersion, loader, withDependencies, listener);
    }

    /** 安装某个具体版本（含依赖）。 */
    public static Result installVersion(Source source, int threads, Path destDir, Modrinth.VersionInfo version,
                                        String gameVersion, String loader, boolean withDependencies,
                                        Listener listener) throws Exception {
        return installVersion(source, threads, destDir, ModSource.MODRINTH, version, gameVersion, loader,
                withDependencies, listener);
    }

    /** 安装某个具体版本（含依赖），文件地址按内容源解析。 */
    public static Result installVersion(Source source, int threads, Path destDir, ModSource modSource,
                                        Modrinth.VersionInfo version, String gameVersion, String loader,
                                        boolean withDependencies, Listener listener) throws Exception {
        Dirs.ensure(destDir);
        ModSource src = modSource == null ? ModSource.MODRINTH : modSource;
        Result result = new Result();
        Downloader dl = new Downloader(source, threads);
        Set<String> seenFiles = new HashSet<>();
        Set<String> seenProjects = new HashSet<>();

        if (listener != null) listener.onStage("解析 " + version.versionNumber + " 的文件");
        collect(dl, seenFiles, seenProjects, src, version, destDir, gameVersion, loader, withDependencies, 0, result);

        if (dl.size() == 0) {
            if (listener != null) listener.onStage("文件已存在，无需下载");
            return result;
        }
        if (listener != null) listener.onStage("下载 " + dl.size() + " 个文件到 " + destDir.getFileName());
        int failedCount = dl.run(listener);
        result.installed.addAll(seenFiles);
        if (failedCount > 0) result.failed.add(failedCount + " 个文件下载失败");
        Log.info(src.label + " 安装完成：" + result.summary());
        return result;
    }

    private static void collect(Downloader dl, Set<String> seenFiles, Set<String> seenProjects, ModSource src,
                                Modrinth.VersionInfo v, Path destDir, String gameVersion, String loader,
                                boolean withDeps, int depth, Result result) {
        if (v == null || depth > 8) return;
        Modrinth.FileInfo f = v.primaryFile();
        if (f != null && !f.filename.isEmpty() && seenFiles.add(f.filename)) {
            dl.addDirect(f.filename, f.url, destDir.resolve(f.filename), f.sha1, f.size);
        }
        if (!withDeps || depth >= 8) return;
        for (Modrinth.Dependency d : v.dependencies) {
            if (!"required".equalsIgnoreCase(d.dependencyType)) continue;
            if (d.projectId == null || Compat.isBlank(d.projectId)) continue;
            if (!seenProjects.add(d.projectId)) continue;
            try {
                Modrinth.VersionInfo dep;
                if (d.versionId != null && !Compat.isBlank(d.versionId)) {
                    dep = ModApi.version(src, d.versionId);
                } else {
                    dep = ModApi.bestVersion(src, d.projectId, gameVersion, loader);
                }
                if (dep == null) {
                    result.skipped.add(d.projectId);
                    continue;
                }
                collect(dl, seenFiles, seenProjects, src, dep, destDir, gameVersion, loader, true, depth + 1, result);
            } catch (IOException e) {
                Log.warn("依赖 " + d.projectId + " 解析失败：" + e.getMessage());
                result.skipped.add(d.projectId);
            }
        }
    }

    /** 单纯下载某个 URL（整合包 .mrpack 等）到文件。 */
    public static void downloadFile(String url, Path dest, String sha1, long size,
                                    Downloader.Listener listener) throws IOException {
        Downloader dl = new Downloader(Source.MOJANG, 1);
        dl.addDirect(dest.getFileName().toString(), url, dest, sha1, size);
        int failed;
        try {
            failed = dl.run(listener);
        } catch (IOException e) {
            throw new IOException("下载失败：" + dest.getFileName() + reasonOf(e) + hintFor(url, e), e);
        }
        if (failed > 0) {
            // 原因一定要带出来：原来这里只有一句「下载失败」，用户和我们都无从下手
            IOException cause = dl.lastError();
            throw new IOException("下载失败：" + dest.getFileName() + reasonOf(cause) + hintFor(url, cause), cause);
        }
        if (!Files.isRegularFile(dest)) throw new IOException("下载后文件不存在：" + dest);
    }

    private static String reasonOf(Throwable e) {
        if (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) return "";
        return "\n底层原因：" + e.getMessage();
    }

    /**
     * 到底该怪网络还是怪本地盘，得分开说。
     *
     * <p>事故复盘：{@code cache/modpacks} 被一个 HTML 文件占住，建目录直接抛
     * {@code FileAlreadyExistsException}，可提示写的是「两套 CDN 都没给这个文件」，
     * 用户和我们都以为是被 CurseForge 拦了，白跑了两轮。现在按异常类型分流。
     */
    private static String hintFor(String url, Throwable e) {
        if (looksLocal(e)) {
            return "\n这是本地磁盘的问题（目录被文件/别的程序占住，或没有写入权限），跟网络无关。"
                    + "\n程序已经试过自动让路；若还不行，关掉可能锁住该目录的软件后再试一次。";
        }
        return forgeCdnHint(url);
    }

    private static boolean looksLocal(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.nio.file.FileAlreadyExistsException
                    || t instanceof java.nio.file.AccessDeniedException
                    || t instanceof java.nio.file.FileSystemException) {
                return true;
            }
            String m = t.getMessage();
            if (m != null && (m.contains("目录位置被文件占用") || m.contains("拒绝访问")
                    || m.contains("磁盘空间") || m.contains("没有足够的空间"))) {
                return true;
            }
            if (t.getCause() == null) break;
        }
        return false;
    }

    /**
     * CurseForge 的整合包在 edge CDN 上会 404，只有 mediafilez 有 —— 下载器已经会自动换域名重试，
     * 所以走到这里说明两套 CDN 都拿不到，才提示去项目页手动下载。
     */
    private static String forgeCdnHint(String url) {
        if (url == null || !url.contains("forgecdn.net")) return "";
        return "\nCurseForge 的 CDN（edge / mediafilez 两套都试过了）都没给这个文件。"
                + "\n请到项目页手动下载后，用「添加本地文件」导入。";
    }
}
