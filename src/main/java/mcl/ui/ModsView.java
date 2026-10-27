package mcl.ui;

import mcl.util.Dirs;
import mcl.util.Compat;
import mcl.util.Log;

import mcl.game.VersionManager;
import mcl.mod.ModSource;
import mcl.mod.ModInspector;
import mcl.mod.Modrinth;
import mcl.mod.Mods;
import mcl.mod.ZhSearch;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 模组页：本地模组管理 + Modrinth 在线搜索下载（模组 / 整合包 / 资源包 / 光影）。 */
public class ModsView extends JPanel {
    private final MainWindow win;

    private final DefaultListModel<Mods.Entry> installedModel = new DefaultListModel<>();
    private final JList<Mods.Entry> installedList = new JList<>(installedModel);
    private final JLabel installedHint = Ui.muted("");
    private final Ui.RoundButton toggleBtn = (Ui.RoundButton) Ui.ghost("禁用");
    private final Ui.RoundButton deleteBtn = (Ui.RoundButton) Ui.ghost("删除");
    private final JTextField installedFilter = new JTextField();
    private final java.util.List<Mods.Entry> allInstalled = new java.util.ArrayList<>();

    private final DefaultListModel<Modrinth.Hit> resultModel = new DefaultListModel<>();
    private final JList<Modrinth.Hit> resultList = new JList<>(resultModel);
    private final JTextField query = new JTextField();
    private final JComboBox<String> typeBox = new JComboBox<>(new String[]{"模组", "整合包", "资源包", "光影"});
    /** 源的选项由 CurseForge 有没有可用 key 决定：没 key 时下拉里不该出现点了才报错的选项。 */
    private static String[] sourceLabels() {
        ModSource[] all = ModSource.available();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) out[i] = all[i].label;
        return out;
    }

    private final JComboBox<String> sourceBox = new JComboBox<>(sourceLabels());
    private final JComboBox<String> mcBox = new JComboBox<>();
    /** 游戏版本下拉里的「不限版本」占位项，搜索时会被换成空字符串。 */
    private static final String MC_ALL = "全部版本";
    private final JComboBox<String> loaderBox = new JComboBox<>(new String[]{"全部加载器", "Fabric", "Quilt", "Forge", "NeoForge"});
    private final JComboBox<String> sortBox = new JComboBox<>(new String[]{"相关度", "下载量", "最新发布", "最近更新"});
    private final JLabel resultHint = Ui.muted("输入中英文关键词后回车（中文也能搜：钠 / 机械动力 / 小地图）");
    private final Ui.RoundButton searchBtn = (Ui.RoundButton) Ui.primary("搜索");
    private final Ui.RoundButton downloadBtn = (Ui.RoundButton) Ui.primary("下载到当前版本");
    private final Ui.RoundButton moreBtn = (Ui.RoundButton) Ui.ghost("加载更多");

    private String versionId = "";
    private int offset;
    private boolean loading;
    private int totalHits;
    private boolean mcLoaded;
    /**
     * 代码里主动改「源」下拉框时置真，让它的 actionListener 只同步配置、不发搜索。
     * 见构造函数里 -Dmcl.source 的处理。
     */
    private boolean suppressAutoSearch;
    /** 已经显示过的 slug，翻页时用来去重（融合结果与后续分页会有重叠）。 */
    private final java.util.Set<String> seenSlugs = new java.util.HashSet<>();

    public ModsView(MainWindow win) {
        this.win = win;
        // 项目图标的磁盘缓存：<启动器>/cache/icons（目录由写入时自动创建，删了会自动重下）
        Icons.setCacheDir(win.settings().launcherDir().resolve("cache").resolve("icons"));
        setOpaque(false);
        setLayout(new BorderLayout(0, 12));

        Ui.RoundButton openDir = (Ui.RoundButton) Ui.ghost("打开模组目录");
        openDir.addActionListener(e -> openModsDir());
        Ui.RoundButton addLocal = (Ui.RoundButton) Ui.ghost("添加本地文件");
        addLocal.addActionListener(e -> addLocalFiles());
        Ui.RoundButton refresh = (Ui.RoundButton) Ui.ghost("刷新");
        refresh.addActionListener(e -> refresh());

        JPanel header = Ui.transparent();
        header.setLayout(new BorderLayout());
        header.add(Ui.col(4, Ui.title("模组与内容"),
                Ui.subtitle("本地模组启用/禁用，或从 Modrinth / CurseForge 在线搜索下载（含必需依赖自动补齐）")),
                BorderLayout.WEST);
        header.add(Ui.row(8, openDir, addLocal, refresh), BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setOpaque(false);
        tabs.setFont(Ui.font(Font.PLAIN, 13f));
        tabs.addTab("已安装模组", buildInstalledTab());
        tabs.addTab("在线下载", buildOnlineTab());
        add(tabs, BorderLayout.CENTER);

        // 一进页面就把游戏版本铺好：以前要等第一次搜索才加载，
        // 用户点开下拉只看到「全部版本」，会以为没有版本可选
        ensureMcVersions();

        // 开发 / 截图用：-Dmcl.query=钠 -Dmcl.source=curseforge 直接切到在线下载并搜一次
        String devSource = System.getProperty("mcl.source", "");
        if (!Compat.isBlank(devSource)) {
            ModSource[] all = ModSource.available();
            for (int i = 0; i < all.length; i++) {
                if (all[i].id.equalsIgnoreCase(devSource) || all[i].label.equalsIgnoreCase(devSource)) {
                    // 这里切源只为把下拉框拨到位，不该触发一次空查询
                    // （空查询会把 loading 占住，导致下面真正带关键词的搜索被丢弃）
                    suppressAutoSearch = true;
                    sourceBox.setSelectedIndex(i);
                    suppressAutoSearch = false;
                    break;
                }
            }
        }
        String devQuery = System.getProperty("mcl.query", "");
        // 中文关键词没法用 -Dmcl.query 传：JVM 解析 -D 时用的是平台编码，
        // shell 递过来的 UTF-8 字节会被解成乱码或直接丢掉。截图脚本改用 base64 传。
        String b64 = System.getProperty("mcl.query.b64", "");
        if (!Compat.isBlank(b64)) {
            try {
                devQuery = new String(java.util.Base64.getDecoder().decode(b64.trim()), "UTF-8");
            } catch (Exception e) {
                Log.warn("mcl.query.b64 解码失败，忽略：" + e.getMessage());
            }
        }
        if (devQuery != null && devQuery.length() > 0) {
            tabs.setSelectedIndex(1);
            query.setText(devQuery);
            SwingUtilities.invokeLater(() -> {
                try {
                    search(true);
                } catch (Exception ignored) {
                    // 截图环境网络不通时忽略
                }
            });
        }
    }

    private JPanel buildInstalledTab() {
        Ui.RoundedPanel card = (Ui.RoundedPanel) Ui.card();
        card.setLayout(new BorderLayout(0, 10));

        installedList.setOpaque(false);
        installedList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        installedList.setFixedCellHeight(52);
        installedList.setCellRenderer(new ModRenderer());
        installedList.addListSelectionListener(e -> updateInstalledButtons());
        card.add(Ui.scroll(installedList), BorderLayout.CENTER);

        // 顶部过滤条：模组多的时候（整合包动辄 200+）靠肉眼翻列表不现实
        installedFilter.setPreferredSize(new Dimension(220, 30));
        installedFilter.putClientProperty("JTextField.placeholderText", "过滤：名称 / 文件名");
        installedFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applyFilter();
            }
        });
        Ui.RoundButton enableAll = (Ui.RoundButton) Ui.ghost("全部启用");
        enableAll.addActionListener(e -> setAllEnabled(true));
        Ui.RoundButton disableAll = (Ui.RoundButton) Ui.ghost("全部禁用");
        disableAll.addActionListener(e -> setAllEnabled(false));
        card.add(Ui.row(8, installedFilter, enableAll, disableAll,
                javax.swing.Box.createHorizontalGlue()), BorderLayout.NORTH);

        JPanel footer = Ui.transparent();
        footer.setLayout(new BorderLayout());
        footer.add(installedHint, BorderLayout.WEST);
        toggleBtn.addActionListener(e -> toggleSelected());
        deleteBtn.addActionListener(e -> deleteSelected());
        Ui.RoundButton updateBtn = (Ui.RoundButton) Ui.ghost("检查更新");
        updateBtn.addActionListener(e -> checkUpdates());
        Ui.RoundButton conflictBtn = (Ui.RoundButton) Ui.ghost("检查冲突");
        conflictBtn.addActionListener(e -> checkConflicts());
        footer.add(Ui.row(8, conflictBtn, updateBtn, toggleBtn, deleteBtn), BorderLayout.EAST);
        card.add(footer, BorderLayout.SOUTH);

        JPanel wrap = Ui.transparent();
        wrap.setLayout(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(10, 2, 2, 2));
        wrap.add(card, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildOnlineTab() {
        Ui.RoundedPanel card = (Ui.RoundedPanel) Ui.card();
        card.setLayout(new BorderLayout(0, 10));

        query.setPreferredSize(new Dimension(190, 32));
        query.putClientProperty("JTextField.placeholderText", "名称或关键词，中英文都行，例如 钠 / sodium");
        query.addActionListener(e -> search(true));
        typeBox.setPreferredSize(new Dimension(96, 32));
        typeBox.addActionListener(e -> updateDownloadButtonText());
        sourceBox.setPreferredSize(new Dimension(118, 32));
        ModSource[] avail = ModSource.available();
        for (int i = 0; i < avail.length; i++) {
            if (avail[i].id.equalsIgnoreCase(win.settings().modSource)) sourceBox.setSelectedIndex(i);
        }
        sourceBox.addActionListener(e -> {
            win.settings().modSource = source().id;
            win.settings().save();
            // 切换源时把 loading 清掉再搜：旧源那次搜索的结果已经没有意义了，
            // 而 search() 开头有 `if (loading) return` 的防重入——不清的话，
            // 用户在搜索进行中切源，新源这边的搜索会被静默吞掉，界面还停在旧结果上
            if (!suppressAutoSearch) {
                loading = false;
                search(true);
            }
        });
        mcBox.setPreferredSize(new Dimension(120, 32));
        mcBox.setEditable(true);
        // 版本列表是异步拉的，先占一个「全部版本」，免得刚打开时下拉是空白
        mcBox.addItem(MC_ALL);
        loaderBox.setPreferredSize(new Dimension(120, 32));
        sortBox.setPreferredSize(new Dimension(110, 32));
        searchBtn.addActionListener(e -> search(true));

        // 一行放不下六个控件，拆成两行：关键词 + 源 + 类型 / 游戏版本 + 加载器 + 排序
        JPanel row1 = Ui.row(8, query, sourceBox, typeBox, searchBtn,
                javax.swing.Box.createHorizontalGlue());
        JPanel row2 = Ui.row(8, Ui.muted("游戏版本"), mcBox, Ui.muted("加载器"), loaderBox, sortBox,
                javax.swing.Box.createHorizontalGlue());
        card.add(Ui.col(8, row1, row2), BorderLayout.NORTH);

        resultList.setOpaque(false);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setFixedCellHeight(56);
        resultList.setCellRenderer(new HitRenderer());
        card.add(Ui.scroll(resultList), BorderLayout.CENTER);

        JPanel footer = Ui.transparent();
        footer.setLayout(new BorderLayout(10, 0));
        // 提示语放 CENTER 并允许压到 0 宽：文案长短随命中来源变化，放 WEST 会把右侧按钮挤到重叠
        resultHint.setMinimumSize(new Dimension(0, 0));
        footer.add(resultHint, BorderLayout.CENTER);
        downloadBtn.addActionListener(e -> downloadSelected());
        moreBtn.addActionListener(e -> search(false));
        moreBtn.setVisible(false);
        footer.add(Ui.row(8, moreBtn, downloadBtn), BorderLayout.EAST);
        card.add(footer, BorderLayout.SOUTH);

        JPanel wrap = Ui.transparent();
        wrap.setLayout(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(10, 2, 2, 2));
        wrap.add(card, BorderLayout.CENTER);
        return wrap;
    }

    // ---------------- 数据 ----------------

    /** 加载器在筛选下拉框里的下标。 */
    private static int loaderIndexOf(String kind) {
        if ("fabric".equals(kind)) return 1;
        if ("quilt".equals(kind)) return 2;
        if ("forge".equals(kind)) return 3;
        if ("neoforge".equals(kind)) return 4;
        return 0;
    }

    public void setVersion(String versionId) {
        this.versionId = versionId == null ? "" : versionId;
        refresh();
        if (!Compat.isBlank(versionId)) {
            VersionManager.Info info = win.game().versions().describe(versionId);
            if (!Compat.isBlank(info.mcVersion)) {
                mcBox.setSelectedItem(info.mcVersion);
            }
            loaderBox.setSelectedIndex(loaderIndexOf(info.loaderKind));
        }
        updateDownloadButtonText();
    }

    public void ensureMcVersions() {
        if (mcLoaded) return;
        mcLoaded = true;
        String current = mcBox.getEditor().getItem() == null ? "" : mcBox.getEditor().getItem().toString();
        win.pool().submit(() -> {
            try {
                mcl.meta.VersionManifest manifest = mcl.meta.VersionManifest.fetch(win.game().source());
                // 全部正式版都要：早先这里截断到前 40 个，而 40 个正好只够退到 1.14~1.16，
                // 用户滚到底会以为「版本只有到 1.16」。manifest 已在 fetch 里按发布时间倒序
                List<String> releases = mcl.meta.VersionManifest.releaseIds(manifest.versions);
                SwingUtilities.invokeLater(() -> {
                    String keep = current;
                    mcBox.removeAllItems();
                    // 第一项必须是「全部版本」：removeAllItems 后 JComboBox 会自动选中第 0 项，
                    // 若直接铺版本列表就等于默认勾了最新版，会把大多数模组筛掉（Sodium 就搜不到）
                    mcBox.addItem(MC_ALL);
                    if (!Compat.isBlank(keep) && !MC_ALL.equals(keep)) mcBox.addItem(keep);
                    for (String id : releases) if (!id.equals(keep)) mcBox.addItem(id);
                    mcBox.setSelectedItem(Compat.isBlank(keep) ? MC_ALL : keep);
                    // 列表刚被整体替换过，popup 的滚动位置会留在旧位置，
                    // 展开时可能直接停在最老的那个版本上 —— 明确拨回第一项
                    mcBox.setSelectedIndex(0);
                    StringBuilder head = new StringBuilder();
                    for (int i = 0; i < Math.min(3, mcBox.getItemCount()); i++) {
                        if (i > 0) head.append(", ");
                        head.append(mcBox.getItemAt(i));
                    }
                    mcl.util.Log.info("版本下拉 " + mcBox.getItemCount() + " 项，前三：" + head);
                    // 截图 / 调试用：-Dmcl.popup=1 自动展开版本下拉，省得手点
                    if (System.getProperty("mcl.popup") != null) {
                        SwingUtilities.invokeLater(() -> mcBox.setPopupVisible(true));
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    public void refresh() {
        Path dir = Compat.isBlank(versionId)
                ? win.game().gameDir().resolve("mods")
                : win.game().modsDir(versionId);
        List<Mods.Entry> list = Mods.list(dir);
        SwingUtilities.invokeLater(() -> {
            allInstalled.clear();
            allInstalled.addAll(list);
            applyFilter();
            installedHint.setToolTipText(dir.toString());
        });
    }

    /** 按过滤词重建列表（不重新读盘）。 */
    private void applyFilter() {
        String q = installedFilter.getText() == null ? "" : installedFilter.getText().trim().toLowerCase();
        installedModel.clear();
        int shown = 0;
        for (Mods.Entry e : allInstalled) {
            if (!q.isEmpty()) {
                String hay = (e.displayName() + " " + e.name).toLowerCase();
                if (!hay.contains(q)) continue;
            }
            installedModel.addElement(e);
            shown++;
        }
        long enabled = allInstalled.stream().filter(x -> x.enabled).count();
        if (Compat.isBlank(versionId)) {
            installedHint.setText("未选择版本");
        } else if (q.isEmpty()) {
            installedHint.setText(allInstalled.size() + " 个模组（启用 " + enabled + "）");
        } else {
            installedHint.setText("匹配 " + shown + " / " + allInstalled.size()
                    + " 个模组（启用 " + enabled + "）");
        }
        if (!installedModel.isEmpty() && installedList.getSelectedIndex() < 0) installedList.setSelectedIndex(0);
        updateInstalledButtons();
    }

    /** 批量启用 / 禁用（作用于当前过滤结果，方便只处理某一类模组）。 */
    private void setAllEnabled(boolean enabled) {
        if (allInstalled.isEmpty()) return;
        List<Mods.Entry> targets = new java.util.ArrayList<>();
        for (int i = 0; i < installedModel.size(); i++) {
            Mods.Entry e = installedModel.get(i);
            if (e.enabled != enabled) targets.add(e);
        }
        if (targets.isEmpty()) {
            win.setStatus(enabled ? "已经是全部启用状态" : "已经是全部禁用状态");
            return;
        }
        win.setStatus((enabled ? "正在启用 " : "正在禁用 ") + targets.size() + " 个模组…");
        win.pool().submit(() -> {
            int ok = 0;
            for (Mods.Entry e : targets) {
                try {
                    Mods.setEnabled(e, enabled);
                    ok++;
                } catch (Exception ex) {
                    mcl.util.Log.warn("切换 " + e.name + " 失败：" + ex.getMessage());
                }
            }
            final int done = ok;
            SwingUtilities.invokeLater(() -> {
                refresh();
                win.setStatus((enabled ? "已启用 " : "已禁用 ") + done + " 个模组");
            });
        });
    }

    private void updateInstalledButtons() {
        List<Mods.Entry> sel = installedList.getSelectedValuesList();
        boolean has = !sel.isEmpty();
        toggleBtn.setEnabled(has);
        deleteBtn.setEnabled(has);
        boolean allEnabled = has && sel.stream().allMatch(x -> x.enabled);
        toggleBtn.setText(sel.size() > 1 ? (allEnabled ? "禁用选中" : "启用选中")
                : (has && !allEnabled ? "启用" : "禁用"));
        if (has) {
            deleteBtn.setText(sel.size() > 1 ? "删除 " + sel.size() + " 个" : "删除");
        } else {
            deleteBtn.setText("删除");
        }
    }

    private void updateDownloadButtonText() {
        String type = typeName();
        downloadBtn.setText(Modrinth.TYPE_MODPACK.equals(type) ? "安装选中整合包" : "下载到当前版本");
        moreBtn.setVisible(resultModel.size() > 0 && resultModel.size() < totalHits);
    }

    /** 当前选中的内容源。下拉项由 {@link ModSource#available()} 生成，顺序一致。 */
    private mcl.mod.ModSource source() {
        ModSource[] all = ModSource.available();
        int i = sourceBox.getSelectedIndex();
        return (i >= 0 && i < all.length) ? all[i] : ModSource.MODRINTH;
    }

    private String typeName() {
        if (typeBox.getSelectedIndex() == 1) {
            return Modrinth.TYPE_MODPACK;
        }
        if (typeBox.getSelectedIndex() == 2) {
            return Modrinth.TYPE_RESOURCEPACK;
        }
        if (typeBox.getSelectedIndex() == 3) {
            return Modrinth.TYPE_SHADER;
        }
        return Modrinth.TYPE_MOD;
    }

    /** 下拉里选中的游戏版本；选「全部版本」或留空时返回空串，表示不加版本面元。 */
    private String mcValue() {
        Object v = mcBox.getEditor().getItem();
        String s = v == null ? "" : v.toString().trim();
        return MC_ALL.equals(s) ? "" : s;
    }

    private String loaderName() {
        if (loaderBox.getSelectedIndex() == 1) {
            return "fabric";
        }
        if (loaderBox.getSelectedIndex() == 2) {
            return "quilt";
        }
        if (loaderBox.getSelectedIndex() == 3) {
            return "forge";
        }
        if (loaderBox.getSelectedIndex() == 4) {
            return "neoforge";
        }
        return "";
    }

    private String sortName() {
        if (sortBox.getSelectedIndex() == 1) {
            return "downloads";
        }
        if (sortBox.getSelectedIndex() == 2) {
            return "newest";
        }
        if (sortBox.getSelectedIndex() == 3) {
            return "updated";
        }
        return "relevance";
    }

    // ---------------- 操作 ----------------

    private void toggleSelected() {
        List<Mods.Entry> sel = installedList.getSelectedValuesList();
        if (sel.isEmpty()) return;
        boolean allEnabled = sel.stream().allMatch(x -> x.enabled);
        boolean target = !allEnabled;
        try {
            for (Mods.Entry e : sel) if (e.enabled != target) Mods.setEnabled(e, target);
            refresh();
            win.setStatus((target ? "已启用 " : "已禁用 ") + sel.size() + " 个模组");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "操作失败：" + ex.getMessage());
            refresh();
        }
    }

    private void deleteSelected() {
        List<Mods.Entry> sel = installedList.getSelectedValuesList();
        if (sel.isEmpty()) return;
        String what = sel.size() == 1 ? "「" + sel.get(0).displayName() + "」"
                : "选中的 " + sel.size() + " 个模组";
        if (JOptionPane.showConfirmDialog(this, "删除 " + what + "？\n\n文件会被直接删除，不可恢复。",
                "删除模组", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        win.pool().submit(() -> {
            int ok = 0;
            for (Mods.Entry e : sel) {
                try {
                    Mods.delete(e);
                    ok++;
                } catch (Exception ex) {
                    mcl.util.Log.warn("删除 " + e.name + " 失败：" + ex.getMessage());
                }
            }
            final int done = ok;
            SwingUtilities.invokeLater(() -> {
                refresh();
                win.setStatus("已删除 " + done + " 个模组");
            });
        });
    }

    private void addLocalFiles() {
        if (Compat.isBlank(versionId)) {
            JOptionPane.showMessageDialog(this, "请先在底部选择一个版本");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("选择要加入的模组 / 资源包");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path dir = win.game().modsDir(versionId);
        int count = 0;
        for (File f : chooser.getSelectedFiles()) {
            try {
                Mods.importFile(dir, f.toPath());
                count++;
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "导入 " + f.getName() + " 失败：" + ex.getMessage());
            }
        }
        refresh();
        win.setStatus("已导入 " + count + " 个文件到 " + dir);
    }

    private void openModsDir() {
        Path dir = Compat.isBlank(versionId) ? win.game().gameDir().resolve("mods") : win.game().modsDir(versionId);
        try {
            Dirs.ensure(dir);
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "无法打开目录：" + ex.getMessage());
        }
    }

    // ---------------- 更新检查 / 冲突检测 ----------------

    private void checkUpdates() {
        if (Compat.isBlank(versionId)) {
            JOptionPane.showMessageDialog(this, "请先在底部选择一个版本");
            return;
        }
        Path dir = win.game().modsDir(versionId);
        VersionManager.Info info = win.game().versions().describe(versionId);
        win.setStatus("正在检查模组更新…");
        win.pool().submit(() -> {
            List<ModInspector.Meta> mods = ModInspector.scan(dir);
            List<ModInspector.Update> ups = ModInspector.checkUpdates(mods, info.mcVersion,
                    info.modrinthLoader(),
                    (done, total) -> win.setStatus("检查模组更新 " + done + "/" + total));
            SwingUtilities.invokeLater(() -> afterUpdateCheck(dir, mods.size(), ups));
        });
    }

    private void afterUpdateCheck(Path dir, int total, List<ModInspector.Update> ups) {
        if (total == 0) {
            win.setStatus("当前版本没有安装模组");
            JOptionPane.showMessageDialog(this, "当前版本没有安装任何模组");
            return;
        }
        if (ups.isEmpty()) {
            win.setStatus("全部 " + total + " 个模组已是最新");
            Ui.showText(this, "检查更新",
                    "已检查 " + total + " 个模组，没有发现可用更新。\n\n"
                            + "提示：只有发布在 Modrinth 上的模组才能自动检查更新，"
                            + "其它来源的模组会被跳过。", 440, 170);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ModInspector.Update u : ups) {
            sb.append("· ").append(u.mod.name.isEmpty() ? u.mod.file : u.mod.name)
                    .append("  ").append(u.mod.version.isEmpty() ? "?" : u.mod.version)
                    .append(" → ").append(u.latestVersion.isEmpty() ? u.latestName : u.latestVersion)
                    .append("\n    ").append(u.mod.file).append("\n");
        }
        javax.swing.JTextArea area = new javax.swing.JTextArea(sb.toString());
        area.setEditable(false);
        area.setFont(Ui.font(Font.PLAIN, 12.5f));
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JScrollPane sp = Ui.scroll(area);
        sp.setPreferredSize(new Dimension(470, Math.min(320, 60 + ups.size() * 40)));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(Ui.label("共 " + total + " 个模组，发现 " + ups.size() + " 个更新：",
                Font.PLAIN, 12.5f, Ui.MUTED), BorderLayout.NORTH);
        wrap.add(sp, BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(this, wrap, "发现可用更新",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (r == JOptionPane.OK_OPTION) applyUpdates(dir, ups);
    }

    private void applyUpdates(Path dir, List<ModInspector.Update> ups) {
        win.setStatus("正在更新 " + ups.size() + " 个模组…");
        win.pool().submit(() -> {
            int ok = 0;
            StringBuilder failed = new StringBuilder();
            for (ModInspector.Update u : ups) {
                if (u.downloadUrl == null || Compat.isBlank(u.downloadUrl)) continue;
                try {
                    win.setStatus("更新 " + u.mod.name + " → " + u.latestVersion);
                    String name = u.latestFile == null || Compat.isBlank(u.latestFile) ? u.mod.file : u.latestFile;
                    Path dest = dir.resolve(name);
                    mcl.mod.ModInstaller.downloadFile(u.downloadUrl, dest, u.sha1, u.size, null);
                    if (!dest.equals(u.mod.path)) Files.deleteIfExists(u.mod.path);
                    ok++;
                } catch (Exception ex) {
                    failed.append(u.mod.file).append(": ").append(ex.getMessage()).append("\n");
                }
            }
            int done = ok;
            String failText = failed.toString();
            SwingUtilities.invokeLater(() -> {
                refresh();
                win.setStatus("已更新 " + done + " 个模组" + (failText.isEmpty() ? "" : "（有失败）"));
                if (!failText.isEmpty()) Ui.showText(this, "部分模组更新失败", failText, 460, 200);
            });
        });
    }

    private void checkConflicts() {
        if (Compat.isBlank(versionId)) {
            JOptionPane.showMessageDialog(this, "请先在底部选择一个版本");
            return;
        }
        Path dir = win.game().modsDir(versionId);
        win.setStatus("正在检查模组冲突…");
        win.pool().submit(() -> {
            List<ModInspector.Meta> mods = ModInspector.scan(dir);
            List<ModInspector.Issue> issues = ModInspector.checkConflicts(mods);
            SwingUtilities.invokeLater(() -> {
                if (issues.isEmpty()) {
                    win.setStatus("没有发现模组冲突");
                    Ui.showText(this, "检查冲突",
                            "已扫描 " + mods.size() + " 个模组，没有发现重复安装、加载器混装或缺失依赖。",
                            440, 160);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                int errors = 0;
                for (ModInspector.Issue i : issues) {
                    if ("错误".equals(i.level)) errors++;
                    sb.append("【").append(i.level).append("】").append(i.title).append("\n")
                            .append(i.detail).append("\n\n");
                }
                sb.append("共 ").append(mods.size()).append(" 个模组，")
                        .append(issues.size()).append(" 个问题（其中错误 ").append(errors).append(" 个）");
                win.setStatus("发现 " + issues.size() + " 个模组问题");
                Ui.showText(this, "检查冲突", sb.toString(), 520, 340);
            });
        });
    }

    private void search(boolean fromStart) {
        if (loading) return;
        ensureMcVersions();
        loading = true;
        if (fromStart) offset = 0;
        searchBtn.setEnabled(false);
        resultHint.setText("正在搜索…");
        final String raw = query.getText() == null ? "" : query.getText().trim();
        String mc = mcValue();
        final String type = typeName();
        final String loader = Modrinth.TYPE_MODPACK.equals(type) ? "" : loaderName();
        final String sort = sortName();
        final int page = offset;
        final mcl.mod.ModSource src = source();
        // 中文查询交给 ZhRecall：锚定 + 本地中文索引 + 译名扩召回 + 原文兜底。
        // 翻页时只走主召回，重叠部分靠 seenSlugs 去重。
        win.pool().submit(() -> {
            try {
                if (Boolean.getBoolean("mcl.dump")) {
                    System.out.println("[SEARCH] src=" + src.id + " q=[" + raw + "] type=" + type
                            + " mc=[" + mc + "] loader=[" + loader + "] sort=" + sort + " offset=" + page
                            + " devq=[" + System.getProperty("mcl.query", "<unset>") + "]"
                            + " field=[" + (query.getText() == null ? "<null>" : query.getText()) + "]");
                }
                mcl.mod.ZhRecall.Result r = mcl.mod.ZhRecall.search(src, raw, type, mc, loader, sort, page, 20);
                SwingUtilities.invokeLater(() -> {
                    loading = false;
                    searchBtn.setEnabled(true);
                    if (page == 0) {
                        resultModel.clear();
                        seenSlugs.clear();
                    }
                    int added = 0;
                    for (Modrinth.Hit h : r.merged.hits) {
                        if (!Compat.isBlank(h.slug) && !seenSlugs.add(h.slug)) continue;
                        resultModel.addElement(h);
                        added++;
                    }
                    totalHits = r.merged.totalHits;
                    offset = r.nextOffset;
                    resultHint.setText(hintFor(src, raw, r, added));
                    if (page == 0) {
                        // 逐条 addElement 的过程中 JList 会把视口带偏，新结果必须显式回到第一条，
                        // 否则用户看到的是列表中间那截（锚定的正主反而被滚出屏幕）
                        resultList.clearSelection();
                        resultList.ensureIndexIsVisible(0);
                        resultList.scrollRectToVisible(new java.awt.Rectangle(0, 0, 1, 1));
                    }
                    if (!r.merged.hits.isEmpty() && resultList.getSelectedIndex() < 0) {
                        resultList.setSelectedIndex(0);
                    }
                    // 开发 / 截图用：-Dmcl.dump=true 把列表模型前几条打到 stdout，便于核对
                    if (Boolean.getBoolean("mcl.dump")) {
                        int n = Math.min(6, resultModel.size());
                        System.out.println("[DUMP] 列表共 " + resultModel.size() + " 条，前 " + n + " 条：");
                        for (int i = 0; i < n; i++) {
                            Modrinth.Hit h = resultModel.get(i);
                            System.out.println("  " + (i + 1) + ". " + h.slug + "  by=" + h.matchedBy
                                    + "  zh=[" + h.zhTitle + "]  " + h.title);
                        }
                    }
                    updateDownloadButtonText();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    loading = false;
                    searchBtn.setEnabled(true);
                    resultHint.setText(src.label + " 搜索失败：" + ex.getMessage());
                });
            }
        });
    }

    /** 结果提示：说明这批结果是怎么凑出来的，0 结果时给出可操作的建议。 */
    private String hintFor(mcl.mod.ModSource src, String raw, mcl.mod.ZhRecall.Result r, int added) {
        if (r.merged.hits.isEmpty()) {
            if (!src.isModrinth()) {
                return "CurseForge 上没有匹配「" + raw + "」的内容。换个说法，或切回 Modrinth 用英文关键词试试";
            }
            if (ZhSearch.hasCjk(raw)) {
                return "两个源都没找到「" + raw + "」。换个更通用的说法（例如「钠」「小地图」），或直接用英文名";
            }
            return "没有结果。换个关键词，或放宽上面的版本 / 加载器筛选";
        }
        StringBuilder sb = new StringBuilder();
        String sum = r.summary();
        if (sum.length() > 0) sb.append(sum).append(" · ");
        sb.append("已显示 ").append(resultModel.size());
        if (r.merged.totalHits > resultModel.size()) {
            sb.append(" / 共 ").append(r.merged.totalHits);
        }
        if (added == 0) sb.append(" · 都是已显示过的");
        if (src.isModrinth() && ZhSearch.hasCjk(raw) && !r.zhIndexOk) {
            sb.append(" · 中文索引暂不可用");
        }
        return sb.toString();
    }

    private void downloadSelected() {
        Modrinth.Hit hit = resultList.getSelectedValue();
        if (hit == null) {
            JOptionPane.showMessageDialog(this, "请先选择一条搜索结果");
            return;
        }
        final boolean pack = Modrinth.TYPE_MODPACK.equals(typeName());
        // 装整合包会自己新建实例、自己带游戏版本，跟「底部当前选中的版本」毫无关系。
        // 这里原来不分类型一律要求先选版本，结果在没装任何版本的新配置里点整合包，
        // 弹一句「请先在底部选择一个版本」——而底部下拉根本是空的，等于把人堵死。
        if (!pack && Compat.isBlank(versionId)) {
            JOptionPane.showMessageDialog(this, "请先在底部选择一个版本");
            return;
        }
        String mc = mcValue();
        String loader = loaderName();
        // 优先传项目 id：CurseForge 的任何后续查询（列文件、装依赖）都只认数字 modId，
        // 传 slug 过去得再查一次去换；Modrinth 的 id 与 slug 接口通用，所以两边都安全
        String idOrSlug = Compat.isBlank(hit.projectId) ? hit.slug : hit.projectId;
        if (pack) {
            win.installModpackFrom(source(), idOrSlug, mc, loader);
        } else {
            win.downloadFrom(versionId, idOrSlug, typeName(), source());
        }
    }

    // ---------------- 渲染 ----------------

    private class ModRenderer extends JPanel implements ListCellRenderer<Mods.Entry> {
        private final JLabel icon = new JLabel("", JLabel.CENTER);
        private final JLabel name = Ui.label("", Font.BOLD, 13.5f, Ui.TEXT);
        private final JLabel sub = Ui.label("", Font.PLAIN, 11.5f, Ui.MUTED);
        private final JLabel state = Ui.label("", Font.PLAIN, 11f, Ui.MUTED);
        private boolean sel;

        ModRenderer() {
            setOpaque(false);
            setLayout(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            icon.setPreferredSize(new Dimension(34, 34));
            icon.setOpaque(true);
            icon.setFont(Ui.font(Font.BOLD, 12f));
            icon.setForeground(Color.WHITE);
            add(icon, BorderLayout.WEST);
            add(Ui.col(3, name, sub), BorderLayout.CENTER);
            state.setPreferredSize(new Dimension(50, 20));
            state.setHorizontalAlignment(JLabel.RIGHT);
            add(state, BorderLayout.EAST);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (sel) Ui.paintRowSelection(g, this, 0.90f);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Mods.Entry> l, Mods.Entry e, int index,
                                                      boolean selected, boolean focus) {
            sel = selected;
            icon.setText(e.enabled ? "M" : "×");
            icon.setBackground(e.enabled ? Ui.blend(Ui.accent(), Color.WHITE, 0.2f) : new Color(0xB9BECD));
            name.setText(e.displayName());
            sub.setText(e.name + "  ·  " + Ui.bytes(e.size));
            state.setText(e.enabled ? "已启用" : "已禁用");
            state.setForeground(e.enabled ? new Color(0x2E9E63) : Ui.MUTED);
            return this;
        }
    }

    private class HitRenderer extends JPanel implements ListCellRenderer<Modrinth.Hit> {
        /** 图标边长：行高 56，留出上下 9px 的呼吸。 */
        private static final int ICON_PX = 38;

        private final JLabel icon = new JLabel("", JLabel.CENTER);
        private final JLabel title = Ui.label("", Font.BOLD, 13.5f, Ui.TEXT);
        private final JLabel sub = Ui.label("", Font.PLAIN, 11.5f, Ui.MUTED);
        private final JLabel right = Ui.label("", Font.PLAIN, 11f, Ui.MUTED);
        /** 缓存「加载完重绘哪个列表」，免得每渲染一行都 new 一个 lambda。 */
        private JList<?> hookOwner;
        private Runnable repaintHook;
        private boolean sel;

        HitRenderer() {
            setOpaque(false);
            setLayout(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            icon.setPreferredSize(new Dimension(ICON_PX, ICON_PX));
            icon.setOpaque(true);
            icon.setFont(Ui.font(Font.BOLD, 13f));
            icon.setForeground(Color.WHITE);
            add(icon, BorderLayout.WEST);
            add(Ui.col(3, title, sub), BorderLayout.CENTER);
            right.setPreferredSize(new Dimension(88, 20));
            right.setHorizontalAlignment(JLabel.RIGHT);
            add(right, BorderLayout.EAST);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (sel) Ui.paintRowSelection(g, this, 0.90f);
        }

        private Runnable hookFor(JList<?> l) {
            if (l != hookOwner) {
                hookOwner = l;
                final JList<?> target = l;
                repaintHook = new Runnable() {
                    @Override
                    public void run() {
                        target.repaint();
                    }
                };
            }
            return repaintHook;
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Modrinth.Hit> l, Modrinth.Hit h, int index,
                                                      boolean selected, boolean focus) {
            sel = selected;
            // 优先显示项目自己的 logo（Modrinth / CurseForge 都给了 icon_url）。
            // 图还没下到、或者压根没有图，就退回首字母色块 —— 至少不会空一块白。
            java.awt.image.BufferedImage art = Icons.get(h.iconUrl, ICON_PX, hookFor(l));
            if (art != null) {
                icon.setIcon(new javax.swing.ImageIcon(art));
                icon.setText("");
                icon.setOpaque(false);
            } else {
                // 图标取英文名首字母，避免中文名和英文名换来换去
                String face = h.title == null || h.title.isEmpty() ? h.slug : h.title;
                icon.setIcon(null);
                icon.setText(face.isEmpty() ? "?" : face.substring(0, face.offsetByCodePoints(0, 1)).toUpperCase());
                icon.setOpaque(true);
                // 底色固定：选中与否由整行背景表达，图标区不再跟着深浅变化
                icon.setBackground(Ui.blend(Ui.accent(), Color.WHITE, 0.45f));
            }
            title.setText(h.displayTitle());
            String desc = h.description == null ? "" : h.description;
            if (desc.length() > 70) desc = desc.substring(0, 70) + "…";
            // 标出来源，让用户知道这条为什么排在前面
            if ("anchor".equals(h.matchedBy)) desc = "√ 中文精确匹配  ·  " + desc;
            else if ("zhindex".equals(h.matchedBy)) desc = "中文索引  ·  " + desc;
            sub.setText(desc);
            right.setText(formatDownloads(h.downloads) + " 次下载");
            return this;
        }

        private String formatDownloads(long d) {
            if (d >= 1_000_000) return String.format("%.1fM", d / 1_000_000.0);
            if (d >= 1_000) return String.format("%.1fK", d / 1000.0);
            return String.valueOf(d);
        }
    }
}
