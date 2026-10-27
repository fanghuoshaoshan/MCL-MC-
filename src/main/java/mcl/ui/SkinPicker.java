package mcl.ui;

import mcl.auth.ProfileSkins;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Box;
import javax.swing.BoxLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * 皮肤模型选择器：Steve（经典）/ Alex（纤细）。
 *
 * <p>不用下拉框 —— 新建角色的时候得一眼看出选的是谁，所以直接把两个头摆出来，
 * 点哪张就是哪张。选中项用主题色描边加浅色底。
 */
public class SkinPicker extends JPanel {

    /** 选中项变化时回调，参数是 {@code "classic"} 或 {@code "slim"}。 */
    public interface Listener {
        void onPick(String model);
    }

    private static final int GAP = 8;

    private final Card classicCard;
    private final Card slimCard;
    private String model = "classic";
    private Listener listener;

    public SkinPicker() {
        this(false);
    }

    /**
     * @param compact {@code true} 时卡片矮一些、不显示中文副标题，给侧边窄栏用
     */
    public SkinPicker(boolean compact) {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        classicCard = new Card("Steve", compact ? "" : "史蒂夫 · 经典", "classic",
                SkinPreview.head(ProfileSkins.steve(), compact ? 34 : 42), compact);
        slimCard = new Card("Alex", compact ? "" : "爱丽丝 · 纤细", "slim",
                SkinPreview.head(ProfileSkins.alex(), compact ? 34 : 42), compact);
        add(classicCard);
        add(Box.createHorizontalStrut(GAP));
        add(slimCard);
        refreshSelection();
    }

    public String model() {
        return model;
    }

    /** 设置选中项，<b>不触发</b>回调（用于回填界面状态，避免自我循环）。 */
    public void setModel(String m) {
        String n = ProfileSkins.normalizeModel(m);
        model = n.isEmpty() ? "classic" : n;
        refreshSelection();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void refreshSelection() {
        classicCard.setSelected("classic".equals(model));
        slimCard.setSelected("slim".equals(model));
        revalidate();
        repaint();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        classicCard.setEnabled(enabled);
        slimCard.setEnabled(enabled);
    }

    private void pick(String m) {
        if (!isEnabled()) return;
        if (m.equals(model)) return;
        model = m;
        refreshSelection();
        if (listener != null) listener.onPick(m);
    }

    /** 单张卡片。 */
    private class Card extends JComponent {
        private final String title;
        private final String subtitle;
        private final String value;
        private final BufferedImage head;
        private final boolean compact;
        private boolean selected;
        private boolean hover;

        Card(String title, String subtitle, String value, BufferedImage head, boolean compact) {
            this.title = title;
            this.subtitle = subtitle;
            this.value = value;
            this.head = head;
            this.compact = compact;
            int w = compact ? 68 : 84;
            int h = compact ? 74 : 96;
            setPreferredSize(new Dimension(w, h));
            setMinimumSize(new Dimension(w, h));
            setMaximumSize(new Dimension(w, h));
            setAlignmentY(TOP_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("slim".equals(value)
                    ? "Alex：纤细模型，3 像素手臂"
                    : "Steve：经典模型，4 像素手臂");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    pick(Card.this.value);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        void setSelected(boolean b) {
            if (selected == b) return;
            selected = b;
            repaint();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setCursor(Cursor.getPredefinedCursor(
                    enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            boolean on = isEnabled();

            Color fill = on ? (selected ? Ui.accentLight() : Color.WHITE) : new Color(0xF5F6F9);
            Color stroke = !on ? Ui.LINE
                    : selected ? Ui.accent()
                    : hover ? Ui.blend(Ui.accent(), Color.WHITE, 0.45f) : Ui.LINE;

            g2.setColor(fill);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);
            g2.setColor(stroke);
            g2.setStroke(new BasicStroke(selected ? 1.8f : 1f));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);

            int headSize = compact ? 34 : 42;
            int hx = (w - headSize) / 2;
            int hy = compact ? 8 : 11;
            if (head != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                if (!on) g2.setComposite(java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, 0.4f));
                g2.drawImage(head, hx, hy, headSize, headSize, null);
                g2.setComposite(java.awt.AlphaComposite.SrcOver);
            }

            int textY = hy + headSize + (compact ? 13 : 17);
            g2.setFont(Ui.font(Font.BOLD, compact ? 11.5f : 12.5f));
            g2.setColor(!on ? Ui.MUTED : selected ? Ui.accent() : Ui.TEXT);
            center(g2, title, w, textY);
            if (!subtitle.isEmpty()) {
                g2.setFont(Ui.font(Font.PLAIN, 10f));
                g2.setColor(Ui.MUTED);
                center(g2, subtitle, w, textY + 14);
            }
            g2.dispose();
        }

        private void center(Graphics2D g2, String text, int width, int baselineY) {
            int tw = g2.getFontMetrics().stringWidth(text);
            g2.drawString(text, (width - tw) / 2, baselineY);
        }
    }
}
