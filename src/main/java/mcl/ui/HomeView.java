package mcl.ui;

import mcl.util.Compat;

import mcl.game.VersionManager;
import mcl.install.Installer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.List;

/** 主页：已安装版本列表 + 启动。 */
public class HomeView extends JPanel {
    private final MainWindow win;
    private final DefaultListModel<VersionManager.InstalledVersion> model = new DefaultListModel<>();
    private final JList<VersionManager.InstalledVersion> list = new JList<>(model);
    private final JLabel emptyHint = Ui.label("还没有安装任何版本", Font.PLAIN, 13f, Ui.MUTED);
    private final JLabel detailTitle = Ui.label("", Font.BOLD, 15f, Ui.TEXT);
    private final JLabel detailSub = Ui.label("", Font.PLAIN, 12f, Ui.MUTED);
    /** 同一版本装了多个加载器时，这里给出「原版 / Fabric / Forge」的切换条。 */
    private final JPanel profileBox = Ui.row(0);
    private final JTextField search = new JTextField();
    private final JMenuItem miRename = new JMenuItem("重命名版本");
    private final JMenuItem miCopy = new JMenuItem("复制版本");
    private final JMenuItem miExport = new JMenuItem("导出为整合包");
    private final JMenuItem miRenameLoader = new JMenuItem("安装 / 更换模组加载器");
    private final JMenuItem miRemoveLoader = new JMenuItem("卸载模组加载器");
    private final JMenuItem miOptiFine = new JMenuItem("安装 OptiFine");
    private final JMenuItem miDelete = new JMenuItem("删除版本");

