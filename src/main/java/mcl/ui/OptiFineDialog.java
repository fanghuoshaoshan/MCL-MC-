package mcl.ui;

import mcl.game.VersionManager;
import mcl.install.OptiFineInstaller;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 给某个已装版本加 OptiFine。
 *
 * <p>OptiFine 不是模组加载器 —— 它那个 jar 里带着 {@code META-INF/mods.toml}
 * （{@code modId="optifine"}），本身就是个 mod，能跟 Forge / NeoForge 的模组共存，
 * Fabric / Quilt 下配一个 OptiFabric 也能用。
 *
 * <p>所以这个框不跟加载器选择框混在一起：选了 Forge 就装不了 Fabric，
 * 但 OptiFine 是「在已有加载器上加一层」，两者不是同一类东西。
 */
public final class OptiFineDialog extends JDialog {

    /** 一个可安装的 OptiFine 版本。 */
    private static final class Item {
        final String edition;
        final String tag;
        final Color tagColor;

        Item(String edition, String tag, Color tagColor) {
            this.edition = edition;
            this.tag = tag;
            this.tagColor = tagColor;
        }
    }

    private final MainWindow win;
    private final String versionId;
    private final String mcVersion;
    private final String loaderKind;
    private final String loaderLabel;
    private final OptiFineInstaller.Shape shape;

    private final DefaultListModel<Item> model = new DefaultListModel<Item>();
    private final JList<Item> list = new JList<Item>(model);
    private final JLabel hint = Ui.muted(" ");
    private final Ui.RoundButton installBtn = (Ui.RoundButton) Ui.primary("安装");
    private String placeholder = "正在查询…";

