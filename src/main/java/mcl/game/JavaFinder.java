package mcl.game;

import mcl.util.Compat;

import mcl.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 本机 Java 探测与版本匹配。 */
public class JavaFinder {

    public static class JavaInfo {
        public final Path exe;
        public final String version;
        public final int major;
        public final String vendor;

        public JavaInfo(Path exe, String version, int major, String vendor) {
            this.exe = exe;
            this.version = version;
            this.major = major;
            this.vendor = vendor;
        }

        public String label() {
            return "Java " + version + (vendor.isEmpty() ? "" : " · " + vendor);
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private static List<JavaInfo> cache;

    public static synchronized List<JavaInfo> findAll() {
        if (cache != null) return cache;
        Set<Path> candidates = new LinkedHashSet<>();
        // 当前正在运行的这个 JVM 往往就是最可靠的一个（尤其是自带的裁剪运行时）
        String currentHome = System.getProperty("java.home");
        if (currentHome != null && !Compat.isBlank(currentHome)) addHome(candidates, java.nio.file.Paths.get(currentHome));
        addFromPathEnv(candidates);
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) addHome(candidates, java.nio.file.Paths.get(javaHome));
        for (Path root : scanRoots()) addFromRoot(candidates, root);

        List<JavaInfo> result = new ArrayList<>();
        Set<String> versions = new LinkedHashSet<>();
        for (Path exe : candidates) {
            JavaInfo info = probe(exe);
            if (info == null) continue;
            if (versions.add(info.version + "|" + exe)) result.add(info);
        }
        result.sort(Comparator.comparingInt((JavaInfo j) -> j.major).reversed()
                .thenComparing(j -> j.version));
        cache = result;
        Log.info("检测到 " + result.size() + " 个 Java 运行时");
        return result;
    }

    public static void refresh() {
        cache = null;
    }

    public static JavaInfo findBest(int requiredMajor) {
        List<JavaInfo> all = findAll();
        JavaInfo best = null;
        for (JavaInfo j : all) {
            if (requiredMajor <= 0) {
                if (best == null || j.major > best.major) best = j;
            } else if (j.major == requiredMajor) {
                return j;
            } else if (j.major > requiredMajor) {
                if (best == null || j.major < best.major) best = j;
            }
        }
        if (best != null) return best;
        // 指定了 requiredMajor 就不能退回更低版本：返回一个版本不够的 Java，
        // 调用方会以为"找到了"而直接拿它去启动，结果就是一个 Mismatch 崩溃。
        if (requiredMajor > 0) return null;
        return all.isEmpty() ? null : all.get(0);
    }

    public static JavaInfo find(Path exe) {
        if (exe == null || !Files.isRegularFile(exe)) return null;
        for (JavaInfo j : findAll()) if (j.exe.equals(exe)) return j;
        return probe(exe);
    }

    private static void addFromPathEnv(Set<Path> out) {
        String path = System.getenv("PATH");
        if (path == null) return;
        String exeName = isWindows() ? "java.exe" : "java";
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Compat.isBlank(dir)) continue;
            Path p = java.nio.file.Paths.get(dir, exeName);
            if (Files.isRegularFile(p)) out.add(p);
        }
    }

    private static void addHome(Set<Path> out, Path home) {
        for (String n : new String[]{"bin/java.exe", "bin/java", "jre/bin/java.exe", "jre/bin/java"}) {
            Path p = home.resolve(n);
            if (Files.isRegularFile(p)) out.add(p);
        }
    }

    private static void addFromRoot(Set<Path> out, Path root) {
        if (!Files.isDirectory(root)) return;
        addHome(out, root);
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).sorted().forEach(dir -> {
                addHome(out, dir);
                try (java.util.stream.Stream<Path> inner = Files.list(dir)) {
                    inner.filter(Files::isDirectory)
                            .filter(d -> d.getFileName().toString().toLowerCase().contains("jdk")
                                    || d.getFileName().toString().toLowerCase().contains("jre"))
                            .forEach(d -> addHome(out, d));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static List<Path> scanRoots() {
        List<Path> roots = new ArrayList<>();
        if (isWindows()) {
            String[] pf = {"C:\\Program Files", "C:\\Program Files (x86)"};
            String[] vendors = {"Java", "Eclipse Adoptium", "Eclipse Foundation", "Microsoft", "Zulu",
                    "BellSoft", "Amazon Corretto", "Semeru", "JetBrains\\Runtime", "AdoptOpenJDK"};
            for (String base : pf) for (String v : vendors) roots.add(java.nio.file.Paths.get(base, v));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                roots.add(java.nio.file.Paths.get(local, "Programs", "Eclipse Adoptium"));
                roots.add(java.nio.file.Paths.get(local, "Programs", "Microsoft"));
            }
            String programData = System.getenv("ProgramData");
            if (programData != null) roots.add(java.nio.file.Paths.get(programData, "Oracle", "Java"));
        } else if (Rules2.isMac()) {
            roots.add(java.nio.file.Paths.get("/Library/Java/JavaVirtualMachines"));
        } else {
            roots.add(java.nio.file.Paths.get("/usr/lib/jvm"));
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            roots.add(java.nio.file.Paths.get(home, ".jdks"));
            roots.add(java.nio.file.Paths.get(home, "scoop", "apps", "openjdk"));
            roots.add(java.nio.file.Paths.get(home, ".gradle", "jdks"));
        }
        return roots;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static final Pattern VERSION_LINE = Pattern.compile("version \"([^\"]+)\"");

    private static JavaInfo probe(Path exe) {
        try {
            Path home = exe.getParent() != null ? exe.getParent().getParent() : null;
            String version = null;
            String vendor = "";
            if (home != null) {
                Path release = home.resolve("release");
                if (Files.isRegularFile(release)) {
                    for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                        if (line.startsWith("JAVA_VERSION=")) {
                            version = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                        } else if (line.startsWith("IMPLEMENTOR=")) {
                            vendor = line.substring("IMPLEMENTOR=".length()).replace("\"", "").trim();
                        }
                    }
                }
            }
            if (version == null || version.isEmpty()) {
                ProcessBuilder pb = new ProcessBuilder(exe.toString(), "-version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Matcher m = VERSION_LINE.matcher(line);
                        if (m.find()) {
                            version = m.group(1);
                            break;
                        }
                    }
                }
                p.waitFor();
            }
            if (version == null) return null;
            return new JavaInfo(exe, version, majorOf(version), vendor);
        } catch (Exception e) {
            return null;
        }
    }

    public static int majorOf(String version) {
        if (version == null || version.isEmpty()) return 0;
        try {
            if (version.startsWith("1.")) {
                String rest = version.substring(2);
                int dot = rest.indexOf('.');
                String n = dot > 0 ? rest.substring(0, dot) : rest;
                StringBuilder digits = new StringBuilder();
                for (char c : n.toCharArray()) {
                    if (Character.isDigit(c)) digits.append(c);
                    else break;
                }
                return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
            }
            StringBuilder digits = new StringBuilder();
            for (char c : version.toCharArray()) {
                if (Character.isDigit(c)) digits.append(c);
                else break;
            }
            return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 避免引入 Rules 造成循环依赖的小工具。 */
    private static final class Rules2 {
        static boolean isMac() {
            return System.getProperty("os.name", "").toLowerCase().contains("mac");
        }
    }
}
