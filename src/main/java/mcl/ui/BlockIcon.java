package mcl.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 版本类型的小方块图标。
 *
 * <p>以前版本列表左边是一个写着「快照」「正式版」的色块 —— 换成图标之后这里也没得可用：
 * Mojang 并不给每个版本发 logo。所以干脆按《我的世界》的方块风格自己画：
 *
 * <ul>
 *   <li>正式版 → 草方块（绿顶 + 泥土）</li>
 *   <li>快照 → 命令方块（琥珀色 + 内框）</li>
 *   <li>远古版本 → 圆石（灰）</li>
 *   <li>其它 / 加了加载器 → 钻石块（青）</li>
 * </ul>
 *
 * <p>16×16 像素画再按<b>整数倍</b>放大 —— 只有整数倍最近邻才是硬边像素感，
 * 3.7 倍这种拉伸会把方块糊成一团。放大后居中放进目标尺寸，
 * 顺带切个圆角，跟列表里那些真实 logo 的圆角保持一致。
 */
public final class BlockIcon {
    /** 像素画的原生分辨率，跟游戏里的方块贴图一样是 16。 */
    private static final int GRID = 16;

    /** kind + "@" + 边长 → 成品图。种类就四种、常用尺寸两三个，不会涨。 */
    private static final Map<String, BufferedImage> CACHE = new HashMap<String, BufferedImage>();

    // 草方块
    private static final int G_LIGHT = 0xFF8CD45C;
    private static final int G_BASE = 0xFF6CAE3C;
    private static final int G_DARK = 0xFF548C2B;
    private static final int D_LIGHT = 0xFF9C6E46;
    private static final int D_BASE = 0xFF7E5636;
    private static final int D_DARK = 0xFF5B3B22;

    // 命令方块
    private static final int C_LIGHT = 0xFFE0A45E;
    private static final int C_BASE = 0xFFC8813C;
    private static final int C_DARK = 0xFF9E5D26;
    private static final int C_DEEP = 0xFF6E3E16;

    // 圆石
    private static final int S_LIGHT = 0xFFB4B4B4;
    private static final int S_BASE = 0xFF8E8E8E;
    private static final int S_DARK = 0xFF6B6B6B;
    private static final int S_DEEP = 0xFF4F4F4F;

    // 钻石块（用作「其它」）
    private static final int Q_LIGHT = 0xFF9DE9E3;
    private static final int Q_BASE = 0xFF3FB6AE;
    private static final int Q_DARK = 0xFF1F8A84;
    private static final int Q_DEEP = 0xFF136360;

    private BlockIcon() {}

    /**
     * 按版本类型取图标。
     *
     * @param type 元数据里的类型：release / snapshot / old_beta / old_alpha
     * @param px   目标边长
     */
    public static BufferedImage get(String type, int px) {
        return build(kind(type), px <= 0 ? GRID : px);
    }

