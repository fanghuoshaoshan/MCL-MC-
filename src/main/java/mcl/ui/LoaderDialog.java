package mcl.ui;

import mcl.install.FabricInstaller;
import mcl.install.ForgeInstaller;
import mcl.util.Log;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 装模组加载器的选择框。
 *
 * <p>以前是「系统下拉框选类型 → 拉一条网络 → 再来一个下拉框选版本 → 确定」，
 * 下拉框里只能看到一行字，五种加载器长得一模一样，还得点三次才能装。
 *
 * <p>现在左边一排卡片，每个加载器带自己的 logo 和一句话说明，点一下就切；
 * 右边是它对这个游戏版本可用的版本列表，带「最新 / 推荐 / 不稳定」小标签，
 * 双击直接装。查询走后台线程，切回已经查过的加载器不会再发一次请求。
 */
public final class LoaderDialog extends JDialog {
    private static final int ICON_PX = 34;

    /** 加载器条目：显示名、说明、logo 资源路径、兜底色块的颜色。 */
    private static final class KindInfo {
        final String id;
        final String desc;
        final String logo;
        final Color tint;

        KindInfo(String id, String desc, String logo, Color tint) {
            this.id = id;
            this.desc = desc;
            this.logo = logo;
            this.tint = tint;
        }
    }

    private static final KindInfo[] KINDS = {
            new KindInfo("Fabric", "轻量、更新最快，生态最大", "/mcl/logos/fabric.png", new Color(0xDBB88A)),
            new KindInfo("Quilt", "社区分支，兼容大多模组", "/mcl/logos/quilt.png", new Color(0x8E6FC9)),
            new KindInfo("Forge", "老牌加载器，整合包常用", "/mcl/logos/forge.png", new Color(0xC1440E)),
            new KindInfo("NeoForge", "Forge 接棒者，1.20.1+", "/mcl/logos/neoforge.png", new Color(0xF1852B)),
            // OptiFine 不在这里：它是模组，不是加载器。放进来等于让用户二选一，
            // 选了它就没法要 Forge 的模组。入口在「版本操作 → 安装 OptiFine」。
    };

    /** 解码过的 logo（id@尺寸 → 成品图），反复开关窗口不用重新读盘。 */
    private static final Map<String, java.awt.image.BufferedImage> LOGOS =
            new HashMap<String, java.awt.image.BufferedImage>();

    private final MainWindow win;
    private final String gameVersion;
    /** 非空表示「给这个整合包实例换加载器」，装完要把加载器收进它的目录。 */
    private final String targetInstance;
    private final Map<String, List<Item>> cache = new HashMap<String, List<Item>>();
    private final List<Card> cards = new ArrayList<Card>();
    private final DefaultListModel<Item> model = new DefaultListModel<Item>();
    private final JList<Item> list = new JList<Item>(model);
    private final JLabel hint = Ui.muted(" ");
    private final Ui.RoundButton installBtn = (Ui.RoundButton) Ui.primary("安装");

    private String kind = "Fabric";
    /** 列表为空时中间显示的那句话（查询中 / 查不到）。 */
    private String placeholder = "正在查询…";
    /** 每次切换加载器自增：异步结果回来时对不上就丢掉，免得串台。 */
    private int token;

