package mcl.ui;

import mcl.util.Dirs;
import mcl.util.Compat;

import mcl.game.Worlds;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 存档页：列出当前版本的存档，支持备份、导入、复制、删除、打开目录。 */
public class WorldsView extends JPanel {
    private final MainWindow win;
    private final DefaultListModel<Worlds.World> model = new DefaultListModel<>();
    private final JList<Worlds.World> list = new JList<>(model);
    private final JLabel hint = Ui.muted("");
    private final Ui.RoundButton backupBtn = (Ui.RoundButton) Ui.ghost("备份");
    private final Ui.RoundButton copyBtn = (Ui.RoundButton) Ui.ghost("复制存档");
    private final Ui.RoundButton deleteBtn = (Ui.RoundButton) Ui.ghost("删除");
    private final Ui.RoundButton exportBtn = (Ui.RoundButton) Ui.ghost("导出 zip");
    private final Map<String, ImageIcon> iconCache = new ConcurrentHashMap<>();

    private String versionId = "";

    public WorldsView(MainWindow win) {
        this.win = win;
        setOpaque(false);
        setLayout(new BorderLayout(0, 12));

        Ui.RoundButton openDir = (Ui.RoundButton) Ui.ghost("打开存档目录");
        openDir.addActionListener(e -> openSavesDir());
        Ui.RoundButton importBtn = (Ui.RoundButton) Ui.ghost("导入存档 zip");
        importBtn.addActionListener(e -> importZip());
        Ui.RoundButton urlBtn = (Ui.RoundButton) Ui.ghost("从链接导入");
        urlBtn.addActionListener(e -> importFromUrl());
        Ui.RoundButton refresh = (Ui.RoundButton) Ui.ghost("刷新");
        refresh.addActionListener(e -> refresh());

        JPanel header = Ui.transparent();
        header.setLayout(new BorderLayout());
        header.add(Ui.col(4, Ui.title("存档"),
                Ui.subtitle("管理当前版本的世界：备份成 zip、导入别人的存档、删除或另存一份")),
                BorderLayout.WEST);
        header.add(Ui.row(8, importBtn, urlBtn, openDir, refresh), BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        Ui.RoundedPanel card = (Ui.RoundedPanel) Ui.card();
        card.setLayout(new BorderLayout(0, 10));
        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(62);
        list.setCellRenderer(new WorldRenderer());
        list.addListSelectionListener(e -> updateButtons());
        card.add(Ui.scroll(list), BorderLayout.CENTER);

        JPanel footer = Ui.transparent();
        footer.setLayout(new BorderLayout());
        footer.add(hint, BorderLayout.WEST);
        backupBtn.addActionListener(e -> backupSelected());
        copyBtn.addActionListener(e -> copySelected());
        deleteBtn.addActionListener(e -> deleteSelected());
        exportBtn.addActionListener(e -> exportSelected());
        footer.add(Ui.row(8, exportBtn, backupBtn, copyBtn, deleteBtn), BorderLayout.EAST);
        card.add(footer, BorderLayout.SOUTH);

        JPanel wrap = Ui.transparent();
        wrap.setLayout(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        wrap.add(card, BorderLayout.CENTER);
        add(wrap, BorderLayout.CENTER);
    }

    public void setVersion(String versionId) {
        this.versionId = versionId == null ? "" : versionId;
        refresh();
    }

    public void refresh() {
        if (Compat.isBlank(versionId)) {
            model.clear();
            hint.setText("未选择版本");
            updateButtons();
            return;
        }
        Path run = win.game().runDir(versionId);
        List<Worlds.World> worlds = Worlds.list(run);
        SwingUtilities.invokeLater(() -> {
            Worlds.World keep = list.getSelectedValue();
            model.clear();
            for (Worlds.World w : worlds) model.addElement(w);
            if (keep != null) {
                for (int i = 0; i < model.size(); i++) {
                    if (model.get(i).folder.equals(keep.folder)) {
                        list.setSelectedIndex(i);
                        break;
                    }
                }
            }
            if (list.getSelectedIndex() < 0 && !worlds.isEmpty()) list.setSelectedIndex(0);
            hint.setText(worlds.size() + " 个存档");
            hint.setToolTipText(Worlds.savesDir(run).toString());
            updateButtons();
        });
    }

    private void updateButtons() {
        boolean has = list.getSelectedValue() != null;
        backupBtn.setEnabled(has);
        copyBtn.setEnabled(has);
        deleteBtn.setEnabled(has);
        exportBtn.setEnabled(has);
    }

    // ---------------- 操作 ----------------

    private Path runDir() {
        return win.game().runDir(versionId);
    }

    private void backupSelected() {
        Worlds.World w = list.getSelectedValue();
        if (w == null) return;
        win.setStatus("正在备份 " + w.name + "…");
        win.pool().submit(() -> {
            try {
                Path zip = Worlds.backup(win.game().gameDir(), w);
                SwingUtilities.invokeLater(() -> {
                    win.setStatus("已备份到 " + zip);
                    JOptionPane.showMessageDialog(this, "备份完成：\n" + zip);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "备份失败：" + ex.getMessage()));
            }
        });
    }

    private void copySelected() {
        Worlds.World w = list.getSelectedValue();
        if (w == null) return;
        win.pool().submit(() -> {
            try {
                Worlds.World copy = Worlds.duplicate(runDir(), w);
                SwingUtilities.invokeLater(() -> {
                    refresh();
                    win.setStatus("已复制为 " + copy.folder);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "复制失败：" + ex.getMessage()));
            }
        });
    }

    private void deleteSelected() {
        Worlds.World w = list.getSelectedValue();
        if (w == null) return;
        if (JOptionPane.showConfirmDialog(this,
                "删除存档「" + w.name + "」？\n\n这会永久删除目录 " + w.dir
                        + "\n（建议先点「备份」生成 zip）", "删除存档",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        win.pool().submit(() -> {
            try {
                Worlds.delete(w);
                SwingUtilities.invokeLater(() -> {
                    refresh();
                    win.setStatus("已删除存档 " + w.folder);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "删除失败：" + ex.getMessage()));
            }
        });
    }

    private void importZip() {
        if (Compat.isBlank(versionId)) {
            JOptionPane.showMessageDialog(this, "请先在底部选择一个版本");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择存档压缩包");
        chooser.setFileFilter(new FileNameExtensionFilter("存档压缩包 (*.zip)", "zip"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path zip = chooser.getSelectedFile().toPath();
        win.setStatus("正在导入 " + zip.getFileName() + "…");
        win.pool().submit(() -> {
            try {
                Worlds.World w = Worlds.importZip(runDir(), zip);
                SwingUtilities.invokeLater(() -> {
                    refresh();
                    win.setStatus("已导入存档 " + w.folder);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "导入失败：" + ex.getMessage()));
            }
        });
    }

    /** 从任意 http(s) 链接下载存档 zip 并导入（很多存档只提供直链 / 蓝奏云直链）。 */
    private void importFromUrl() {
        if (Compat.isBlank(versionId)) {
            JOptionPane.showMessageDialog(this, "请先在底部选择一个版本");
            return;
        }
        String input = JOptionPane.showInputDialog(this,
                "存档压缩包的下载地址（.zip 直链）：\n\n"
                        + "下载完成后会自动解到 saves/ 下，遇到压缩包内多一层目录也能识别。",
                "从链接导入存档", JOptionPane.PLAIN_MESSAGE);
        if (input == null) return;
        String url = input.trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            JOptionPane.showMessageDialog(this, "请填写 http:// 或 https:// 开头的直链地址");
            return;
        }
        String name = url;
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (Compat.isBlank(name)) name = "world-" + System.currentTimeMillis() + ".zip";
        if (!name.toLowerCase().endsWith(".zip")) name = name + ".zip";
        final String fileName = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        win.runTask("下载存档 " + fileName, l -> {
            Path dir = win.settings().launcherDir().resolve("cache").resolve("worlds");
            Dirs.ensure(dir);
            Path tmp = dir.resolve(fileName);
            l.onStage("下载 " + fileName);
            final long[] got = {0};
            mcl.net.Http.download(url, tmp, null, 0, n -> {
                got[0] += n;
                l.onProgress(0, 1, got[0], 0, fileName);
            });
            l.onStage("解压存档");
            Worlds.World w = Worlds.importZip(runDir(), tmp);
            SwingUtilities.invokeLater(this::refresh);
            return "已导入存档：" + w.name + "（" + Ui.bytes(w.size) + "）";
        });
    }

    /** 导出选中的存档为 zip（发给朋友 / 传到网盘）。 */
    private void exportSelected() {
        Worlds.World w = list.getSelectedValue();
        if (w == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出存档");
        chooser.setSelectedFile(new File(safeName(w.name) + ".zip"));
        chooser.setFileFilter(new FileNameExtensionFilter("存档压缩包 (*.zip)", "zip"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath();
        if (!target.toString().toLowerCase().endsWith(".zip")) target = java.nio.file.Paths.get(target + ".zip");
        Path finalTarget = target;
        win.pool().submit(() -> {
            try {
                Worlds.zipDir(w.dir, finalTarget);
                SwingUtilities.invokeLater(() -> {
                    win.setStatus("已导出 " + finalTarget.getFileName() + "（" + Ui.bytes(w.size) + "）");
                    JOptionPane.showMessageDialog(this, "存档已导出：\n" + finalTarget);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "导出失败：" + ex.getMessage()));
            }
        });
    }

    private static String safeName(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return t.isEmpty() ? "world" : t;
    }

    private void openSavesDir() {
        try {
            Path dir = Worlds.savesDir(runDir());
            Dirs.ensure(dir);
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "无法打开目录：" + ex.getMessage());
        }
    }

    // ---------------- 渲染 ----------------

    private class WorldRenderer extends JPanel implements ListCellRenderer<Worlds.World> {
        private final JLabel icon = new JLabel("", JLabel.CENTER);
        private final JLabel name = Ui.label("", Font.BOLD, 13.5f, Ui.TEXT);
        private final JLabel sub = Ui.label("", Font.PLAIN, 11.5f, Ui.MUTED);
        private final JLabel size = Ui.label("", Font.PLAIN, 11f, Ui.MUTED);
        private boolean sel;

        WorldRenderer() {
            setOpaque(false);
            setLayout(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            icon.setPreferredSize(new Dimension(48, 48));
            icon.setOpaque(true);
            icon.setBackground(new Color(0xE8EBF3));
            add(icon, BorderLayout.WEST);
            add(Ui.col(4, name, sub), BorderLayout.CENTER);
            size.setPreferredSize(new Dimension(76, 20));
            size.setHorizontalAlignment(JLabel.RIGHT);
            add(size, BorderLayout.EAST);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (sel) Ui.paintRowSelection(g, this, 0.90f);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Worlds.World> l, Worlds.World w, int index,
                                                      boolean selected, boolean focus) {
            sel = selected;
            name.setText(w.name);
            sub.setText(w.subtitle());
            size.setText(Ui.bytes(w.size));
            ImageIcon img = iconFor(w);
            if (img != null) {
                icon.setIcon(img);
                icon.setText("");
            } else {
                icon.setIcon(null);
                icon.setText("🗺");
                icon.setFont(Ui.font(Font.PLAIN, 20f));
                icon.setForeground(Ui.MUTED);
            }
            return this;
        }

        private ImageIcon iconFor(Worlds.World w) {
            Path p = w.iconFile();
            if (p == null) return null;
            return iconCache.computeIfAbsent(p.toString(), k -> {
                try {
                    Image src = javax.imageio.ImageIO.read(new File(k));
                    if (src == null) return null;
                    return new ImageIcon(src.getScaledInstance(48, 48, Image.SCALE_SMOOTH));
                } catch (Exception e) {
                    return null;
                }
            });
        }
    }
}
