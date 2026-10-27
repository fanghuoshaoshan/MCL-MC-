package mcl.game;

import mcl.util.Dirs;
import mcl.util.Compat;

import mcl.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 多人游戏服务器列表（{@code .minecraft/servers.dat}）。
 *
 * <p>这个文件是 gzip 压缩的 NBT，结构固定：
 * <pre>
 * TAG_Compound("") {
 *   TAG_List("servers", TAG_Compound) [
 *     { name: String, ip: String, icon: String(base64 png), acceptTextures: Byte }
 *   ]
 * }
 * </pre>
 * 为了跟游戏完全兼容，这里自己实现了够用的 NBT 读写，不引第三方库。
 * 写入时会保留未知字段（例如 {@code hidden}），只覆盖自己关心的部分。
 */
public final class Servers {

    private Servers() {
    }

    /** 一条服务器记录。 */
    public static final class Server {
        public String name = "";
        public String ip = "";
        /** base64 编码的 64x64 PNG 图标，可能为空。 */
        public String icon = "";
        public boolean acceptTextures;
        /** 其它未识别的字段，写回时原样保留。 */
        final Map<String, Object> extra = new LinkedHashMap<>();

        public String host() {
            return split(ip)[0];
        }

        public String portText() {
            String p = split(ip)[1];
            return p.isEmpty() ? "25565" : p;
        }

        public String label() {
            return name == null || Compat.isBlank(name) ? ip : name;
        }

        @Override
        public String toString() {
            return label() + " (" + ip + ")";
        }
    }