    private OptiFineDialog(MainWindow win, String versionId) {
        super(SwingUtilities.getWindowAncestor(win), "安装 OptiFine", ModalityType.APPLICATION_MODAL);
        this.win = win;
        this.versionId = versionId;
        VersionManager.Info info = win.game().versions().describe(versionId);
        this.mcVersion = info.mcVersion == null || info.mcVersion.isEmpty() ? versionId : info.mcVersion;
        this.loaderKind = info.loaderKind;
        this.loaderLabel = info.loaderLabel();
        this.shape = OptiFineInstaller.shapeFor(loaderKind);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(Ui.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        root.add(header(), BorderLayout.NORTH);
        root.add(body(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);
        setSize(620, 480);
        setMinimumSize(new Dimension(560, 420));
        setLocationRelativeTo(win);
        getRootPane().setDefaultButton(installBtn);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        load();
    }

    /** 打开选择框；返回时用户已经确认或取消，安装由主窗口接管。 */
    public static void open(MainWindow win, String versionId) {
        if (versionId == null || versionId.trim().isEmpty()) return;
        new OptiFineDialog(win, versionId).setVisible(true);
    }

    // ---------------- 界面 ----------------

    private JPanel header() {
        return Ui.col(4,
                Ui.title("安装 OptiFine"),
                Ui.muted("给版本「" + versionId + "」加 OptiFine（游戏版本 " + mcVersion + "，"
                        + loaderLabel + "）"));
    }

    private JPanel body() {
        JPanel p = new JPanel(new BorderLayout(0, 10));

        Ui.RoundedPanel tip = (Ui.RoundedPanel) Ui.card();
        tip.setLayout(new BorderLayout());
        tip.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        // 文案可能有好几句（Fabric 要提 OptiFabric、NeoForge 要警告），用 HTML 限定宽度才会折行
        tip.add(Ui.label("<html><body style='width:520px'>" + shapeText() + "</body></html>",
                Font.PLAIN, 12f, Ui.MUTED), BorderLayout.CENTER);
        p.add(tip, BorderLayout.NORTH);

        Ui.RoundedPanel listCard = (Ui.RoundedPanel) Ui.card();
        listCard.setLayout(new BorderLayout(0, 8));
        listCard.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        listCard.add(Ui.label("OptiFine 版本", Font.BOLD, 12.5f, Ui.TEXT), BorderLayout.NORTH);

        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(44);
        list.setCellRenderer(new VersionRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) install();
            }
        });

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
                    int y = Math.max(24, getHeight() / 3) - (lines.length - 1) * fm.getHeight() / 2
                            + fm.getAscent();
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
        listCard.add(holder, BorderLayout.CENTER);
        listCard.add(hint, BorderLayout.SOUTH);
        p.add(listCard, BorderLayout.CENTER);
        return p;
    }

    /** 这个版本装了 OptiFine 之后会变成什么样 —— 用户的第一个疑问就是这个。 */
    private String shapeText() {
        if (shape == OptiFineInstaller.Shape.INSTALLER) {
            return "原版不支持模组，会另外生成一个带 OptiFine 的独立版本（"
                    + mcVersion + "-OptiFine_xxx），原版本身不动。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("会作为模组放进这个版本的 mods/ 目录，已有的 ")
                .append(loaderLabel).append(" 模组照常能用。");
        if (OptiFineInstaller.needsOptiFabric(loaderKind)) {
            sb.append("另外会装上 OptiFabric —— ").append(loaderLabel)
                    .append(" 下 OptiFine 要靠它加载（社区方案，不是官方支持）。");
        }
        if ("neoforge".equalsIgnoreCase(loaderKind)) {
            sb.append("⚠ OptiFine 官方只支持 Forge，在 NeoForge 上很可能启动失败；"
                    + "要光影建议改用 Sodium + Iris。仍然可以装，但请先备份存档。");
        }
        Path mods = win.game().modsDir(versionId);
        Path exists = OptiFineInstaller.existingMod(mods);
        if (exists != null && Files.isRegularFile(exists)) {
            sb.append("当前已有 ").append(exists.getFileName()).append("，再装会替换掉它。");
        }
        return sb.toString();
    }

    private JPanel footer() {
        Ui.RoundButton cancel = (Ui.RoundButton) Ui.ghost("取消");
        cancel.addActionListener(e -> dispose());
        installBtn.addActionListener(e -> install());
        installBtn.setEnabled(false);

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(Ui.muted(shape == OptiFineInstaller.Shape.MOD
                ? "下载的是官方 jar，直接放进 mods，不新增版本"
                : "会调用 OptiFine 官方安装器，过程中窗口可能无响应"), BorderLayout.WEST);
        p.add(Ui.row(8, cancel, installBtn), BorderLayout.EAST);
        return p;
    }

    // ---------------- 数据 ----------------

    private void load() {
        model.clear();
        installBtn.setEnabled(false);
        placeholder = "正在查询 " + mcVersion + "\n可用的 OptiFine 版本…";
        hint.setText("正在查询 " + mcVersion + " 可用的 OptiFine…");

        win.pool().submit(() -> {
            List<Item> items = null;
            String error = null;
            try {
                items = query(mcVersion);
            } catch (Exception ex) {
                error = brief(ex);
            }
            final List<Item> got = items;
            final String err = error;
            SwingUtilities.invokeLater(() -> {
                if (err != null) {
                    model.clear();
                    installBtn.setEnabled(false);
                    placeholder = "没能拿到 OptiFine 版本列表\n检查网络后再试，或换个游戏版本";
                    hint.setText("查询失败：" + err);
                    repaint();
                } else {
                    fill(got);
                }
            });
        });
    }

    private static List<Item> query(String mcVersion) throws IOException {
        List<Item> out = new ArrayList<Item>();
        List<OptiFineInstaller.Version> all = OptiFineInstaller.list(mcVersion);
        int i = 0;
        for (OptiFineInstaller.Version v : all) {
            // OptiFine 同一个游戏版本通常有 1~2 个 edition，第一个就是最新的
            out.add(new Item(v.edition, i == 0 ? "最新" : "", i == 0 ? Ui.accent() : Ui.MUTED));
            i++;
        }
        if (out.isEmpty()) throw new IOException("OptiFine 官方没发布 " + mcVersion + " 的版本");
        return out;
    }

    private void fill(List<Item> items) {
        model.clear();
        for (Item it : items) model.addElement(it);
        list.setSelectedIndex(items.isEmpty() ? -1 : 0);
        installBtn.setEnabled(!items.isEmpty());
        hint.setText(mcVersion + " 共 " + items.size() + " 个 OptiFine 版本，双击可直接安装");
    }

    private void install() {
        Item it = list.getSelectedValue();
        if (it == null) return;
        String v = it.edition;
        dispose();
        win.installOptiFineInto(versionId, v);
    }

    private static String brief(Throwable e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int nl = msg.indexOf('\n');
        if (nl > 0) msg = msg.substring(0, nl);
        return msg.length() > 70 ? msg.substring(0, 70) + "…" : msg;
    }

    /** 版本列表的一行。 */
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
            name.setText(it.edition);
            tag.setText(it.tag);
            tag.setForeground(it.tagColor);
            return this;
        }
    }
}
