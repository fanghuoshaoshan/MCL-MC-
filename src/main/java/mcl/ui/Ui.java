package mcl.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;

/** 界面基础组件与配色。 */
public final class Ui {
    public static final Color BG = new Color(0xF3F4F8);
    public static final Color CARD = Color.WHITE;
    public static final Color TEXT = new Color(0x2C2F3A);
    public static final Color MUTED = new Color(0x8B90A0);
    public static final Color LINE = new Color(0xE3E6EF);
    public static final Font BASE = pickFont();

    private static Color accent = new Color(0x5C6BC0);

    private Ui() {}

    private static Font pickFont() {
        String[] names = {"Microsoft YaHei UI", "微软雅黑", "PingFang SC", "Noto Sans CJK SC", "Dialog"};
        for (String n : names) {
            Font f = new Font(n, Font.PLAIN, 13);
            if (f.getFamily().equalsIgnoreCase(n)) return f.deriveFont(13f);
        }
        return new Font("Dialog", Font.PLAIN, 13);
    }

    public static Color accent() {
        return accent;
    }

    public static void setAccent(Color c) {
        if (c != null) accent = c;
    }

    public static Color accentLight() {
        return blend(accent, Color.WHITE, 0.88f);
    }

    public static Color blend(Color a, Color b, float ratioB) {
        float r = 1 - ratioB;
        return new Color(
                Math.round(a.getRed() * r + b.getRed() * ratioB),
                Math.round(a.getGreen() * r + b.getGreen() * ratioB),
                Math.round(a.getBlue() * r + b.getBlue() * ratioB));
    }

    public static Font font(int style, float size) {
        return BASE.deriveFont(style, size);
    }