    private LoaderDialog(MainWindow win, String gameVersion, String targetInstance) {
        super(SwingUtilities.getWindowAncestor(win),
                targetInstance == null ? "安装模组加载器" : "更换模组加载器",
                ModalityType.APPLICATION_MODAL);
        this.win = win;
        this.gameVersion = gameVersion;
        this.targetInstance = targetInstance;

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(Ui.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        root.add(header(), BorderLayout.NORTH);
        root.add(body(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);
        setSize(800, 570);
        setMinimumSize(new Dimension(700, 470));
        setLocationRelativeTo(win);
        getRootPane().setDefaultButton(installBtn);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        select(KINDS[0]);
    }

    /** 打开选择框；返回时用户已经确认或取消，安装由主窗口接管。 */
    public static void open(MainWindow win, String gameVersion) {
        open(win, gameVersion, null);
    }

    /**
     * @param targetInstance 整合包实例 id；非空时表示「换掉这个整合包里的加载器」，
     *                       装完会把加载器收进它的目录，不再往 versions/ 里多摆一个
     */
    public static void open(MainWindow win, String gameVersion, String targetInstance) {
        new LoaderDialog(win, gameVersion, targetInstance).setVisible(true);
    }

    // ---------------- 界面 ----------------

    private JPanel header() {
        if (targetInstance == null) {
            return Ui.col(4,
                    Ui.title("安装模组加载器"),
                    Ui.muted("为 " + gameVersion + " 选择加载器，再挑一个版本；缺原版会自动补装"));
        }
        return Ui.col(4,
                Ui.title("更换模组加载器"),
                Ui.muted("换掉整合包「" + targetInstance + "」里的加载器（" + gameVersion
                        + "）；新的会装进整合包自己的目录"));
    }

    private JPanel body() {
        JPanel p = new JPanel(new BorderLayout(14, 0));
        p.setOpaque(false);

        Ui.RoundedPanel left = (Ui.RoundedPanel) Ui.card();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        left.add(section("加载器"));
        for (KindInfo info : KINDS) {
            Card c = new Card(info);
            cards.add(c);
            left.add(c);
            left.add(Ui.gap(0, 6));
        }
        left.add(Box.createVerticalGlue());
        left.setPreferredSize(new Dimension(300, 0));

        Ui.RoundedPanel right = (Ui.RoundedPanel) Ui.card();
        right.setLayout(new BorderLayout(0, 8));
        right.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        right.add(section("可用版本"), BorderLayout.NORTH);

        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(42);
        list.setCellRenderer(new VersionRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) install();
            }
        });
        // 列表为空时（还在查 / 查不到）在中间留一句说明，别让用户对着白板发呆
        JPanel holder = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (!model.isEmpty()) return;
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(Ui.MUTED);
                    g2.setFont(Ui.font(Font.PLAIN, 12.5f));
                    FontMetrics fm = g2.getFontMetrics();
                    String[] lines = placeholder.split("\n");
                    int y = Math.max(24, getHeight() / 3) - (lines.length - 1) * fm.getHeight() / 2 + fm.getAscent();
                    for (String line : lines) {
                        g2.drawString(line, Math.max(8, (getWidth() - fm.stringWidth(line)) / 2), y);
                        y += fm.getHeight();
                    }
                } finally {
                    g2.dispose();
                }
            }
        };
        holder.setOpaque(false);
        holder.add(Ui.scroll(list), BorderLayout.CENTER);
        right.add(holder, BorderLayout.CENTER);
        right.add(hint, BorderLayout.SOUTH);
        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.CENTER);
        return p;
    }

    private JPanel footer() {
        Ui.RoundButton cancel = (Ui.RoundButton) Ui.ghost("取消");
        cancel.addActionListener(e -> dispose());
        installBtn.addActionListener(e -> install());
        installBtn.setEnabled(false);

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(Ui.muted("Forge / NeoForge 走官方安装步骤，在后台进行；OptiFine 属于模组，在「版本操作」里单独装"),
                BorderLayout.WEST);
        p.add(Ui.row(8, cancel, installBtn), BorderLayout.EAST);
        return p;
    }

    private static JLabel section(String text) {
        JLabel l = Ui.label(text, Font.BOLD, 12.5f, Ui.TEXT);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(0, 2, 8, 0));
        return l;
    }

    // ---------------- 数据 ----------------

    /** 一个可安装的版本。 */
    private static final class Item {
        final String version;
        final String tag;
        final Color tagColor;

        Item(String version, String tag, Color tagColor) {
            this.version = version;
            this.tag = tag;
            this.tagColor = tagColor;
        }
    }

    private void select(KindInfo info) {
        kind = info.id;
        for (Card c : cards) c.setPicked(c.info == info);

        List<Item> cached = cache.get(kind);
        if (cached != null) {
            fill(cached);
            return;
        }

        model.clear();
        installBtn.setEnabled(false);
        placeholder = "正在查询 " + gameVersion + "\n可用的 " + kind + " 版本…";
        hint.setText("正在查询 " + gameVersion + " 可用的 " + kind + " 版本…");
        repaint();

        final String asked = kind;
        final int my = ++token;
        win.pool().submit(() -> {
            List<Item> items = null;
            String error = null;
            try {
                items = query(asked, gameVersion);
            } catch (Exception ex) {
                error = brief(ex);
            }
            final List<Item> got = items;
            final String err = error;
            SwingUtilities.invokeLater(() -> {
                if (my != token) return;   // 期间又点了别的加载器，这批结果作废
                if (err != null) {
                    model.clear();
                    installBtn.setEnabled(false);
                    placeholder = "没能拿到 " + asked + " 版本列表\n换个加载器，或换一个正式版试试";
                    hint.setText("查询 " + asked + " 失败：" + err);
                    repaint();
                } else {
                    cache.put(asked, got);
                    fill(got);
                }
            });
        });
    }

    private void fill(List<Item> items) {
        model.clear();
        for (Item it : items) model.addElement(it);
        list.setSelectedIndex(items.isEmpty() ? -1 : 0);
        installBtn.setEnabled(!items.isEmpty());
        hint.setText(gameVersion + " 共 " + items.size() + " 个 " + kind + " 版本，双击可直接安装");
    }

    private void install() {
        Item it = list.getSelectedValue();
        if (it == null) return;
        String k = kind;
        String v = it.version;
        dispose();
        win.installLoader(k, gameVersion, v, targetInstance);
    }

    /** 拉某个加载器对某个游戏版本的候选版本（后台线程调用）。 */
    private static List<Item> query(String kind, String gameVersion) throws IOException {
        List<Item> out = new ArrayList<Item>();
        Color recommend = new Color(0x2E9E63);
        Color unstable = new Color(0xD98324);

        if ("Fabric".equals(kind) || "Quilt".equals(kind)) {
            boolean quilt = "Quilt".equals(kind);
            int i = 0;
            for (FabricInstaller.Loader l : FabricInstaller.loaders(gameVersion, quilt)) {
                if (!l.stable) {
                    out.add(new Item(l.version, "不稳定", unstable));
                } else if (i == 0) {
                    out.add(new Item(l.version, "最新", Ui.accent()));
                } else {
                    out.add(new Item(l.version, "", Ui.MUTED));
                }
                i++;
            }
        } else {
            boolean neo = "NeoForge".equals(kind);
            List<ForgeInstaller.Loader> all = ForgeInstaller.loaders(gameVersion, neo);
            int limit = Math.min(all.size(), 200);
            for (int i = 0; i < limit; i++) {
                ForgeInstaller.Loader l = all.get(i);
                if (l.recommended) {
                    out.add(new Item(l.version, "推荐", recommend));
                } else if (i == 0) {
                    out.add(new Item(l.version, "最新", Ui.accent()));
                } else {
                    out.add(new Item(l.version, "", Ui.MUTED));
                }
            }
        }
        if (out.isEmpty()) throw new IOException("没有可用版本");
        return out;
    }

    private static String brief(Throwable e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int nl = msg.indexOf('\n');
        if (nl > 0) msg = msg.substring(0, nl);
        return msg.length() > 70 ? msg.substring(0, 70) + "…" : msg;
    }

    // ---------------- 图标 ----------------

    private static java.awt.image.BufferedImage logoOf(KindInfo info, int px) {
        String key = info.id + "@" + px;
        synchronized (LOGOS) {
            java.awt.image.BufferedImage hit = LOGOS.get(key);
            if (hit != null) return hit;
            java.awt.image.BufferedImage raw = info.logo == null ? null : readResource(info.logo);
            if (raw == null) raw = letterTile(info.id.substring(0, 2).toUpperCase(java.util.Locale.ROOT),
                    info.tint, px * 3);
            java.awt.image.BufferedImage made = Icons.round(raw, px);
            LOGOS.put(key, made);
            return made;
        }
    }

    private static java.awt.image.BufferedImage readResource(String path) {
        try (InputStream in = LoaderDialog.class.getResourceAsStream(path)) {
            if (in != null) return ImageIO.read(in);
        } catch (Exception e) {
            Log.info("加载器 logo 读取失败，改用字母块：" + path + " — " + e.getMessage());
        }
        return null;
    }

    /** logo 缺失时的兜底：品牌色圆角块 + 两个白字母。 */
    private static java.awt.image.BufferedImage letterTile(String text, Color tint, int px) {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(px, px, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(tint);
            g.fillRoundRect(0, 0, px, px, px / 3, px / 3);
            g.setColor(Color.WHITE);
            g.setFont(Ui.font(Font.BOLD, px * 0.42f));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (px - fm.stringWidth(text)) / 2,
                    (px - fm.getHeight()) / 2 + fm.getAscent());
        } finally {
            g.dispose();
        }
        return img;
    }

    // ---------------- 组件 ----------------

    /** 左边一张加载器卡片。 */
    private final class Card extends JPanel {
        private final KindInfo info;
        private boolean picked;
        private boolean hover;

        Card(KindInfo info) {
            this.info = info;
            setOpaque(false);
            setLayout(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
            setPreferredSize(new Dimension(240, 58));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel icon = new JLabel(new ImageIcon(logoOf(info, ICON_PX)));
            icon.setPreferredSize(new Dimension(ICON_PX, ICON_PX));
            add(icon, BorderLayout.WEST);

            JLabel name = Ui.label(info.id, Font.BOLD, 13.5f, Ui.TEXT);
            JLabel desc = Ui.label(info.desc, Font.PLAIN, 11f, Ui.MUTED);
            add(Ui.col(2, name, desc), BorderLayout.CENTER);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    select(Card.this.info);
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

        void setPicked(boolean b) {
            picked = b;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (picked) {
                Ui.paintRowSelection(g, this, 0.90f);
            } else if (hover) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(Ui.blend(Ui.accent(), Color.WHITE, 0.95f));
                    g2.fillRoundRect(0, 2, getWidth(), getHeight() - 4, 12, 12);
                } finally {
                    g2.dispose();
                }
            }
        }
    }

    /** 右边版本列表的一行。 */
    private final class VersionRenderer extends JPanel implements ListCellRenderer<Item> {
        private final JLabel name = Ui.label("", Font.BOLD, 13f, Ui.TEXT);
        private final JLabel tag = Ui.label("", Font.PLAIN, 11f, Ui.MUTED);
        private boolean sel;

        VersionRenderer() {
            setOpaque(false);
            setLayout(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            add(name, BorderLayout.WEST);
            tag.setHorizontalAlignment(JLabel.RIGHT);
            add(tag, BorderLayout.EAST);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (sel) Ui.paintRowSelection(g, this, 0.90f);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Item> l, Item it, int index,
                                                     boolean selected, boolean focus) {
            sel = selected;
            name.setText(it.version);
            tag.setText(it.tag);
            tag.setForeground(it.tagColor);
            return this;
        }
    }
}