    public HomeView(MainWindow win) {
        this.win = win;
        setOpaque(false);
        setLayout(new BorderLayout(0, 12));

        JLabel title = Ui.title("版本列表");
        search.setPreferredSize(new Dimension(200, 32));
        search.putClientProperty("JTextField.placeholderText", "搜索已安装版本");
        search.addCaretListener(e -> refresh());
        Ui.RoundButton refresh = (Ui.RoundButton) Ui.ghost("刷新");
        refresh.addActionListener(e -> win.refreshAll());
        Ui.RoundButton install = (Ui.RoundButton) Ui.primary("安装新版本");
        install.addActionListener(e -> win.showPage(1));

        JPanel header = Ui.transparent();
        header.setLayout(new BorderLayout());
        JPanel right = Ui.row(8, search, refresh, install);
        header.add(Ui.col(4, title, Ui.subtitle("选择版本并启动游戏，或前往下载页安装新版本")), BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        Ui.RoundedPanel card = (Ui.RoundedPanel) Ui.card();
        card.setLayout(new BorderLayout(0, 8));

        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.setFixedCellHeight(58);
        list.addListSelectionListener(e -> updateDetail());
        JScrollPane sp = Ui.scroll(list);
        sp.setOpaque(false);
        card.add(sp, BorderLayout.CENTER);

        emptyHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(emptyHint, BorderLayout.SOUTH);
        add(card, BorderLayout.CENTER);

        // 底部详情 + 操作
        Ui.RoundedPanel footer = (Ui.RoundedPanel) Ui.card();
        footer.setLayout(new BorderLayout(12, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
        footer.add(Ui.col(3, detailTitle, detailSub, profileBox), BorderLayout.CENTER);

        Ui.RoundButton launch = (Ui.RoundButton) Ui.primary("启动游戏");
        launch.addActionListener(e -> win.launchSelected());
        Ui.RoundButton folder = (Ui.RoundButton) Ui.ghost("打开目录");
        folder.addActionListener(e -> win.openGameDir());
        Ui.RoundButton more = (Ui.RoundButton) Ui.ghost("版本操作");
        JPopupMenu menu = new JPopupMenu();
        miRename.addActionListener(e -> win.renameVersionDialog());
        miCopy.addActionListener(e -> win.copyVersionDialog());
        miExport.addActionListener(e -> win.exportModpackDialog());
        // 加载器这块以前只有下载页左下角一个入口，装了之后想换或想卸都得自己猜。
        // 现在挂在版本操作里，选中哪个版本就作用于哪个版本。
        miRenameLoader.addActionListener(e -> installLoaderSelected());
        miRemoveLoader.addActionListener(e -> removeLoaderSelected());
        // OptiFine 是模组不是加载器，单独一项：装在当前版本上，跟已有加载器的模组共存
        miOptiFine.addActionListener(e -> installOptiFineSelected());
        miDelete.addActionListener(e -> deleteSelected());
        menu.add(miRename);
        menu.add(miCopy);
        menu.add(miExport);
        menu.addSeparator();
        menu.add(miRenameLoader);
        menu.add(miRemoveLoader);
        menu.addSeparator();
        menu.add(miOptiFine);
        menu.addSeparator();
        menu.add(miDelete);
        // 菜单是复用的，每次弹出前按当前选中项改可用状态（有没有版本、是不是加载器版本）
        more.addActionListener(e -> {
            updateVersionMenu();
            menu.show(more, 0, more.getHeight());
        });
        footer.add(Ui.row(8, folder, more, launch), BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    public void refresh() {
        String filter = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        VersionManager.InstalledVersion selected = list.getSelectedValue();
        List<VersionManager.InstalledVersion> all = win.game().versions().list();
        SwingUtilities.invokeLater(() -> {
            model.clear();
            for (VersionManager.InstalledVersion v : all) {
                if (filter.isEmpty() || v.id.toLowerCase().contains(filter)) model.addElement(v);
            }
            emptyHint.setVisible(model.isEmpty());
            String preferred = win.settings().profile() == null
                    ? null : win.settings().profile().selectedVersion;
            int target = -1;
            if (selected != null) {
                for (int i = 0; i < model.size(); i++) {
                    if (model.get(i).id.equals(selected.id)) {
                        target = i;
                        break;
                    }
                }
            }
            if (target < 0 && preferred != null) {
                for (int i = 0; i < model.size(); i++) {
                    if (model.get(i).id.equals(preferred)) {
                        target = i;
                        break;
                    }
                }
            }
            if (target < 0 && model.size() > 0) target = 0;
            if (target >= 0) list.setSelectedIndex(target);
            if (model.isEmpty()) {
                detailTitle.setText("暂无已安装版本");
                detailSub.setText("点击右上角「安装新版本」开始下载");
            }
            revalidate();
            repaint();
        });
    }

    /** 版本类型的中文名。 */
    private static String versionTypeLabel(String t) {
        String s = t == null ? "" : t;
        if ("release".equals(s)) return "正式版";
        if ("snapshot".equals(s)) return "快照";
        if ("old_beta".equals(s)) return "远古 Beta";
        if ("old_alpha".equals(s)) return "远古 Alpha";
        return s;
    }

    private void updateDetail() {
        VersionManager.InstalledVersion v = list.getSelectedValue();
        if (v == null) return;
        detailTitle.setText(v.id);
        String type = versionTypeLabel(v.type);
        String date = v.releaseTime.length() >= 10 ? v.releaseTime.substring(0, 10) : v.releaseTime;
        // 把「这个版本带的什么加载器」写进副标题 —— 以前只能从图标颜色猜
        VersionManager.Info info = win.game().versions().describe(v.id);
        StringBuilder sub = new StringBuilder(type);
        if (date != null && !date.isEmpty()) sub.append("  ·  ").append(date);
        if (!"vanilla".equals(info.loaderKind)) {
            sub.append("  ·  ").append(info.loaderLabel());
            if (!Compat.isBlank(info.loaderVersion)) sub.append(' ').append(info.loaderVersion);
        }
        sub.append("  ·  ").append(v.hasJar ? "客户端已就绪" : "缺少客户端 jar");
        // 隔离后每个版本有自己的 mods / saves，用户需要知道当前这个是不是独立的
        Path run = win.game().runDir(v.id);
        boolean isolated = run.equals(win.game().gameDir().resolve("versions").resolve(v.id));
        sub.append("  ·  ").append(isolated ? "独立目录" : "共用目录");
        detailSub.setText(sub.toString());
        updateProfileBar(v);
        win.selectVersion(v.id);
    }

    /**
     * 同一版本装了不止一个加载器时，给一条切换栏。
     *
     * <p>没有这条栏的话，用户装了 Forge 又想玩纯原版，只能去翻文件夹删东西 ——
     * 而这里点一下就是换一份配置，文件层面完全不动。
     */
    private void updateProfileBar(VersionManager.InstalledVersion v) {
        profileBox.removeAll();
        Path vdir = win.game().gameDir().resolve("versions").resolve(v.id);
        List<String> names = mcl.game.VersionProfiles.names(vdir);
        if (names.size() < 2) {
            profileBox.setVisible(false);
            profileBox.revalidate();
            return;
        }
        String[] labels = new String[names.size()];
        for (int i = 0; i < names.size(); i++) {
            labels[i] = mcl.game.VersionProfiles.label(names.get(i));
        }
        Ui.Chips chips = new Ui.Chips(labels);
        int activeIndex = names.indexOf(mcl.game.VersionProfiles.active(vdir));
        if (activeIndex > 0) chips.setSelectedIndex(activeIndex);
        chips.setAlignmentY(Component.CENTER_ALIGNMENT);
        chips.addActionListener(e -> {
            String picked = names.get(chips.getSelectedIndex());
            try {
                mcl.game.VersionProfiles.setActive(vdir, v.id, picked);
                win.refreshAll();
                win.setStatus("启动方式已切到 " + mcl.game.VersionProfiles.label(picked));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "切换失败：" + ex.getMessage(),
                        "切换启动方式", JOptionPane.ERROR_MESSAGE);
            }
        });
        profileBox.add(chips);
        profileBox.setVisible(true);
        profileBox.revalidate();
        profileBox.repaint();
    }

    /** 弹出「版本操作」前，按当前选中项刷新各项可用状态。 */
    private void updateVersionMenu() {
        VersionManager.InstalledVersion v = list.getSelectedValue();
        boolean has = v != null;
        miRename.setEnabled(has);
        miCopy.setEnabled(has);
        miExport.setEnabled(has);
        miDelete.setEnabled(has);
        miRenameLoader.setEnabled(has);
        miOptiFine.setEnabled(has);
        // 只有「自己就是加载器版本」才给卸载；整合包只是继承了加载器，
        // 对它显示卸载会把整个整合包删掉，所以那种情况只留「删除版本」。
        boolean selfIsLoader = false;
        boolean hasLoader = false;
        boolean hasProfileLoader = false;
        if (has) {
            selfIsLoader = !"vanilla".equals(VersionManager.idInfo(v.id).loaderKind);
            Path vdir = win.game().gameDir().resolve("versions").resolve(v.id);
            for (String n : mcl.game.VersionProfiles.names(vdir)) {
                if (!mcl.game.VersionProfiles.VANILLA.equals(n)) {
                    hasProfileLoader = true;
                    break;
                }
            }
            // 整合包认不出 id，但它的 mcl 元信息里有 —— 那也该叫「更换」
            hasLoader = selfIsLoader || hasProfileLoader
                    || !"vanilla".equals(win.game().versions().describe(v.id).loaderKind);
            miRenameLoader.setText(hasLoader ? "更换模组加载器" : "安装模组加载器");
            // 有加载器就装成 mod；原版没法加载模组，只能另开一个带 OptiFine 的版本
            miOptiFine.setText(hasLoader ? "安装 OptiFine（模组）" : "安装 OptiFine（独立版本）");
        } else {
            miRenameLoader.setText("安装模组加载器");
            miOptiFine.setText("安装 OptiFine");
        }
        // 整合包只继承加载器、自己不带那份配置，对它来说「卸载」等于拆了整合包
        miRemoveLoader.setEnabled(has && (selfIsLoader || hasProfileLoader));
    }

    /** 在当前版本的底层原版上装一个加载器（或换一个）。 */
    private void installLoaderSelected() {
        VersionManager.InstalledVersion v = list.getSelectedValue();
        if (v == null) return;
        boolean isPack = java.nio.file.Files.isRegularFile(
                win.game().gameDir().resolve("versions").resolve(v.id).resolve(".mcl-isolate"));
        if (isPack) {
            VersionManager.Info info = win.game().versions().describe(v.id);
            String base = Compat.isBlank(info.mcVersion) ? v.id : info.mcVersion;
            // 整合包：换加载器要换进它自己的目录，而不是再装一个共享版本
            LoaderDialog.open(win, base, v.id);
            return;
        }
        // 普通版本：加载器会并进这个版本自己的目录（.mcl-profiles），
        // 版本列表里不会多出 fabric-loader-x-y 这种东西
        VersionManager.Info self = VersionManager.idInfo(v.id);
        String base = Compat.isBlank(self.mcVersion) ? v.id : self.mcVersion;
        LoaderDialog.open(win, base, null);
    }

    /**
     * 给当前选中的版本加 OptiFine。
     *
     * <p>OptiFine 是模组，不是加载器 —— 它那个 jar 里带 {@code META-INF/mods.toml}
     * （{@code modId="optifine"}），Forge / NeoForge 会当普通 mod 加载，Fabric / Quilt
     * 配 OptiFabric 也能用。所以它跟「安装模组加载器」是两个独立入口：
     * 装加载器是换底座，装 OptiFine 是在已有底座上加东西。
     */
    private void installOptiFineSelected() {
        VersionManager.InstalledVersion v = list.getSelectedValue();
        if (v == null) return;
        OptiFineDialog.open(win, v.id);
    }

    /**
     * 卸载加载器。
     *
     * <p>加载器现在是版本目录里的一份配置（{@code .mcl-profiles}），
     * 所以卸载就是删掉这些配置、把生效的那份切回原版 —— 版本本身、库、mods、存档都不动。
     * 老版本留下的「独立加载器版本」还是走删目录那条路。
     */
    private void removeLoaderSelected() {
        VersionManager.InstalledVersion v = list.getSelectedValue();
        if (v == null) return;
        Path vdir = win.game().gameDir().resolve("versions").resolve(v.id);
        List<String> profiles = mcl.game.VersionProfiles.names(vdir);
        StringBuilder names = new StringBuilder();
        for (String p : profiles) {
            if (mcl.game.VersionProfiles.VANILLA.equals(p)) continue;
            if (names.length() > 0) names.append('、');
            names.append(mcl.game.VersionProfiles.label(p));
        }
        if (names.length() > 0) {
            String msg = "卸载 " + names + "？\n\n"
                    + "只删掉这些启动配置，" + v.id + " 这个版本本身、已经下好的库、\n"
                    + "mods 和存档都保留；卸载后它回到纯原版。";
            if (JOptionPane.showConfirmDialog(this, msg, "卸载模组加载器",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            int n = mcl.game.VersionProfiles.removeAllLoaders(vdir, v.id);
            win.refreshAll();
            win.setStatus(n > 0 ? "已卸载 " + names + "，" + v.id + " 回到原版" : "本来就没有装加载器");
            return;
        }
        // 用 idInfo：确保「卸载」作用的对象本身就是一个加载器版本（而不是整合包实例）
        VersionManager.Info info = VersionManager.idInfo(v.id);
        if ("vanilla".equals(info.loaderKind)) return;
        String label = info.loaderLabel()
                + (Compat.isBlank(info.loaderVersion) ? "" : " " + info.loaderVersion);
        String base = Compat.isBlank(info.mcVersion) ? "" : info.mcVersion;
        // 整合包这类版本会 inheritsFrom 到加载器版本上，卸掉它等于把别人也弄瘸
        List<String> dependents = dependentsOf(v.id);
        StringBuilder msg = new StringBuilder();
        msg.append("卸载 ").append(label).append("？\n\n")
                .append("只会删除版本 ").append(v.id).append(" 本身；\n")
                .append(base.isEmpty() ? "" : "原版 " + base + " 仍然保留，")
                .append("已下载的库、存档与资源文件都不会动。");
        if (!dependents.isEmpty()) {
            msg.append("\n\n⚠ 以下版本依赖它，卸载后将无法启动：\n");
            for (String d : dependents) msg.append("  · ").append(d).append('\n');
        }
        if (JOptionPane.showConfirmDialog(this, msg.toString(), "卸载模组加载器",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            Installer.delete(win.game().gameDir(), v.id);
            if (!base.isEmpty() && win.game().versions().isInstalled(base)) win.selectVersion(base);
            win.refreshAll();
            win.setStatus("已卸载 " + label + "（版本 " + v.id + "）");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "卸载失败：" + e.getMessage(),
                    "卸载模组加载器", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 顺着 inheritsFrom 往上找，列出还压在这个版本上的其它已装版本。 */
    private List<String> dependentsOf(String id) {
        List<String> out = new java.util.ArrayList<>();
        VersionManager vm = win.game().versions();
        for (VersionManager.InstalledVersion v : vm.list()) {
            if (v.id.equals(id)) continue;
            String cur = v.id;
            for (int depth = 0; depth < 8 && cur != null; depth++) {
                // 走 versionJson：整合包把加载器收进了自己目录，直接拼 versions/<id>/<id>.json 会断链
                Path json = vm.versionJson(cur);
                if (json == null) break;
                String parent;
                try {
                    parent = mcl.util.Json.str(com.google.gson.JsonParser
                            .parseString(Compat.readString(json)).getAsJsonObject(),
                            "inheritsFrom", null);
                } catch (Exception e) {
                    break;
                }
                if (id.equals(parent)) {
                    out.add(v.id);
                    break;
                }
                cur = parent;
            }
        }
        return out;
    }

    private void deleteSelected() {
        VersionManager.InstalledVersion v = list.getSelectedValue();
        if (v == null) return;
        if (JOptionPane.showConfirmDialog(this, "确定删除版本 " + v.id + " 吗？\n（不会删除存档与资源文件）",
                "删除版本", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            Installer.delete(win.game().gameDir(), v.id);
            win.refreshAll();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "删除失败：" + e.getMessage());
        }
    }

    private class Renderer extends JPanel implements ListCellRenderer<VersionManager.InstalledVersion> {
        private final JLabel icon = new JLabel("", JLabel.CENTER);
        private final JLabel name = Ui.label("", Font.BOLD, 14f, Ui.TEXT);
        private final JLabel sub = Ui.label("", Font.PLAIN, 11.5f, Ui.MUTED);
        private boolean sel;

        Renderer() {
            setOpaque(false);
            setLayout(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            icon.setPreferredSize(new Dimension(38, 38));
            // 方块图标自带底色，不再垫色块
            icon.setOpaque(false);
            add(icon, BorderLayout.WEST);
            add(Ui.col(3, name, sub), BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (sel) Ui.paintRowSelection(g, this, 0.90f);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends VersionManager.InstalledVersion> l,
                                                      VersionManager.InstalledVersion v, int index,
                                                      boolean selected, boolean focus) {
            sel = selected;
            icon.setIcon(new javax.swing.ImageIcon(BlockIcon.forVersion(v.id, v.type, 38)));
            name.setText(v.id);
            String type = "release".equals(v.type) ? "正式版" : "snapshot".equals(v.type) ? "快照" : v.type;
            String date = v.releaseTime.length() >= 10 ? v.releaseTime.substring(0, 10) : "";
            sub.setText(type + "  ·  " + date + (v.hasJar ? "" : "  ·  jar 缺失"));
            return this;
        }
    }
}
