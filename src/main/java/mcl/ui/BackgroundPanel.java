package mcl.ui;

import mcl.util.Compat;

import mcl.config.Settings;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.nio.file.Files;
import java.nio.file.Path;

/** 背景层：主题渐变 + 可选自定义壁纸。 */
public class BackgroundPanel extends javax.swing.JPanel {
    private Image image;

    public BackgroundPanel() {
        setOpaque(false);
    }

    public void setWallpaper(Path path) {
        image = null;
        if (path != null && Files.isRegularFile(path)) {
            try {
                image = ImageIO.read(path.toFile());
            } catch (Exception ignored) {
            }
        }
        repaint();
    }

    public void loadFrom(Settings settings) {
        setWallpaper(settings.backgroundImage == null || Compat.isBlank(settings.backgroundImage)
                ? null : java.nio.file.Paths.get(settings.backgroundImage));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        Color a = Ui.blend(Ui.accent(), Color.WHITE, 0.82f);
        Color b = Ui.BG;
        g2.setPaint(new GradientPaint(0, 0, a, 0, getHeight(), b));
        g2.fillRect(0, 0, getWidth(), getHeight());
        if (image != null) {
            double scale = Math.max(getWidth() / (double) image.getWidth(null),
                    getHeight() / (double) image.getHeight(null));
            int w = (int) (image.getWidth(null) * scale);
            int h = (int) (image.getHeight(null) * scale);
            int x = (getWidth() - w) / 2;
            int y = (getHeight() - h) / 2;
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            g2.drawImage(image, x, y, w, h, null);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, getWidth(), getHeight());
        }
        g2.dispose();
    }
}
