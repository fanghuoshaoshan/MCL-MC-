package mcl.auth;

import mcl.util.Compat;

import mcl.config.Account;
import mcl.net.Http;
import mcl.util.Digest;
import mcl.util.Log;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 皮肤：给外置登录（authlib-injector）账户上传皮肤。
 *
 * <p>走 Yggdrasil 的 {@code PUT /api/user/profile/<uuid>/skin}，需要账户的 accessToken。
 * 皮肤格式要求 64x64（1.8+）或 64x32（旧版），且是 PNG。
 */
public final class Skins {

    private Skins() {}

    public static void upload(Account a, Path png, boolean slim) throws IOException {
        if (a == null || !"authlib".equals(a.type)) {
            throw new IOException("只有外置登录账户能上传皮肤（离线 / 微软账户请到官网或皮肤站设置）");
        }
        if (a.apiRoot == null || Compat.isBlank(a.apiRoot)) throw new IOException("账户缺少皮肤站地址");
        if (a.accessToken == null || Compat.isBlank(a.accessToken)) {
            throw new IOException("账户没有有效的 accessToken，请重新登录一次");
        }
        if (!Files.isRegularFile(png)) throw new IOException("找不到皮肤文件：" + png);
        long size = Files.size(png);
        if (size > 1L * 1024 * 1024) throw new IOException("皮肤文件太大（超过 1 MB）");
        BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) throw new IOException("不是有效的图片文件");
        int w = img.getWidth();
        int h = img.getHeight();
        if (!((w == 64 && (h == 64 || h == 32)) || (w == 128 && (h == 128 || h == 64)))) {
            throw new IOException("皮肤尺寸应为 64x64 / 64x32（或 128 的整数倍），当前是 " + w + "x" + h);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + a.accessToken);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", slim ? "slim" : "");
        String url = a.apiRoot + "/api/user/profile/" + a.uuid + "/skin";
        Http.putMultipart(url, headers, fields, "file", png, "image/png");
        Log.info("皮肤已上传：" + a.username + "（" + (slim ? "细手臂" : "经典") + "，" + Digest.sha1(png) + "）");
    }
}