    /** 把 {@code host:port} 拆成两段；没写端口时端口为空字符串。 */
    public static String[] split(String address) {
        String a = address == null ? "" : address.trim();
        if (a.isEmpty()) return new String[]{"", ""};
        int idx = a.lastIndexOf(':');
        // IPv6 形如 [::1]:25565
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            if (end > 0) {
                String host = a.substring(1, end);
                String rest = a.substring(end + 1);
                return new String[]{host, rest.startsWith(":") ? rest.substring(1) : ""};
            }
        }
        if (idx > 0 && a.indexOf(':') == idx) {
            return new String[]{a.substring(0, idx), a.substring(idx + 1)};
        }
        return new String[]{a, ""};
    }

    public static Path file(Path gameDir) {
        return gameDir.resolve("servers.dat");
    }

    /** 读取服务器列表；文件不存在时返回空列表而不是报错。 */
    public static List<Server> list(Path gameDir) {
        List<Server> out = new ArrayList<>();
        Path f = file(gameDir);
        if (!Files.isRegularFile(f)) return out;
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
            Map<String, Object> root = readRoot(in);
            Object servers = root.get("servers");
            if (servers instanceof List) {
                for (Object item : (List<?>) servers) {
                    if (!(item instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) item;
                    Server s = new Server();
                    s.name = str(m.get("name"));
                    s.ip = str(m.get("ip"));
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        String k = String.valueOf(e.getKey());
                        if ("name".equals(k) || "ip".equals(k)) {
                            continue;
                        } else if ("icon".equals(k)) {
                            s.icon = str(e.getValue());
                        } else if ("acceptTextures".equals(k)) {
                            s.acceptTextures = num(e.getValue()) != 0;
                        } else {
                            s.extra.put(k, e.getValue());
                        }
                    }
                    if (!Compat.isBlank(s.ip)) out.add(s);
                }
            }
        } catch (Exception e) {
            Log.warn("读取 servers.dat 失败：" + e.getMessage());
        }
        return out;
    }

    /** 覆盖写入服务器列表。 */
    public static void save(Path gameDir, List<Server> servers) throws IOException {
        Path f = file(gameDir);
        Dirs.ensure(f.getParent() == null ? gameDir : f.getParent());
        Path tmp = f.resolveSibling("servers.dat.tmp");
        try (OutputStream raw = Files.newOutputStream(tmp);
             GZIPOutputStream gz = new GZIPOutputStream(new BufferedOutputStream(raw));
             DataOutputStream out = new DataOutputStream(gz)) {
            out.writeByte(10);            // TAG_Compound
            writeUtf(out, "");            // 根节点名字为空
            out.writeByte(9);             // TAG_List
            writeUtf(out, "servers");
            out.writeByte(10);            // 元素是 TAG_Compound
            out.writeInt(servers.size());
            for (Server s : servers) writeServer(out, s);
            out.writeByte(0);             // TAG_End
        }
        try {
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static void writeServer(DataOutputStream out, Server s) throws IOException {
        out.writeByte(8);                 // name
        writeUtf(out, "name");
        writeUtf(out, s.name == null ? "" : s.name);
        out.writeByte(8);                 // ip
        writeUtf(out, "ip");
        writeUtf(out, s.ip == null ? "" : s.ip);
        if (s.icon != null && !Compat.isBlank(s.icon)) {
            out.writeByte(8);
            writeUtf(out, "icon");
            writeUtf(out, s.icon);
        }
        out.writeByte(1);                 // acceptTextures
        writeUtf(out, "acceptTextures");
        out.writeByte(s.acceptTextures ? 1 : 0);
        for (Map.Entry<String, Object> e : s.extra.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Byte) {
                out.writeByte(1);
                writeUtf(out, e.getKey());
                out.writeByte(((Byte) v).byteValue());
            } else if (v instanceof Number) {
                out.writeByte(3);
                writeUtf(out, e.getKey());
                out.writeInt(((Number) v).intValue());
            } else if (v instanceof String) {
                out.writeByte(8);
                writeUtf(out, e.getKey());
                writeUtf(out, (String) v);
            }
        }
        out.writeByte(0);                 // 该 compound 结束
    }

    /** 地址格式校验 + 补默认端口，返回可直接用于 {@code --server} 的 host。 */
    public static String normalizeHost(String address) {
        return split(address)[0];
    }

    public static String normalizePort(String address) {
        String p = split(address)[1];
        return p.isEmpty() ? "25565" : p;
    }

    // ---------------- NBT 读取 ----------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object v) {
        return (Map<String, Object>) v;
    }

    private static Map<String, Object> readRoot(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);
        int type = din.readUnsignedByte();
        if (type != 10) throw new IOException("servers.dat 根节点不是 compound");
        readUtf(din);
        Object v = readPayload(din, 10);
        if (v instanceof Map) return castMap(v);
        return new LinkedHashMap<String, Object>();
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: {
                return in.readByte();
            }
            case 2: {
                return in.readShort();
            }
            case 3: {
                return in.readInt();
            }
            case 4: {
                return in.readLong();
            }
            case 5: {
                return in.readFloat();
            }
            case 6: {
                return in.readDouble();
            }
            case 7: {
                int len = in.readInt();
                byte[] b = new byte[Math.max(0, len)];
                in.readFully(b);
                return b;
            }
            case 8: {
                return readUtf(in);
            }
            case 9: {
                int elem = in.readUnsignedByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < Math.max(0, len); i++) list.add(readPayload(in, elem));
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String name = readUtf(in);
                    map.put(name, readPayload(in, t));
                }
                return map;
            }
            case 11: {
                int len = in.readInt();
                int[] arr = new int[Math.max(0, len)];
                for (int i = 0; i < arr.length; i++) arr[i] = in.readInt();
                return arr;
            }
            case 12: {
                int len = in.readInt();
                long[] arr = new long[Math.max(0, len)];
                for (int i = 0; i < arr.length; i++) arr[i] = in.readLong();
                return arr;
            }
            default: throw new IOException("未知 NBT 类型 " + type);
        }
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeUtf(DataOutputStream out, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.write(b);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    // ---------------- 便捷增删改 ----------------

    public static void add(Path gameDir, Server s) throws IOException {
        List<Server> all = list(gameDir);
        all.removeIf(x -> x.ip.equalsIgnoreCase(s.ip));
        all.add(s);
        save(gameDir, all);
    }

    public static void remove(Path gameDir, Server s) throws IOException {
        List<Server> all = list(gameDir);
        all.removeIf(x -> x.ip.equalsIgnoreCase(s.ip) && x.name.equalsIgnoreCase(s.name));
        save(gameDir, all);
    }

    /** 把一段 PNG 字节转成 servers.dat 里要求的 base64 文本。 */
    public static String encodeIcon(byte[] png) {
        return png == null || png.length == 0
                ? "" : java.util.Base64.getEncoder().encodeToString(png);
    }

    /** 解析图标 base64，失败返回 null。 */
    public static byte[] decodeIcon(String base64) {
        if (base64 == null || Compat.isBlank(base64)) return null;
        try {
            return java.util.Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
