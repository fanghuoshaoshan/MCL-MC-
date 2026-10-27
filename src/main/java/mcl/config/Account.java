package mcl.config;

import mcl.util.Compat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 账户：支持离线、微软正版（msa）与外置登录（authlib）。 */
public class Account {
    public String username = "";
    public String uuid = "";
    /** offline / msa / authlib */
    public String type = "offline";
    public String accessToken = "";
    /** 微软账户的 OAuth 刷新令牌 */
    public String refreshToken = "";
    /** 访问令牌到期时间（毫秒时间戳），0 表示未知 */
    public long expiresAt;
    /** 外置登录的 Yggdrasil API 根地址（authlib 账户用） */
    public String apiRoot = "";
    /** 外置登录服务器对外展示的名字 */
    public String serverName = "";
    /**
     * 离线账户用的默认皮肤模型。
     *
     * <p>{@code ""} 表示跟随 UUID（和游戏内 DefaultSkinHelper 算出来的一致），
     * {@code "classic"} 指定 Steve，{@code "slim"} 指定 Alex。
     * 正版 / 外置账户的模型由服务器给，这个字段对它们不起作用。
     */
    public String skinModel = "";

    public Account() {}

    public Account(String username, String type) {
        this.username = username;
        this.type = type;
        if ("offline".equals(type)) {
            this.uuid = offlineUuid(username);
            this.accessToken = UUID.randomUUID().toString().replace("-", "");
        }
    }

    /** 与官方启动器一致的离线 UUID 算法。 */
    public static String offlineUuid(String name) {
        UUID u = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return u.toString().replace("-", "");
    }

    /** 角色名的合法长度（Minecraft 的网络协议规定，多一个字节都进不去游戏）。 */
    public static final int MIN_NAME_LENGTH = 3;
    public static final int MAX_NAME_LENGTH = 16;

    /**
     * 校验角色名，通过返回 {@code null}，不通过返回给用户看的原因。
     *
     * <p><b>必须和游戏自己的规则一致</b>：客户端把名字发给内部服务器后，
     * {@code ServerLoginPacketListenerImpl} 会做字符检查，不合法直接
     * {@code IllegalStateException: Invalid characters in username} 断开连接。
     * 单人游戏创建世界时表现为「世界建到一半被踢回主菜单」，非常像启动器的 bug。
     *
     * <p>规则：3~16 个字符，只允许英文字母、数字、下划线。
     * 中文、日文、空格、各种符号游戏都不接受 —— 这不是启动器的限制，改不了。
     */
    public static String checkName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() == 0) return "请输入角色名";
        int count = n.codePointCount(0, n.length());
        if (count < MIN_NAME_LENGTH) {
            return "角色名至少 " + MIN_NAME_LENGTH + " 个字符（当前 " + count + " 个）";
        }
        if (count > MAX_NAME_LENGTH) {
            return "角色名最多 " + MAX_NAME_LENGTH + " 个字符（当前 " + count + " 个）";
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                if (Character.isISOControl(c)) return "角色名不能包含换行、制表符这类控制字符";
                if (c > 127) {
                    return "角色名只能用英文字母、数字和下划线，中文/日文/全角字符 Minecraft 不接受"
                            + "\n（进世界时会被踢：Invalid characters in username）"
                            + "\n可以换成拼音，例如 fangshaoshan";
                }
                return "角色名只能用英文字母、数字和下划线，不能有空格或符号：「" + c + "」";
            }
        }
        return null;
    }

    /** 首字（用于头像圆圈），按码点取，避免把 emoji 之类的代理对切一半。 */
    public String initial() {
        if (username == null || username.isEmpty()) return "?";
        int end = username.offsetByCodePoints(0, 1);
        return username.substring(0, end).toUpperCase();
    }

    public String typeLabel() {
        String t = type == null ? "" : type;
        if ("msa".equals(t)) return "微软正版";
        if ("authlib".equals(t)) {
            return "外置登录" + (serverName == null || Compat.isBlank(serverName) ? "" : "（" + serverName + "）");
        }
        return "离线账户";
    }

    /** 启动参数里的 userType：微软账户必须用 msa，否则皮肤/披风不生效。 */
    public String userType() {
        return "msa".equals(type) ? "msa" : "legacy";
    }

    @Override
    public String toString() {
        return username + "（" + typeLabel() + "）";
    }
}
