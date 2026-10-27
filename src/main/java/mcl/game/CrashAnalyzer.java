package mcl.game;

import mcl.util.Compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 崩溃分析：读 {@code logs/latest.log}、{@code crash-reports/*.txt} 和 JVM 的
 * {@code hs_err_pid*.log}，按已知特征给出"为什么崩"和"怎么办"。
 *
 * <p>这里不做上传，纯本地规则匹配——和 HMCL 的崩溃分析定位一致：
 * 先给结论，再把原始日志留给用户自己看。
 */
public final class CrashAnalyzer {

    private CrashAnalyzer() {
    }

    /** 一条分析结论。 */
    public static final class Finding {
        /** 严重程度：error / warn / info。 */
        public String level = "info";
        public String title = "";
        public String detail = "";
        /** 建议操作，可为空。 */
        public String advice = "";

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[" + level + "] " + title);
            if (!Compat.isBlank(detail)) sb.append("\n    ").append(detail);
            if (!Compat.isBlank(advice)) sb.append("\n    → ").append(advice);
            return sb.toString();
        }
    }

    /** 一次分析的结果。 */
    public static final class Report {
        public final List<Finding> findings = new ArrayList<>();
        /** 用到的日志文件（按时间倒序，第一个是最新的）。 */
        public final List<Path> sources = new ArrayList<>();
        public String exitHint = "";

        public boolean hasProblems() {
            for (Finding f : findings) if (!"info".equals(f.level)) return true;
            return false;
        }

        public String summary() {
            if (findings.isEmpty()) return "没有在日志里找到已知的崩溃特征，可能是驱动/硬件问题。";
            Finding first = findings.get(0);
            return first.title + (Compat.isBlank(first.advice) ? "" : "：" + first.advice);
        }

        public String toText() {
            StringBuilder sb = new StringBuilder();
            if (!Compat.isBlank(exitHint)) sb.append(exitHint).append("\n\n");
            if (findings.isEmpty()) {
                sb.append("没有匹配到已知的崩溃特征。\n");
                sb.append("可以检查：显卡驱动是否最新、是否装了 OptiFine 与光影冲突的模组、Java 版本是否匹配。\n");
            }
            for (int i = 0; i < findings.size(); i++) {
                Finding f = findings.get(i);
                sb.append(i + 1).append(". ").append(f.title).append('\n');
                if (!Compat.isBlank(f.detail)) sb.append("   ").append(f.detail).append('\n');
                if (!Compat.isBlank(f.advice)) sb.append("   建议：").append(f.advice).append('\n');
                sb.append('\n');
            }
            if (!sources.isEmpty()) {
                sb.append("分析的日志：\n");
                for (Path p : sources) sb.append("  ").append(p).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 分析某个运行目录最近一次启动。
     *
     * @param runDir     版本运行目录（隔离版本就是 {@code versions/<id>}）
     * @param exitCode   进程退出码，未知时传 -1
     * @param meta       版本元数据，可为 null（用于判断需要的 Java 版本）
     */
    public static Report analyze(Path runDir, int exitCode, mcl.meta.VersionMeta meta) {
        Report report = new Report();
        if (exitCode >= 0) {
            report.exitHint = exitCode == 0 ? "游戏正常退出（退出码 0）。"
                    : "游戏以退出码 " + exitCode + " 结束。";
        }

        List<Path> logs = new ArrayList<>();
        // 崩溃报告优先（内容最集中）
        Path crashDir = runDir.resolve("crash-reports");
        if (Files.isDirectory(crashDir)) {
            try (java.util.stream.Stream<Path> s = Files.list(crashDir)) {
                s.filter(Files::isRegularFile)
                        .sorted(Comparator.comparingLong(CrashAnalyzer::mtime).reversed())
                        .limit(3)
                        .forEach(logs::add);
            } catch (IOException ignored) {
            }
        }
        Path latest = runDir.resolve("logs").resolve("latest.log");
        if (Files.isRegularFile(latest)) logs.add(latest);
        Path launcherLog = runDir.resolve("logs").resolve("mcl-latest.log");
        if (Files.isRegularFile(launcherLog)) logs.add(launcherLog);
        // JVM 级崩溃（hs_err_pid）
        try (java.util.stream.Stream<Path> s = Files.list(runDir)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("hs_err_pid") && n.endsWith(".log");
            }).limit(1).forEach(logs::add);
        } catch (IOException ignored) {
        }

        StringBuilder all = new StringBuilder();
        for (Path p : logs) {
            String text = readTail(p, 512 * 1024);
            if (text.isEmpty()) continue;
            report.sources.add(p);
            all.append("\n=== ").append(p.getFileName()).append(" ===\n").append(text);
        }
        match(all.toString(), report, meta);
        // 去重（同一结论只留一条）
        List<Finding> unique = new ArrayList<>();
        for (Finding f : report.findings) {
            boolean dup = false;
            for (Finding u : unique) {
                if (u.title.equals(f.title)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) unique.add(f);
        }
        report.findings.clear();
        report.findings.addAll(unique);
        // error 排前面
        report.findings.sort(Comparator.comparingInt(f -> levelRank(f.level)));
        return report;
    }

    /** error 排最前，warn 次之，其余按原顺序。 */
    private static int levelRank(String level) {
        if ("error".equals(level)) return 0;
        if ("warn".equals(level)) return 1;
        return 2;
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String readTail(Path p, int maxBytes) {
        try {
            byte[] all = Files.readAllBytes(p);
            int start = Math.max(0, all.length - maxBytes);
            return new String(all, start, all.length - start, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void match(String text, Report report, mcl.meta.VersionMeta meta) {
        String s = text.toLowerCase(Locale.ROOT);

        if (s.contains("outofmemoryerror") || s.contains("java heap space")) {
            add(report, "error", "内存不足（OutOfMemoryError）",
                    "游戏申请的内存超过了 JVM 上限，常见于高分辨率材质包、大量模组或分配过小。",
                    "到「设置」把最大内存调到 4 GB 以上（大型整合包建议 6–8 GB），并降低渲染距离/材质分辨率。");
        }
        if (s.contains("unsupportedclassversionerror")) {
            String need = meta != null && meta.javaMajor > 0 ? String.valueOf(meta.javaMajor) : "更高版本";
            add(report, "error", "Java 版本太低",
                    "日志里出现 UnsupportedClassVersionError，说明当前 Java 跑不动这个版本。",
                    "这个版本需要 Java " + need + "。在「设置」里指定对应的 java.exe，或让 MCL 自动下载运行时。");
        }
        if (s.contains("mod resolution failed") || s.contains("incompatible mod set")
                || s.contains("mod resolution encountered an incompatible mod set")) {
            add(report, "error", "模组依赖不满足",
                    "Fabric/Quilt 在启动时做依赖解析失败，通常是缺少前置模组或版本不匹配。",
                    "看日志里 \"requires\" 后面的模组名，用「模组 → 在线下载」补装对应前置；或用「模组 → 检查」看缺失依赖。");
        }
        if (s.contains("requires fabric api") || s.contains("fabric-api is not installed")
                || s.contains("fabric api is not installed") || s.contains("requires any version of fabric-api")
                || s.contains("requires any version of fabric api")) {
            add(report, "error", "缺少 Fabric API",
                    "很多 Fabric 模组都要 Fabric API 才能运行。",
                    "在「模组 → 在线下载」搜索 fabric-api 安装。");
        }
        if (s.contains("missing or unsupported mandatory dependencies")
                || s.contains("mod file") && s.contains("is missing")) {
            add(report, "error", "缺少必需的前置模组",
                    "Forge/NeoForge 报告有模组缺少强制依赖。",
                    "按日志提示补装前置；不想折腾就删掉报错的那个模组。");
        }
        if (s.contains("duplicate mods") || s.contains("duplicatemodsexception")) {
            add(report, "error", "装了两个同名的模组",
                    "同一个 modId 出现了多份 jar（常见于手动复制、或更新时没删旧文件）。",
                    "在「模组 → 已安装」里删掉旧版本，只留一个。");
        }
        if (s.contains("java.lang.classnotfoundexception") && s.contains("optifine")) {
            add(report, "warn", "OptiFine 类找不到",
                    "OptiFine 与当前加载器/游戏版本不匹配，或被其它模组覆盖。",
                    "确认 OptiFine 是给这个游戏版本用的；同时装 Forge 时要用 OptiFine 的 Forge 版安装方式。");
        }
        if (s.contains("glfw error") || s.contains("failed to create window")
                || s.contains("pixel format not accelerated")) {
            add(report, "error", "显卡/OpenGL 初始化失败",
                    "通常是显卡驱动过旧、远程桌面/虚拟机环境、或缺少 OpenGL 支持。",
                    "更新显卡驱动；远程桌面下改用本机运行；必要时加 JVM 参数 -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true（画质会很差）。");
        }
        if (s.contains("unsupported operating system") || s.contains("os error 193")
                || s.contains("the system cannot find the path specified")) {
            add(report, "error", "启动时找不到文件或路径含特殊字符",
                    "游戏目录或 Java 路径里有非法字符，或文件被杀毒软件隔离。",
                    "把游戏目录移到纯英文路径（例如 D:\\MCL），并把目录加入杀毒软件白名单。");
        }
        if (s.contains("could not find or load main class") || s.contains("noclassdeffounderror")) {
            add(report, "error", "主类或依赖库缺失",
                    "版本 json / 库文件不完整，多半是下载中断或手动改过 versions 目录。",
                    "重新安装该版本（「下载」页面重装，或删除 versions/<id> 后重装）。");
        }
        if (s.contains("the game crashed whilst") || s.contains("exit code: -1")
                || s.contains("exception in thread \"main\"")) {
            add(report, "warn", "游戏主线程抛异常",
                    "崩溃报告里有 Java 异常，具体原因看上面的堆栈第一段。",
                    "把崩溃报告里 \"Description:\" 和第一段堆栈发到模组作者的 issue 区，通常能直接定位。");
        }
        if (s.contains("access violation") || s.contains("sigsegv") || s.contains("hs_err_pid")) {
            add(report, "error", "JVM 级崩溃（SIGSEGV / Access Violation）",
                    "不是普通 Java 异常，而是本地库或驱动直接把进程搞崩了。",
                    "先换一个 Java（比如 MCL 自带的运行时）；再排查显卡驱动与本地库类模组（光影、小地图、优化模组）。");
        }
        if (s.contains("invalid maximum heap size") || s.contains("could not reserve enough space")) {
            add(report, "error", "内存参数无效或系统内存不足",
                    "设置的最大内存超过系统可用内存，或用了 32 位 Java。",
                    "把最大内存调小；确认用的是 64 位 Java。");
        }
        if (s.contains("invalid session") || s.contains("failed to verify username")
                || s.contains("authenticationexception") || s.contains("invalid credentials")) {
            add(report, "warn", "登录验证失败",
                    "正版账户令牌过期，或离线账户进正版服务器被拒。",
                    "到「账户」页重新登录；进正版服务器必须用微软正版账户。");
        }
        if (s.contains("mixin apply failed") || s.contains("mixinapplyerror")) {
            add(report, "error", "Mixin 注入失败",
                    "两个模组改了同一段代码，或模组版本与游戏版本不匹配。",
                    "先删掉最近装的模组试；确认所有模组都支持当前游戏版本与加载器版本。");
        }
        if (s.contains("no such file or directory") && s.contains("assets")) {
            add(report, "warn", "游戏资源不完整",
                    "assets 目录缺文件，声音/贴图可能丢失甚至启动失败。",
                    "在「下载」页面对该版本重新安装一次（会补齐缺失资源）。");
        }
    }

    private static void add(Report r, String level, String title, String detail, String advice) {
        Finding f = new Finding();
        f.level = level;
        f.title = title;
        f.detail = detail;
        f.advice = advice;
        r.findings.add(f);
    }
}
