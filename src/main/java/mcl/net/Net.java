package mcl.net;

import mcl.util.Compat;

import mcl.config.Settings;
import mcl.util.Log;

import java.io.IOException;

/** 网络设置的应用入口：把 mcl.json 里的代理与限速同步到 Http / Throttle。 */
public final class Net {

    private Net() {}

    public static void apply(Settings s) {
        if (s == null) return;
        Http.configure(s.proxyMode, s.proxyHost, s.proxyPort, s.proxyUser, s.proxyPass);
        Throttle.setLimitKbps(s.speedLimitKbps);
    }

    /** 代理可用性探测：拿一个很小的资源，返回可读结论。 */
    public static String probe() {
        String url = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
        long t0 = System.currentTimeMillis();
        try {
            String body = Http.getStringOnce(url);
            long ms = System.currentTimeMillis() - t0;
            return "连通正常：" + Http.proxyLabel() + " · 版本清单 " + Ui_size(body.length()) + " · " + ms + " ms";
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg == null || Compat.isBlank(msg)) msg = e.getClass().getSimpleName();
            return "连不通：" + Http.proxyLabel() + " · " + msg;
        }
    }

    private static String Ui_size(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    /** 供 CLI 使用：解析 "host:port" 或 "http://host:port"。 */
    public static boolean configureFromString(Settings s, String spec) {
        if (spec == null || Compat.isBlank(spec)) {
            s.proxyMode = "none";
            apply(s);
            return false;
        }
        String text = spec.trim();
        String mode = "http";
        if (text.startsWith("socks5://")) {
            mode = "socks";
            text = text.substring("socks5://".length());
        } else if (text.startsWith("socks://")) {
            mode = "socks";
            text = text.substring("socks://".length());
        } else if (text.startsWith("http://")) {
            text = text.substring("http://".length());
        }
        String user = "";
        String pass = "";
        int at = text.lastIndexOf('@');
        if (at > 0) {
            String cred = text.substring(0, at);
            text = text.substring(at + 1);
            int colon = cred.indexOf(':');
            if (colon >= 0) {
                user = cred.substring(0, colon);
                pass = cred.substring(colon + 1);
            } else {
                user = cred;
            }
        }
        int colon = text.lastIndexOf(':');
        int port = 7890;
        String host = text;
        if (colon > 0) {
            host = text.substring(0, colon);
            try {
                port = Integer.parseInt(text.substring(colon + 1));
            } catch (NumberFormatException e) {
                Log.warn("代理端口不是数字，回退 7890：" + spec);
            }
        }
        if (Compat.isBlank(host)) return false;
        s.proxyMode = mode;
        s.proxyHost = host;
        s.proxyPort = port;
        s.proxyUser = user;
        s.proxyPass = pass;
        apply(s);
        return true;
    }
}
