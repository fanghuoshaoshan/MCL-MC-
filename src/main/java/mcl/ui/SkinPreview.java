package mcl.ui;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

/**
 * 皮肤预览：把 64x64 的皮肤纹理拼成一个"纸娃娃"。
 *
 * <p>只画正面（头 / 身体 / 双臂 / 双腿 + 帽子层 + 披风），不依赖 3D 引擎，
 * 和 HMCL 账户页的小人是同一类做法：够看、够快、零依赖。
 */
public class SkinPreview extends JPanel {
    private BufferedImage skin;
    private BufferedImage cape;
    private String title = "";
    private String subtitle = "";

    public SkinPreview() {
        setOpaque(false);
    }

    public void setSkin(BufferedImage skin, BufferedImage cape, String title, String subtitle) {
        this.skin = skin;
        this.cape = cape;
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        repaint();
    }

    public void clear() {
        skin = null;
        cape = null;
        title = "";
        subtitle = "";
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        int w = getWidth();
        int h = getHeight();
        int textH = 40;
        int unit = Math.max(2, Math.min((w - 24) / 18, (h - textH - 16) / 34));
        int cx = w / 2;
        int top = Math.max(6, (h - textH - unit * 32) / 2);

        if (skin == null) {
            g2.setColor(Ui.MUTED);
            g2.setFont(Ui.font(java.awt.Font.PLAIN, 12f));
            String msg = "未获取皮肤";
            int tw = g2.getFontMetrics().stringWidth(msg);
            g2.drawString(msg, (w - tw) / 2, top + unit * 16);
        } else {
            // 影子
            g2.setColor(new Color(0, 0, 0, 26));
            g2.fill(new Ellipse2D.Double(cx - unit * 7.0, top + unit * 31.4, unit * 14.0, unit * 3.2));

            boolean legacy = skin.getHeight() < 64;
            int u = unit;

            // 披风（在身体后面）
            if (cape != null) {
                BufferedImage capeFront = sub(cape, 1, 1, 10, 16);
                if (capeFront != null) {
                    g2.drawImage(capeFront, cx - 5 * u, top + 8 * u + u / 2, 10 * u, 16 * u, null);
                }
            }

            // 腿
            draw(g2, sub(skin, 4, 20, 4, 12), cx - 4 * u, top + 20 * u, 4 * u, 12 * u);
            if (legacy) {
                draw(g2, flipX(sub(skin, 4, 20, 4, 12)), cx, top + 20 * u, 4 * u, 12 * u);
            } else {
                draw(g2, sub(skin, 20, 52, 4, 12), cx, top + 20 * u, 4 * u, 12 * u);
            }

            // 身体
            draw(g2, sub(skin, 20, 20, 8, 12), cx - 4 * u, top + 8 * u, 8 * u, 12 * u);

            // 手臂（观察者左边 = 角色的右手）
            draw(g2, sub(skin, 44, 20, 4, 12), cx - 8 * u, top + 8 * u, 4 * u, 12 * u);
            if (legacy) {
                draw(g2, flipX(sub(skin, 44, 20, 4, 12)), cx + 4 * u, top + 8 * u, 4 * u, 12 * u);
            } else {
                draw(g2, sub(skin, 36, 52, 4, 12), cx + 4 * u, top + 8 * u, 4 * u, 12 * u);
            }

            // 头 + 帽子层
            draw(g2, sub(skin, 8, 8, 8, 8), cx - 4 * u, top, 8 * u, 8 * u);
            draw(g2, sub(skin, 40, 8, 8, 8), cx - 4 * u, top, 8 * u, 8 * u);

            // 身体 / 手臂 / 腿的第二层（外套、袖子、裤脚）
            draw(g2, sub(skin, 20, 36, 8, 12), cx - 4 * u, top + 8 * u, 8 * u, 12 * u);
            draw(g2, sub(skin, 44, 36, 4, 12), cx - 8 * u, top + 8 * u, 4 * u, 12 * u);
            if (!legacy) {
                draw(g2, sub(skin, 52, 52, 4, 12), cx + 4 * u, top + 8 * u, 4 * u, 12 * u);
            }
        }

        // 名字
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int y = h - textH + 6;
        if (!title.isEmpty()) {
            g2.setFont(Ui.font(java.awt.Font.BOLD, 13.5f));
            g2.setColor(Ui.TEXT);
            int tw = g2.getFontMetrics().stringWidth(title);
            g2.drawString(title, (w - tw) / 2, y + 12);
        }
        if (!subtitle.isEmpty()) {
            g2.setFont(Ui.font(java.awt.Font.PLAIN, 11.5f));
            g2.setColor(Ui.MUTED);
            int tw = g2.getFontMetrics().stringWidth(subtitle);
            g2.drawString(subtitle, (w - tw) / 2, y + 28);
        }
        g2.dispose();
    }

    private static void draw(Graphics2D g2, BufferedImage img, int x, int y, int w, int h) {
        if (img == null) return;
        g2.drawImage(img, x, y, w, h, null);
    }

    /** 取纹理子区域，越界返回 null（旧版 64x32 皮肤没有左臂左腿）。 */
    public static BufferedImage sub(BufferedImage img, int x, int y, int w, int h) {
        if (img == null) return null;
        if (x + w > img.getWidth() || y + h > img.getHeight()) return null;
        return img.getSubimage(x, y, w, h);
    }

    public static BufferedImage flipX(BufferedImage img) {
        if (img == null) return null;
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out.setRGB(w - 1 - x, y, img.getRGB(x, y));
        }
        return out;
    }

    /** 头像：给账户列表用的小方块（8x8 脸部放大）。 */
    public static BufferedImage head(BufferedImage skin, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        BufferedImage face = skin == null ? null : sub(skin, 8, 8, 8, 8);
        if (face != null) {
            // 圆角裁切
            java.awt.geom.Area clip = new java.awt.geom.Area(
                    new java.awt.geom.RoundRectangle2D.Float(0, 0, size, size, size * 0.32f, size * 0.32f));
            g2.setClip(clip);
            g2.drawImage(face, 0, 0, size, size, null);
        } else {
            g2.setColor(Ui.blend(Ui.accent(), Color.WHITE, 0.25f));
            g2.fillRoundRect(0, 0, size, size, (int) (size * 0.32), (int) (size * 0.32));
        }
        g2.dispose();
        return out;
    }

    /** 无皮肤时的占位头像（首字母）。 */
    public static BufferedImage letterAvatar(String letter, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(Ui.blend(Ui.accent(), Color.WHITE, 0.25f));
        g2.fillRoundRect(0, 0, size - 1, size - 1, (int) (size * 0.34), (int) (size * 0.34));
        g2.setColor(Ui.accent());
        g2.setStroke(new BasicStroke(1.6f));
        g2.drawRoundRect(1, 1, size - 3, size - 3, (int) (size * 0.34), (int) (size * 0.34));
        g2.setColor(Color.WHITE);
        g2.setFont(Ui.font(java.awt.Font.BOLD, size * 0.44f));
        String t = "?";
        if (letter != null && !letter.isEmpty()) {
            // 按码点取首字，中文 / emoji 角色名不会被切成半个代理对
            t = letter.substring(0, letter.offsetByCodePoints(0, 1)).toUpperCase();
        }
        int tw = g2.getFontMetrics().stringWidth(t);
        g2.drawString(t, (size - tw) / 2f, size * 0.5f + size * 0.16f);
        g2.dispose();
        return out;
    }
}