    /**
     * 给「已安装版本」用：这类记录的 type 经常是空的，
     * 空的时候看版本号里有没有加载器痕迹，有就画钻石块，没有就按正式版画。
     */
    public static BufferedImage forVersion(String id, String type, int px) {
        String t = type == null ? "" : type.trim();
        if (!t.isEmpty()) return get(t, px);
        String lower = id == null ? "" : id.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("fabric") || lower.contains("quilt") || lower.contains("forge")
                || lower.contains("optifine") || lower.contains("liteloader")) {
            return get("other", px);
        }
        return get("release", px);
    }

    /** 把版本类型归到四种画法之一。 */
    static String kind(String type) {
        String t = type == null ? "" : type.trim();
        if (t.startsWith("old")) return "old";
        if ("snapshot".equals(t)) return "snapshot";
        if ("release".equals(t)) return "release";
        if (t.isEmpty()) return "release";
        return "other";
    }

    /** 画好 16×16 的像素，再整体放大成 px×px（带圆角）。 */
    private static BufferedImage build(String kind, int px) {
        String key = kind + "@" + px;
        synchronized (CACHE) {
            BufferedImage hit = CACHE.get(key);
            if (hit != null) return hit;
            BufferedImage made = render(kind, px);
            CACHE.put(key, made);
            return made;
        }
    }

    private static BufferedImage render(String kind, int px) {
        int[] px16 = pixels(kind);
        BufferedImage small = new BufferedImage(GRID, GRID, BufferedImage.TYPE_INT_ARGB);
        small.setRGB(0, 0, GRID, GRID, px16, 0, GRID);

        BufferedImage out = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int factor = Math.max(1, px / GRID);
            int art = GRID * factor;
            int off = (px - art) / 2;
            float arc = Math.max(6f, art * 0.22f);
            // 直角方块贴着脸太冲，切个圆角；antialias 让边缘软一点点
            g.setClip(new RoundRectangle2D.Float(off, off, art, art, arc, arc));
            g.drawImage(small, off, off, art, art, null);
            g.setClip(null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static int[] pixels(String kind) {
        int[] p = new int[GRID * GRID];
        // 固定种子：同一个类型每次画出来一模一样，截图和自检才好比对
        Random r = new Random(0x9E3779B9L ^ kind.hashCode());
        if ("snapshot".equals(kind)) {
            command(p, r);
        } else if ("old".equals(kind)) {
            cobble(p, r);
        } else if ("other".equals(kind)) {
            diamond(p, r);
        } else {
            grass(p, r);
        }
        return p;
    }

    /** 草方块：上面一两像素的草，草边参差不齐，下面全是泥土。 */
    private static void grass(int[] p, Random r) {
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                boolean green = y < 4 || (y < 6 && r.nextInt(3) > 0);
                int c;
                if (green) {
                    c = y == 0 ? G_LIGHT : (r.nextInt(4) == 0 ? G_DARK : G_BASE);
                } else {
                    c = r.nextInt(5) == 0 ? D_LIGHT : (r.nextInt(7) == 0 ? D_DARK : D_BASE);
                }
                p[y * GRID + x] = edge(x, y, c, D_DARK);
            }
        }
    }

    /** 命令方块：琥珀底色 + 深色内框，好和正式版一眼分开。 */
    private static void command(int[] p, Random r) {
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int c;
                if (x == 0 || y == 0 || x == GRID - 1 || y == GRID - 1) {
                    c = C_DEEP;
                } else if (x == 3 || x == GRID - 4 || y == 3 || y == GRID - 4) {
                    c = C_DARK;
                } else {
                    c = r.nextInt(4) == 0 ? C_LIGHT : C_BASE;
                }
                p[y * GRID + x] = c;
            }
        }
    }

    /** 圆石：把 2×2 当成一块石头随机取深浅，拼出不规则的碎石感。 */
    private static void cobble(int[] p, Random r) {
        int cells = GRID / 2;
        int[][] shade = new int[cells][cells];
        for (int cy = 0; cy < cells; cy++) {
            for (int cx = 0; cx < cells; cx++) {
                int n = r.nextInt(10);
                shade[cy][cx] = n < 3 ? S_LIGHT : n < 8 ? S_BASE : S_DARK;
            }
        }
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int c = shade[y / 2][x / 2];
                // 石块交界处压一条暗缝，不然会糊成一片灰
                if ((x % 2 == 1 && (x / 2 + 1 < cells)) || (y % 2 == 1 && (y / 2 + 1 < cells))) {
                    if (r.nextInt(3) == 0) c = S_DARK;
                }
                p[y * GRID + x] = edge(x, y, c, S_DEEP);
            }
        }
    }

    /** 钻石块：青色底 + 几个亮点，用来表示「带加载器的版本」。 */
    private static void diamond(int[] p, Random r) {
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int c = r.nextInt(6) == 0 ? Q_LIGHT : Q_BASE;
                // 中间打个菱形高光，别让青色块看起来像纯色占位
                int dx = Math.abs(x - 7);
                int dy = Math.abs(y - 7);
                if (dx + dy <= 2) c = Q_LIGHT;
                else if (dx + dy <= 4 && r.nextInt(3) == 0) c = Q_DARK;
                p[y * GRID + x] = edge(x, y, c, Q_DEEP);
            }
        }
    }

    /** 最外一圈压暗，方块才有厚度感。 */
    private static int edge(int x, int y, int c, int dark) {
        if (y == GRID - 1 || x == GRID - 1) return dark;
        if (y == 0 || x == 0) {
            Color col = new Color(c, true);
            return lighten(col, 0.10f).getRGB();
        }
        return c;
    }

    private static Color lighten(Color c, float f) {
        return new Color(
                Math.min(255, Math.round(c.getRed() + (255 - c.getRed()) * f)),
                Math.min(255, Math.round(c.getGreen() + (255 - c.getGreen()) * f)),
                Math.min(255, Math.round(c.getBlue() + (255 - c.getBlue()) * f)),
                c.getAlpha());
    }
}