    public static JLabel label(String text, int style, float size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font(style, size));
        l.setForeground(color);
        return l;
    }

    public static JLabel title(String text) {
        return label(text, Font.BOLD, 20f, TEXT);
    }

    public static JLabel subtitle(String text) {
        return label(text, Font.PLAIN, 12.5f, MUTED);
    }

    public static JLabel muted(String text) {
        return label(text, Font.PLAIN, 12f, MUTED);
    }

    public static JPanel row(int gap, Component... comps) {
        JPanel p = transparent();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        for (int i = 0; i < comps.length; i++) {
            if (i > 0) p.add(Box.createHorizontalStrut(gap));
            p.add(comps[i]);
        }
        return p;
    }

    public static JPanel col(int gap, Component... comps) {
        JPanel p = transparent();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (int i = 0; i < comps.length; i++) {
            if (i > 0) p.add(Box.createVerticalStrut(gap));
            if (comps[i] instanceof JComponent) ((JComponent) comps[i]).setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(comps[i]);
        }
        return p;
    }

    public static JPanel transparent() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        return p;
    }

    public static JPanel card() {
        RoundedPanel p = new RoundedPanel(16, new Color(255, 255, 255, 244), LINE);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        return p;
    }

    public static JButton primary(String text) {
        return new RoundButton(text, true);
    }

    public static JButton ghost(String text) {
        return new RoundButton(text, false);
    }

    public static JScrollPane scroll(Component c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        return sp;
    }

    public static Component gap(int w, int h) {
        return Box.createRigidArea(new Dimension(w, h));
    }

    /**
     * 列表行的选中样式：整行淡色圆角底 + 左侧一条实色竖条。
     *
     * <p>以前靠给左侧图标块加深底色来表示选中，自从图标换成项目自己的 logo 之后
     * 这招就不灵了 —— 图片盖住了底色，选中和没选中长得一模一样。
     * 现在选中信息画在行背景上，图标区一点都不用动。
     *
     * @param tint 底色向白色稀释的比例，越大越淡
     */
    public static void paintRowSelection(Graphics g, Component c, float tint) {
        int w = c.getWidth();
        int h = c.getHeight();
        if (w <= 0 || h <= 0) return;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(blend(accent, Color.WHITE, tint));
            g2.fill(new java.awt.geom.RoundRectangle2D.Float(0, 2, w, h - 4, 12, 12));
            g2.setColor(accent);
            g2.fill(new java.awt.geom.RoundRectangle2D.Float(3, Math.max(8, h / 4), 3, h - 2 * Math.max(8, h / 4), 3, 3));
        } finally {
            g2.dispose();
        }
    }

    public static String bytes(long b) {
        if (b <= 0) return "-";
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    /** 等宽/普通字体的只读文本区，方便塞进自定义对话框。 */
    public static javax.swing.JTextArea textArea(String text, int width, int height) {
        javax.swing.JTextArea area = new javax.swing.JTextArea(text == null ? "" : text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Ui.font(Font.PLAIN, 12.5f));
        area.setBackground(CARD);
        area.setForeground(TEXT);
        area.setCaretPosition(0);
        area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        area.setPreferredSize(new Dimension(width, height));
        return area;
    }

    /** 只读文本对话框（用于展示更新列表、冲突检测结果等长文本）。 */
    public static void showText(java.awt.Component parent, String title, String text, int width, int height) {
        javax.swing.JTextArea area = new javax.swing.JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Ui.font(Font.PLAIN, 12.5f));
        area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        javax.swing.JScrollPane sp = scroll(area);
        sp.setPreferredSize(new Dimension(width, height));
        javax.swing.JPanel wrap = new javax.swing.JPanel(new java.awt.BorderLayout());
        wrap.setBackground(CARD);
        wrap.add(sp, java.awt.BorderLayout.CENTER);
        javax.swing.JOptionPane.showMessageDialog(parent, wrap, title,
                javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    /** 圆角卡片。 */
    public static class RoundedPanel extends JPanel {
        private final int arc;
        private Color fill;
        private Color stroke;

        public RoundedPanel(int arc, Color fill, Color stroke) {
            this.arc = arc;
            this.fill = fill;
            this.stroke = stroke;
            setOpaque(false);
        }

        public void setFill(Color c) {
            this.fill = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            if (stroke != null) {
                g2.setColor(stroke);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 圆角按钮（实心 / 描边两种）。 */
    public static class RoundButton extends JButton {
        private final boolean filled;
        private boolean hover;

        public RoundButton(String text, boolean filled) {
            super(text);
            this.filled = filled;
            setFont(font(Font.BOLD, 13f));
            setForeground(filled ? Color.WHITE : accent);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(9, 20, 9, 20));
            addMouseListener(new MouseAdapter() {
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

        public void refreshColors() {
            setForeground(filled ? Color.WHITE : (isEnabled() ? accent : MUTED));
            repaint();
        }

        @Override
        public void setEnabled(boolean b) {
            super.setEnabled(b);
            refreshColors();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = filled ? accent : blend(accent, Color.WHITE, 0.9f);
            if (!isEnabled()) base = new Color(0xE6E8EF);
            else if (hover) base = filled ? accent.darker() : blend(accent, Color.WHITE, 0.8f);
            g2.setColor(base);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            if (!filled) {
                g2.setColor(blend(accent, Color.WHITE, 0.6f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * 分段选择条：一排圆角胶囊，点哪个选哪个。
     *
     * <p>用来替掉那些只有三四个选项的下拉框 —— 下拉框要先点开才知道有哪些选项，
     * 而且点开之后还得再点一次。选项少的时候全摆出来、当前选哪个一眼就能看到，
     * 点一下也就切换了。
     */
    public static class Chips extends JPanel {
        private static final int PAD_X = 13;
        private static final int HEIGHT = 32;

        private final String[] labels;
        private final java.util.List<java.awt.event.ActionListener> listeners =
                new java.util.ArrayList<java.awt.event.ActionListener>();
        private int selected;
        private int hover = -1;

        public Chips(String... labels) {
            this.labels = labels == null ? new String[0] : labels;
            setOpaque(false);
            setFont(font(Font.BOLD, 12.5f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(width(), HEIGHT));
            setMaximumSize(new Dimension(width(), HEIGHT));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    int i = indexAt(e.getX());
                    if (i >= 0) setSelectedIndex(i);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }
            });
            addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int i = indexAt(e.getX());
                    if (i != hover) {
                        hover = i;
                        repaint();
                    }
                }
            });
        }

        public int getSelectedIndex() {
            return selected;
        }

        public void setSelectedIndex(int i) {
            if (i < 0 || i >= labels.length || i == selected) return;
            selected = i;
            repaint();
            for (java.awt.event.ActionListener l : listeners) {
                l.actionPerformed(new java.awt.event.ActionEvent(this, 0, labels[selected]));
            }
        }

        public void addActionListener(java.awt.event.ActionListener l) {
            if (l != null) listeners.add(l);
        }

        private int width() {
            java.awt.FontMetrics fm = metrics();
            int w = 8;
            for (String s : labels) w += fm.stringWidth(s) + PAD_X * 2 + 4;
            return w;
        }

        private int indexAt(int x) {
            int cx = 4;
            for (int i = 0; i < labels.length; i++) {
                int w = metrics().stringWidth(labels[i]) + PAD_X * 2;
                if (x >= cx && x < cx + w) return i;
                cx += w + 4;
            }
            return -1;
        }

        private java.awt.FontMetrics metrics() {
            Font f = getFont() == null ? BASE : getFont();
            return getFontMetrics(f);
        }

        @Override
        public void setFont(Font f) {
            super.setFont(f);
            // 构造期间（super() 里装 UI 默认值）label 数组还没赋值，也没必要算宽度
            if (labels == null || getFont() == null) return;
            setPreferredSize(new Dimension(width(), HEIGHT));
            setMaximumSize(new Dimension(width(), HEIGHT));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int h = getHeight();
                g2.setColor(blend(accent, Color.WHITE, 0.90f));
                g2.fillRoundRect(0, 0, getWidth(), h, 11, 11);

                Font f = getFont() == null ? BASE : getFont();
                java.awt.FontMetrics fm = g2.getFontMetrics(f);
                int cx = 4;
                for (int i = 0; i < labels.length; i++) {
                    int w = fm.stringWidth(labels[i]) + PAD_X * 2;
                    boolean on = i == selected;
                    if (on) {
                        g2.setColor(Color.WHITE);
                        g2.fillRoundRect(cx, 3, w, h - 6, 9, 9);
                        g2.setColor(blend(accent, Color.WHITE, 0.55f));
                        g2.drawRoundRect(cx, 3, w - 1, h - 7, 9, 9);
                    } else if (i == hover) {
                        g2.setColor(new Color(255, 255, 255, 150));
                        g2.fillRoundRect(cx, 3, w, h - 6, 9, 9);
                    }
                    g2.setColor(on ? accent : MUTED);
                    g2.setFont(f);
                    int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString(labels[i], cx + PAD_X, ty);
                    cx += w + 4;
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** 侧边栏导航按钮。 */
    public static class NavButton extends JButton {
        private boolean selected;
        private boolean hover;

        public NavButton(String text) {
            super(text);
            setHorizontalAlignment(SwingConstants.LEFT);
            setFont(font(Font.PLAIN, 14f));
            setForeground(new Color(255, 255, 255, 220));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(11, 18, 11, 18));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            addMouseListener(new MouseAdapter() {
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

        public void setNavSelected(boolean b) {
            selected = b;
            setFont(font(b ? Font.BOLD : Font.PLAIN, 14f));
            setForeground(new Color(255, 255, 255, b ? 255 : 215));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g2.setColor(new Color(255, 255, 255, 46));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            } else if (hover) {
                g2.setColor(new Color(255, 255, 255, 24));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
