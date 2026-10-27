package mcl.game;

import mcl.util.Compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 服务器状态查询（Server List Ping）。
 *
 * <p>按 1.7+ 的协议走：握手包（next state = 1）→ 请求包 → 读回 JSON，
 * 里面带 MOTD、在线人数和 favicon（base64 PNG）。这个信息正好能直接写进
 * {@code servers.dat}，让游戏里的服务器列表也显示出图标。
 */
public final class ServerPing {

    private ServerPing() {
    }

    /** 一次查询的结果。 */
    public static final class Result {
        public boolean ok;
        /** 失败原因，成功时为空。 */
        public String error = "";
        public String motd = "";
        public int online = -1;
        public int max = -1;
        public String versionName = "";
        /** base64 编码的 PNG（不含 data:image/png;base64, 前缀）。 */
        public String favicon = "";
        public long latencyMs;

        public String playersText() {
            if (online < 0 || max < 0) return "";
            return online + " / " + max;
        }

        public String shortText() {
            if (!ok) return error.isEmpty() ? "无法连接" : error;
            StringBuilder sb = new StringBuilder();
            if (!Compat.isBlank(motd)) sb.append(motd);
            if (!playersText().isEmpty()) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append("在线 ").append(playersText());
            }
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(latencyMs).append(" ms");
            return sb.toString();
        }
    }

    public static Result ping(String address) {
        return ping(address, 3000);
    }

    public static Result ping(String address, int timeoutMs) {
        Result r = new Result();
        String host = Servers.normalizeHost(address);
        String portText = Servers.normalizePort(address);
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            port = 25565;
        }
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00);       // packet id
            writeVarInt(handshake, -1);         // 协议版本（-1 表示只要状态）
            writeString(handshake, host);
            handshake.write((port >> 8) & 0xFF);
            handshake.write(port & 0xFF);
            writeVarInt(handshake, 1);          // next state = status
            writeVarInt(out, handshake.size());
            out.write(handshake.toByteArray());

            out.write(new byte[]{0x01, 0x00});  // 长度为 1 的请求包，id = 0
            out.flush();

            int length = readVarInt(in);
            if (length <= 0 || length > 4 * 1024 * 1024) throw new IOException("服务器返回长度异常");
            byte[] payload = new byte[length];
            in.readFully(payload);
            r.latencyMs = System.currentTimeMillis() - start;

            ByteArrayInputStreamEx buf = new ByteArrayInputStreamEx(payload);
            int packetId = readVarInt(buf);
            if (packetId != 0x00) throw new IOException("不是状态响应包");
            String json = readString(buf);
            parse(json, r);
            r.ok = true;
        } catch (Exception e) {
            r.error = describe(e);
            r.latencyMs = System.currentTimeMillis() - start;
        }
        return r;
    }

    private static void parse(String json, Result r) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("description")) r.motd = stripColors(flatten(o.get("description")));
            if (o.has("players") && o.get("players").isJsonObject()) {
                JsonObject p = o.getAsJsonObject("players");
                if (p.has("online")) r.online = p.get("online").getAsInt();
                if (p.has("max")) r.max = p.get("max").getAsInt();
            }
            if (o.has("version") && o.get("version").isJsonObject()) {
                JsonObject v = o.getAsJsonObject("version");
                if (v.has("name")) r.versionName = v.get("name").getAsString();
            }
            if (o.has("favicon")) {
                String fav = o.get("favicon").getAsString();
                int comma = fav.indexOf(',');
                r.favicon = comma >= 0 ? fav.substring(comma + 1) : fav;
            }
        } catch (Exception ignored) {
        }
    }

    /** MOTD 可能是纯字符串，也可能是 {text, extra:[...]} 结构，统一拼成一行文本。 */
    private static String flatten(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        if (e.isJsonPrimitive()) return e.getAsString();
        StringBuilder sb = new StringBuilder();
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("text")) sb.append(o.get("text").getAsString());
            if (o.has("extra")) sb.append(flatten(o.get("extra")));
            if (o.has("translate")) sb.append(o.get("translate").getAsString());
        } else if (e.isJsonArray()) {
            JsonArray arr = e.getAsJsonArray();
            for (JsonElement x : arr) sb.append(flatten(x));
        }
        return stripColors(sb.toString());
    }

    /** 去掉 Minecraft 的 {@code §} 颜色/格式代码（含 {@code §x§R§R§G§G§B§B} 十六进制写法）。 */
    public static String stripColors(String text) {
        if (text == null || text.isEmpty()) return "";
        String t = text.replaceAll("(?i)\u00a7x(\u00a7[0-9a-f]){6}", "");
        t = t.replaceAll("(?i)\u00a7[0-9a-fk-or]", "");
        return t.replace('\u00a7', ' ').replaceAll("[ \\t]+", " ").trim();
    }

    private static String describe(Exception e) {
        String msg = e.getMessage();
        if (msg == null || Compat.isBlank(msg)) return "无法连接（" + e.getClass().getSimpleName() + "）";
        if (msg.contains("timed out")) return "连接超时";
        if (msg.contains("Connection refused")) return "连接被拒绝（服务器没开或端口不对）";
        if (msg.contains("UnknownHost")) return "域名解析失败";
        return msg;
    }

    // ---------------- VarInt / 字符串编解码 ----------------

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        int v = value;
        while (true) {
            int b = v & 0x7F;
            v >>>= 7;
            if (v != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return;
            }
        }
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("连接已关闭");
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) return value;
            position += 7;
            if (position >= 32) throw new IOException("VarInt 过长");
        }
    }

    private static String readString(InputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > 32767 * 4) throw new IOException("字符串长度异常");
        byte[] b = new byte[len];
        new DataInputStream(in).readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 从 byte[] 里顺序读，接口跟 InputStream 一致，省得套 ByteArrayInputStream。 */
    private static final class ByteArrayInputStreamEx extends InputStream {
        private final byte[] data;
        private int pos;

        ByteArrayInputStreamEx(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }
    }
}
