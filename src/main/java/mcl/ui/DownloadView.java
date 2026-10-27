package mcl.ui;

import mcl.meta.VersionManifest;
import mcl.net.Source;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 下载页：从 Mojang / BMCLAPI 拉版本清单并安装。 */
public class DownloadView extends JPanel {
    private final MainWindow win;
    private final DefaultListModel<VersionManifest.Entry> model = new DefaultListModel<>();
    private final JList<VersionManifest.Entry> list = new JList<>(model);
    private final JTextField search = new JTextField();
    private final Ui.Chips typeFilter = new Ui.Chips("全部", "正式版", "快照", "远古版本");
    private final JComboBox<String> sourceBox = new JComboBox<>();
    private final JLabel hint = Ui.muted("正在准备版本清单…");
    private final Ui.RoundButton installBtn = (Ui.RoundButton) Ui.primary("安装选中版本");
    private final Set<String> installed = new HashSet<>();
    private VersionManifest manifest;
    private boolean loading;
    private boolean loaded;

    public DownloadView(MainWindow win) {
        this.win = win;
        setOpaque(false);
        setLayout(new BorderLayout(0, 12));

        for (Source s : Source.all()) sourceBox.addItem(s.displayName);
        sourceBox.setSelectedIndex(Math.max(0, indexOfSource(win.settings().downloadSource)));
        sourceBox.setPreferredSize(new Dimension(150, 32));
        sourceBox.addActionListener(e -> {
            int i = sourceBox.getSelectedIndex();
            if (i < 0) return;
            win.settings().downloadSource = Source.all()[i].name();
            win.settings().save();
            reload();
        });

        Ui.RoundButton refresh = (Ui.RoundButton) Ui.ghost("刷新");
        refresh.addActionListener(e -> reload());
        installBtn.addActionListener(e -> installSelected());

        JPanel header = Ui.transparent();
        header.setLayout(new BorderLayout());
        header.add(Ui.col(4, Ui.title("下载游戏"),
                Ui.subtitle("版本元数据与资源文件均支持官方源与 BMCLAPI 镜像，失败自动回退")), BorderLayout.WEST);
        header.add(Ui.row(8, sourceBox, refresh), BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        Ui.RoundedPanel card = (Ui.RoundedPanel) Ui.card();
        card.setLayout(new BorderLayout(0, 10));

        typeFilter.addActionListener(e -> applyFilter());
        search.setPreferredSize(new Dimension(220, 32));
        search.putClientProperty("JTextField.placeholderText", "搜索版本号，例如 1.20.1");
        search.addCaretListener(e -> applyFilter());
        JPanel tools = Ui.row(8, typeFilter, search, Box.createHorizontalGlue(), hint);
        card.add(tools, BorderLayout.NORTH);

        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(54);
        list.setCellRenderer(new Renderer());
        card.add(Ui.scroll(list), BorderLayout.CENTER);

        JPanel footer = Ui.transparent();
        footer.setLayout(new BorderLayout());
        footer.add(Ui.muted("提示：装加载器会先补装原版；Forge/NeoForge 需执行官方安装步骤，OptiFine 会调用官方安装器"),
                BorderLayout.WEST);
        Ui.RoundButton loaderBtn = (Ui.RoundButton) Ui.ghost("装模组加载器");
        loaderBtn.addActionListener(e -> installLoader());
        Ui.RoundButton importBtn = (Ui.RoundButton) Ui.ghost("导入整合包");
        importBtn.addActionListener(e -> win.importModpackDialog());
        footer.add(Ui.row(8, importBtn, loaderBtn, installBtn), BorderLayout.EAST);
        card.add(footer, BorderLayout.SOUTH);
        add(card, BorderLayout.CENTER);
    }

    /** 打开加载器选择框：左边一排加载器卡片，右边是该加载器的版本列表。 */
    private void installLoader() {
        VersionManifest.Entry e = list.getSelectedValue();
        if (e == null) {
            JOptionPane.showMessageDialog(this, "请先在列表里选择一个原版版本");
            return;
        }
        LoaderDialog.open(win, e.id);
    }

    private int indexOfSource(String name) {
        Source s = Source.of(name);
        Source[] all = Source.all();
        for (int i = 0; i < all.length; i++) if (all[i] == s) return i;
        return 0;
    }

    public void ensureLoaded() {
        if (!loaded && !loading) reload();
        refreshInstalled();
    }

    public void reload() {
        if (loading) return;
        loading = true;
        hint.setText("正在获取版本清单…");
        installBtn.setEnabled(false);
        Source src = Source.of(win.settings().downloadSource);
        win.pool().submit(() -> {
            try {
                VersionManifest m = VersionManifest.fetch(src);
                SwingUtilities.invokeLater(() -> {
                    manifest = m;
                    loaded = true;
                    loading = false;
                    hint.setText("共 " + m.versions.size() + " 个版本，最新正式版 " + m.latestRelease);
                    refreshInstalled();
                    applyFilter();
                    installBtn.setEnabled(true);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    loading = false;
                    hint.setText("获取失败：" + e.getMessage());
                    JOptionPane.showMessageDialog(this, "无法获取版本清单：\n" + e.getMessage()
                            + "\n\n可以尝试切换下载源后重试。", "网络错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    private void refreshInstalled() {
        installed.clear();
        for (mcl.game.VersionManager.InstalledVersion v : win.game().versions().list()) installed.add(v.id);
    }

    public void markInstalled(String id) {
        installed.add(id);
        list.repaint();
    }

    /** 版本类型的中文名，未知类型原样返回。 */
    private static String typeLabel(String t) {
        String s = t == null ? "" : t;
        if ("release".equals(s)) return "正式版";
        if ("snapshot".equals(s)) return "快照";
        if ("old_beta".equals(s)) return "远古 Beta";
        if ("old_alpha".equals(s)) return "远古 Alpha";
        return s;
    }

    private void applyFilter() {
        if (manifest == null) return;
        String kw = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        int type = typeFilter.getSelectedIndex();
        List<VersionManifest.Entry> filtered = new ArrayList<>();
        for (VersionManifest.Entry e : manifest.versions) {
            if (!kw.isEmpty() && !e.id.toLowerCase().contains(kw)) continue;
            boolean ok;
            if (type == 1) {
                ok = "release".equals(e.type);
            } else if (type == 2) {
                ok = "snapshot".equals(e.type);
            } else if (type == 3) {
                ok = e.type != null && e.type.startsWith("old");
            } else {
                ok = true;
            }
            if (!ok) continue;
            filtered.add(e);
        }
        model.clear();
        for (VersionManifest.Entry e : filtered) model.addElement(e);
        if (!filtered.isEmpty()) list.setSelectedIndex(0);
    }

    private void installSelected() {
        VersionManifest.Entry e = list.getSelectedValue();
        if (e == null) {
            JOptionPane.showMessageDialog(this, "请先选择一个版本");
            return;
        }
        win.installVersion(e.id);
    }

    private class Renderer extends JPanel implements ListCellRenderer<VersionManifest.Entry> {
        private final JLabel icon = new JLabel("", JLabel.CENTER);
        private final JLabel name = Ui.label("", Font.BOLD, 14f, Ui.TEXT);
        private final JLabel sub = Ui.label("", Font.PLAIN, 11.5f, Ui.MUTED);
        private final JLabel badge = Ui.label("", Font.PLAIN, 11f, Ui.MUTED);
        private boolean sel;

        Renderer() {
            setOpaque(false);
            setLayout(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
            icon.setPreferredSize(new Dimension(36, 36));
            // 图标是画出来的方块，自带底色，不需要再垫色块
            icon.setOpaque(false);
            add(icon, BorderLayout.WEST);
            add(Ui.col(3, name, sub), BorderLayout.CENTER);
            badge.setPreferredSize(new Dimension(80, 20));
            badge.setHorizontalAlignment(JLabel.RIGHT);
            add(badge, BorderLayout.EAST);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (sel) Ui.paintRowSelection(g, this, 0.90f);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends VersionManifest.Entry> l,
                                                      VersionManifest.Entry e, int index,
                                                      boolean selected, boolean focus) {
            sel = selected;
            String type = typeLabel(e.type);
            icon.setIcon(new javax.swing.ImageIcon(BlockIcon.get(e.type, 36)));
            name.setText(e.id);
            String date = e.releaseTime.length() >= 10 ? e.releaseTime.substring(0, 10) : "";
            sub.setText(type + "  ·  " + date);
            boolean done = installed.contains(e.id);
            badge.setText(done ? "已安装" : "");
            badge.setForeground(new Color(0x2E9E63));
            return this;
        }
    }
}
