package mcl.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java 8 兼容垫片。
 *
 * <p>项目编译目标是 Java 8（这样一台只装了 Java 8 的机器也能开），但写代码时手滑就会用到
 * Java 9+ 的 API。这些 API 在 Java 8 的 rt.jar 里根本不存在，运行时才炸，所以在编译期
 * 就用 {@code --release 8} 拦住，统一改成这里的等价实现。
 *
 * <p>语义上和 JDK 的实现保持一致（{@code isBlank} 按 Unicode 空白判断，
 * 而不是 {@code trim()} 那样只认 &lt;= ' '）。
 */
public final class Compat {

    private Compat() {}

    /** 等价于 Java 11 的 {@code String.isBlank()}。 */
    public static boolean isBlank(CharSequence s) {
        if (s == null) return true;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            if (!Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }

    public static boolean notBlank(CharSequence s) {
        return !isBlank(s);
    }

    /** 等价于 Java 11 的 {@code String.strip()}。 */
    public static String strip(String s) {
        return s == null ? null : stripTrailing(stripLeading(s));
    }

    public static String stripLeading(String s) {
        if (s == null) return null;
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    public static String stripTrailing(String s) {
        if (s == null) return null;
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return s.substring(0, i);
    }

    /** 等价于 Java 9 的 {@code InputStream.readAllBytes()}。 */
    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** 等价于 Java 11 的 {@code Files.readString(path, UTF_8)}。 */
    public static String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 等价于 Java 11 的 {@code Files.writeString(path, text, UTF_8)}。 */
    public static void writeString(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 少用，但偶尔需要把数组包成不可变列表。 */
    @SafeVarargs
    public static <T> List<T> listOf(T... items) {
        List<T> out = new ArrayList<T>(items.length);
        Collections.addAll(out, items);
        return Collections.unmodifiableList(out);
    }

    @SafeVarargs
    public static <T> Set<T> setOf(T... items) {
        return Collections.unmodifiableSet(new LinkedHashSet<T>(Arrays.asList(items)));
    }

    /** {@code mapOf("k1", v1, "k2", v2)} —— 参数必须成对。 */
    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> mapOf(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("mapOf 参数必须成对");
        Map<K, V> out = new LinkedHashMap<K, V>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((K) pairs[i], (V) pairs[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }
}
