package mcl.net;

import mcl.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 下载源。MCL 保留版本 JSON 中的官方 URL，在下载时按源做映射，
 * 这样同一份元数据既可用官方源也可用 BMCLAPI 镜像，失败可自动回退。
 */
public enum Source {
    MOJANG("官方源", "https://launchermeta.mojang.com"),
    BMCLAPI("BMCLAPI 镜像", "https://bmclapi2.bangbang93.com");

    public final String displayName;
    public final String baseUrl;

    /** 连续失败计数：镜像站整体挂掉时，避免每个文件都白等三次重试。 */
    private final AtomicInteger failures = new AtomicInteger();
    private volatile long disabledUntil;

    Source(String displayName, String baseUrl) {
        this.displayName = displayName;
        this.baseUrl = baseUrl;
    }

    public static Source of(String name) {
        for (Source s : values()) if (s.name().equalsIgnoreCase(name)) return s;
        return BMCLAPI;
    }

    /** 熔断期内直接走官方地址。 */
    public boolean available() {
        return this == MOJANG || System.currentTimeMillis() >= disabledUntil;
    }

    public void recordSuccess() {
        failures.set(0);
    }

    public void recordFailure() {
        if (this == MOJANG) return;
        if (failures.incrementAndGet() >= 6) {
            disabledUntil = System.currentTimeMillis() + 5 * 60_000L;
            failures.set(0);
            Log.warn(displayName + " 连续失败 6 次，接下来 5 分钟直接使用官方地址");
        }
    }

    /** 把官方 URL 映射到当前源；无法映射的原样返回。 */
    public String map(String url) {
        if (url == null) return null;
        if (this == MOJANG) return url;
        String u = url;
        u = u.replace("https://launchermeta.mojang.com", baseUrl);
        u = u.replace("http://launchermeta.mojang.com", baseUrl);
        u = u.replace("https://piston-meta.mojang.com", baseUrl);
        u = u.replace("https://piston-data.mojang.com", baseUrl);
        u = u.replace("https://launcher.mojang.com", baseUrl);
        u = u.replace("https://libraries.minecraft.net", baseUrl + "/maven");
        u = u.replace("https://resources.download.minecraft.net", baseUrl + "/assets");
        u = u.replace("https://maven.minecraftforge.net", baseUrl + "/maven");
        u = u.replace("https://files.minecraftforge.net/maven", baseUrl + "/maven");
        u = u.replace("https://maven.fabricmc.net", baseUrl + "/maven");
        return u;
    }

    /** 版本清单地址，多个候选依次尝试。 */
    public String[] manifestUrls() {
        if (this == MOJANG) {
            return new String[]{
                    "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json",
                    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
            };
        }
        return new String[]{
                baseUrl + "/mc/game/version_manifest_v2.json",
                baseUrl + "/mc/game/version_manifest.json"
        };
    }

    public static Source[] all() {
        return values();
    }
}
