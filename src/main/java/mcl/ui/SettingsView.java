package mcl.ui;

import mcl.util.Compat;

import mcl.config.Profile;
import mcl.config.Settings;
import mcl.game.Game;
import mcl.game.Isolation;
import mcl.game.JavaFinder;
import mcl.game.VersionManager;
import mcl.net.Source;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 设置页：配置、Java、内存、下载源、外观。 */
public class SettingsView extends JPanel {
    private final MainWindow win;
    private final JComboBox<String> profileBox = new JComboBox<>();
    private final JTextField gameDirField = new JTextField();
    private final JComboBox<String> javaBox = new JComboBox<>();
    private final List<String> javaPaths = new ArrayList<>();
    private final JCheckBox autoMemory = new JCheckBox("自动分配内存");
    private final JSpinner memorySpinner = new JSpinner(new SpinnerNumberModel(4096, 512, 65536, 256));
    private final JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(854, 320, 7680, 1));
    private final JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(480, 240, 4320, 1));
    private final JCheckBox fullscreen = new JCheckBox("全屏启动");
    private final JCheckBox isolate = new JCheckBox("版本隔离");
    private final JComboBox<String> sourceBox = new JComboBox<>();
    private final JSpinner threadSpinner = new JSpinner(new SpinnerNumberModel(32, 1, 256, 1));
    private final JTextField cfKeyField = new JTextField();
    private final JComboBox<String> proxyModeBox = new JComboBox<>(
            new String[]{"不使用代理", "HTTP 代理", "SOCKS5 代理"});
    private final JTextField proxyHostField = new JTextField();
    private final JSpinner proxyPortSpinner = new JSpinner(new SpinnerNumberModel(7890, 1, 65535, 1));
    private final JTextField proxyUserField = new JTextField();
    private final javax.swing.JPasswordField proxyPassField = new javax.swing.JPasswordField();
    private final JSpinner limitSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 102400, 256));
    private final JLabel netHint = Ui.muted("代理只影响启动器自身的下载与接口请求");
    private final JTextField bgField = new JTextField();
    private final JCheckBox showLog = new JCheckBox("启动后显示日志窗口");
    private final Ui.RoundButton themeBtn = (Ui.RoundButton) Ui.ghost("主题色");
    private final JLabel hint = Ui.muted("");

    private boolean loading;

    public SettingsView(MainWindow win) {
        this.win = win;
        setOpaque(false);
        setLayout(new BorderLayout(0, 12));

        JPanel header = Ui.transparent();
        header.setLayout(new BorderLayout());
        header.add(Ui.col(4, Ui.title("设置"),
                Ui.subtitle("所有设置保存在启动器目录下的 mcl.json")), BorderLayout.WEST);
        add(header, BorderLayout.NORTH);

        JPanel content = Ui.transparent();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(section("配置", buildProfileSection()));
        content.add(Box.createVerticalStrut(12));
        content.add(section("Java 与内存", buildJavaSection()));
        content.add(Box.createVerticalStrut(12));
        content.add(section("下载", buildDownloadSection()));
        content.add(Box.createVerticalStrut(12));
        content.add(section("网络", buildNetworkSection()));
        content.add(Box.createVerticalStrut(12));
        content.add(section("外观", buildAppearanceSection()));

        JScrollPane scroll = Ui.scroll(content);
        scroll.setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        Ui.RoundButton save = (Ui.RoundButton) Ui.primary("保存设置");
        save.addActionListener(e -> save());
        JPanel footer = Ui.transparent();
        footer.setLayout(new BorderLayout());
        footer.add(hint, BorderLayout.WEST);
        footer.add(save, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    private JPanel section(String title, JPanel body) {
        Ui.RoundedPanel card = (Ui.RoundedPanel) Ui.card();
        card.add(Ui.label(title, Font.BOLD, 14f, Ui.TEXT));
        card.add(Box.createVerticalStrut(10));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(body);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4000));
        return card;
    }

    private JPanel formRow(String label, Component... comps) {
        JPanel row = Ui.transparent();
        row.setLayout(new BorderLayout(12, 0));
        JLabel l = Ui.label(label, Font.PLAIN, 12.5f, Ui.MUTED);
        l.setPreferredSize(new Dimension(92, 30));
        row.add(l, BorderLayout.WEST);
        JPanel right = Ui.transparent();
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        for (int i = 0; i < comps.length; i++) {
            if (i > 0) right.add(Box.createHorizontalStrut(8));
            right.add(comps[i]);
        }
        right.add(Box.createHorizontalGlue());
        row.add(right, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private JPanel buildProfileSection() {
        JPanel body = Ui.transparent();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        profileBox.setPreferredSize(new Dimension(180, 30));
        profileBox.addActionListener(e -> {
            if (loading) return;
            Object sel = profileBox.getSelectedItem();
            if (sel != null && !sel.equals(win.settings().selectedProfile)) {
                win.settings().selectedProfile = sel.toString();
                reload();
            }
        });
        Ui.RoundButton newProfile = (Ui.RoundButton) Ui.ghost("新建配置");
        newProfile.addActionListener(e -> newProfile());
        body.add(formRow("配置", profileBox, newProfile));
        body.add(Box.createVerticalStrut(6));

        gameDirField.setPreferredSize(new Dimension(320, 30));
        Ui.RoundButton browse = (Ui.RoundButton) Ui.ghost("浏览");
        browse.addActionListener(e -> chooseGameDir());
        Ui.RoundButton open = (Ui.RoundButton) Ui.ghost("打开");
        open.addActionListener(e -> win.openGameDir());
        body.add(formRow("游戏目录", gameDirField, browse, open));
        body.add(Box.createVerticalStrut(6));

        body.add(formRow("启动选项", fullscreen, Ui.muted("留空则使用默认分辨率")));
        body.add(Box.createVerticalStrut(6));
        body.add(formRow("分辨率", widthSpinner, Ui.muted("×"), heightSpinner));
        body.add(Box.createVerticalStrut(6));
        body.add(formRow("版本隔离", isolate,
                Ui.muted("默认开启。每个版本的 mods、存档、按键配置各存一份")));
        return body;
    }

    private JPanel buildJavaSection() {
        JPanel body = Ui.transparent();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        javaBox.setPreferredSize(new Dimension(360, 30));
        Ui.RoundButton browse = (Ui.RoundButton) Ui.ghost("浏览 java.exe");
        browse.addActionListener(e -> chooseJava());
        Ui.RoundButton rescan = (Ui.RoundButton) Ui.ghost("重新检测");
        rescan.addActionListener(e -> {
            JavaFinder.refresh();
            fillJavaBox();
        });
        body.add(formRow("Java", javaBox, browse, rescan));
        body.add(Box.createVerticalStrut(6));
        body.add(formRow("内存", autoMemory, memorySpinner, Ui.muted("MB")));
        memorySpinner.setPreferredSize(new Dimension(110, 30));
        autoMemory.addActionListener(e -> memorySpinner.setEnabled(!autoMemory.isSelected()));
        return body;
    }

    private JPanel buildDownloadSection() {
        JPanel body = Ui.transparent();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        for (Source s : Source.all()) sourceBox.addItem(s.displayName);
        sourceBox.setPreferredSize(new Dimension(180, 30));
        threadSpinner.setPreferredSize(new Dimension(90, 30));
        body.add(formRow("下载源", sourceBox, Ui.muted("官方源慢时建议使用 BMCLAPI 镜像")));
        body.add(Box.createVerticalStrut(6));
        body.add(formRow("下载线程", threadSpinner));
        body.add(Box.createVerticalStrut(6));
        cfKeyField.setPreferredSize(new Dimension(320, 30));
        cfKeyField.putClientProperty("JTextField.placeholderText", "可选；留空用内置 key（共享配额）");
        body.add(formRow("CF Key", cfKeyField,
                Ui.muted("CurseForge API Key，官网免费申请；留空则用内置兜底 key，独享配额请填写")));
        return body;
    }

    private JPanel buildNetworkSection() {
        JPanel body = Ui.transparent();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        proxyModeBox.setPreferredSize(new Dimension(150, 30));
        proxyHostField.setPreferredSize(new Dimension(160, 30));
        proxyHostField.putClientProperty("JTextField.placeholderText", "127.0.0.1");
        proxyPortSpinner.setPreferredSize(new Dimension(80, 30));
        proxyUserField.setPreferredSize(new Dimension(110, 30));
        proxyUserField.putClientProperty("JTextField.placeholderText", "用户名（可选）");
        proxyPassField.setPreferredSize(new Dimension(110, 30));
        proxyPassField.putClientProperty("JTextField.placeholderText", "密码（可选）");

        body.add(formRow("代理", proxyModeBox, proxyHostField, proxyPortSpinner));
        proxyModeBox.addActionListener(e -> {
            boolean on = proxyModeBox.getSelectedIndex() > 0;
            proxyHostField.setEnabled(on);
            proxyPortSpinner.setEnabled(on);
            proxyUserField.setEnabled(on);
            proxyPassField.setEnabled(on);
        });
        body.add(Box.createVerticalStrut(6));
        body.add(formRow("代理认证", proxyUserField, proxyPassField,
                Ui.muted("HTTP 代理需要认证时填写，SOCKS5 可留空")));
        body.add(Box.createVerticalStrut(6));

        limitSpinner.setPreferredSize(new Dimension(110, 30));
        Ui.RoundButton test = (Ui.RoundButton) Ui.ghost("测试连通");
        test.addActionListener(e -> testNetwork());
        body.add(formRow("下载限速", limitSpinner, Ui.muted("KB/s，0 表示不限速（全局限速，不是每线程）"), test));
        body.add(Box.createVerticalStrut(8));
        body.add(formRow("状态", netHint));
        return body;
    }

    private JPanel buildAppearanceSection() {
        JPanel body = Ui.transparent();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        themeBtn.addActionListener(e -> chooseTheme());
        body.add(formRow("主题色", themeBtn));

        bgField.setEditable(false);
        bgField.setPreferredSize(new Dimension(320, 30));
        Ui.RoundButton pick = (Ui.RoundButton) Ui.ghost("选择壁纸");
        pick.addActionListener(e -> chooseBackground());
        Ui.RoundButton clear = (Ui.RoundButton) Ui.ghost("清除");
        clear.addActionListener(e -> {
            bgField.setText("");
            win.settings().backgroundImage = "";
            win.applyTheme();
        });
        body.add(Box.createVerticalStrut(6));
        body.add(formRow("背景图", bgField, pick, clear));
        body.add(Box.createVerticalStrut(6));
        body.add(formRow("日志", showLog));
        return body;
    }

    public void reload() {
        loading = true;
        Settings s = win.settings();
        profileBox.removeAllItems();
        for (Profile p : s.profiles) profileBox.addItem(p.name);
        profileBox.setSelectedItem(s.selectedProfile);

        Profile p = s.profile();
        if (p != null) {
            gameDirField.setText(p.gameDir);
            memorySpinner.setValue(p.maxMemory);
            autoMemory.setSelected(p.autoMemory);
            memorySpinner.setEnabled(!p.autoMemory);
            widthSpinner.setValue(p.width <= 0 ? 854 : p.width);
            heightSpinner.setValue(p.height <= 0 ? 480 : p.height);
            fullscreen.setSelected(p.fullscreen);
            isolate.setSelected(p.isolate);
        }
        fillJavaBox();
        for (int i = 0; i < Source.all().length; i++) {
            if (Source.all()[i].name().equals(s.downloadSource)) sourceBox.setSelectedIndex(i);
        }
        threadSpinner.setValue(s.downloadThreads);
        cfKeyField.setText(s.curseforgeApiKey == null ? "" : s.curseforgeApiKey);
        proxyModeBox.setSelectedIndex(proxyModeIndex(s.proxyMode));
        proxyHostField.setText(s.proxyHost == null ? "" : s.proxyHost);
        proxyPortSpinner.setValue(s.proxyPort <= 0 ? 7890 : s.proxyPort);
        proxyUserField.setText(s.proxyUser == null ? "" : s.proxyUser);
        proxyPassField.setText(s.proxyPass == null ? "" : s.proxyPass);
        boolean useProxy = proxyModeBox.getSelectedIndex() > 0;
        proxyHostField.setEnabled(useProxy);
        proxyPortSpinner.setEnabled(useProxy);
        proxyUserField.setEnabled(useProxy);
        proxyPassField.setEnabled(useProxy);
        limitSpinner.setValue(s.speedLimitKbps);
        netHint.setText(mcl.net.Http.proxyLabel() + " · 限速 "
                + (mcl.net.Throttle.limitBytesPerSecond() <= 0 ? "不限"
                : (mcl.net.Throttle.limitBytesPerSecond() / 1024) + " KB/s"));
        bgField.setText(s.backgroundImage == null ? "" : s.backgroundImage);
        showLog.setSelected(s.showLogWindow);
        themeBtn.setText("主题色 " + s.themeColor);
        themeBtn.refreshColors();
        hint.setText("当前配置：" + s.selectedProfile);
        loading = false;
    }

    /** 代理模式对应的下拉框下标。 */
    private static int proxyModeIndex(String mode) {
        String m = mode == null ? "none" : mode;
        if ("http".equals(m)) return 1;
        if ("socks".equals(m) || "socks5".equals(m)) return 2;
        return 0;
    }

    private void fillJavaBox() {
        javaBox.removeAllItems();
        javaPaths.clear();
        javaBox.addItem("自动选择（按版本要求匹配）");
        javaPaths.add("");
        Profile p = win.settings().profile();
        String configured = p == null ? "" : p.javaPath;
        boolean found = configured == null || Compat.isBlank(configured);
        for (JavaFinder.JavaInfo j : JavaFinder.findAll()) {
            javaBox.addItem(j.label() + "  —  " + j.exe);
            javaPaths.add(j.exe.toString());
            if (!found && j.exe.toString().equals(configured)) {
                javaBox.setSelectedIndex(javaPaths.size() - 1);
                found = true;
            }
        }
        if (!found) {
            javaBox.addItem("自定义：" + configured);
            javaPaths.add(configured);
            javaBox.setSelectedIndex(javaPaths.size() - 1);
        }
    }

    private void chooseGameDir() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            gameDirField.setText(fc.getSelectedFile().getAbsolutePath());
            hint.setText("已选择目录，点击保存生效");
        }
    }

    private void chooseJava() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Java 可执行文件", "exe"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            JavaFinder.JavaInfo info = JavaFinder.find(f.toPath());
            if (info == null) {
                JOptionPane.showMessageDialog(this, "无法识别该文件是否为 Java 运行时");
                return;
            }
            javaBox.addItem("自定义：" + info.label() + "  —  " + f.getAbsolutePath());
            javaPaths.add(f.getAbsolutePath());
            javaBox.setSelectedIndex(javaPaths.size() - 1);
        }
    }

    private void chooseTheme() {
        Color current;
        try {
            current = Color.decode(win.settings().themeColor);
        } catch (Exception e) {
            current = Ui.accent();
        }
        Color c = JColorChooser.showDialog(this, "选择主题色", current);
        if (c == null) return;
        win.settings().themeColor = String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
        win.applyTheme();
        themeBtn.setText("主题色 " + win.settings().themeColor);
        themeBtn.refreshColors();
    }

    /** 用当前界面上的代理参数（不落盘）做一次连通性探测。 */
    private void testNetwork() {
        mcl.config.Settings s = win.settings();
        // 只在内存里临时生效，取消时把原值写回去
        String oldMode = s.proxyMode, oldHost = s.proxyHost, oldUser = s.proxyUser, oldPass = s.proxyPass;
        int oldPort = s.proxyPort, oldLimit = s.speedLimitKbps;
        if (proxyModeBox.getSelectedIndex() == 1) {
            s.proxyMode = "http";
        }
        if (proxyModeBox.getSelectedIndex() == 2) {
            s.proxyMode = "socks";
        }
        s.proxyMode = "none";
        s.proxyHost = proxyHostField.getText() == null ? "" : proxyHostField.getText().trim();
        s.proxyPort = ((Number) proxyPortSpinner.getValue()).intValue();
        s.proxyUser = proxyUserField.getText() == null ? "" : proxyUserField.getText().trim();
        s.proxyPass = new String(proxyPassField.getPassword());
        s.speedLimitKbps = ((Number) limitSpinner.getValue()).intValue();
        mcl.net.Net.apply(s);
        netHint.setText("正在测试…");
        win.pool().submit(() -> {
            String result = mcl.net.Net.probe();
            javax.swing.SwingUtilities.invokeLater(() -> {
                netHint.setText(result);
                // 测试用的代理不保留：回到已保存的配置
                mcl.net.Net.apply(win.settings());
            });
        });
        // 还原，等用户点保存才真正生效
        s.proxyMode = oldMode;
        s.proxyHost = oldHost;
        s.proxyPort = oldPort;
        s.proxyUser = oldUser;
        s.proxyPass = oldPass;
        s.speedLimitKbps = oldLimit;
    }

    private void chooseBackground() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("图片", "png", "jpg", "jpeg", "gif", "bmp"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            win.settings().backgroundImage = fc.getSelectedFile().getAbsolutePath();
            bgField.setText(win.settings().backgroundImage);
            win.applyTheme();
        }
    }

    private void newProfile() {
        String name = JOptionPane.showInputDialog(this, "配置名称（例如 1.12.2 模组）", "新建配置",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || Compat.isBlank(name)) return;
        for (Profile p : win.settings().profiles) {
            if (p.name.equals(name.trim())) {
                JOptionPane.showMessageDialog(this, "配置名已存在");
                return;
            }
        }
        Profile p = new Profile(name.trim());
        p.gameDir = ".minecraft";
        // 新配置继承当前正在用的版本：原来留空，于是新建完点什么都弹「请先在底部选择一个版本」，
        // 而底部下拉此刻还没有选中项（尤其在新机器上还没装版本时，直接把人堵死）
        Profile cur = win.settings().profile();
        if (cur != null && !Compat.isBlank(cur.selectedVersion)) {
            p.selectedVersion = cur.selectedVersion;
        }
        win.settings().profiles.add(p);
        win.settings().selectedProfile = p.name;
        win.settings().save();
        reload();
        win.refreshAll();
    }

    private void save() {
        Settings s = win.settings();
        Profile p = s.profile();
        if (p == null) return;
        p.gameDir = gameDirField.getText().trim().isEmpty() ? ".minecraft" : gameDirField.getText().trim();
        int javaIndex = javaBox.getSelectedIndex();
        p.javaPath = javaIndex >= 0 && javaIndex < javaPaths.size() ? javaPaths.get(javaIndex) : "";
        p.autoMemory = autoMemory.isSelected();
        p.maxMemory = ((Number) memorySpinner.getValue()).intValue();
        p.width = ((Number) widthSpinner.getValue()).intValue();
        p.height = ((Number) heightSpinner.getValue()).intValue();
        p.fullscreen = fullscreen.isSelected();
        boolean wasIsolated = p.isolate;
        p.isolate = isolate.isSelected();
        p.showLogWindow = showLog.isSelected();
        s.showLogWindow = showLog.isSelected();
        int si = sourceBox.getSelectedIndex();
        if (si >= 0) s.downloadSource = Source.all()[si].name();
        s.downloadThreads = ((Number) threadSpinner.getValue()).intValue();
        s.curseforgeApiKey = cfKeyField.getText() == null ? "" : cfKeyField.getText().trim();
        if (proxyModeBox.getSelectedIndex() == 1) {
            s.proxyMode = "http";
        }
        if (proxyModeBox.getSelectedIndex() == 2) {
            s.proxyMode = "socks";
        }
        s.proxyMode = "none";
        s.proxyHost = proxyHostField.getText() == null ? "" : proxyHostField.getText().trim();
        s.proxyPort = ((Number) proxyPortSpinner.getValue()).intValue();
        s.proxyUser = proxyUserField.getText() == null ? "" : proxyUserField.getText().trim();
        s.proxyPass = new String(proxyPassField.getPassword());
        s.speedLimitKbps = ((Number) limitSpinner.getValue()).intValue();
        mcl.net.Net.apply(s);
        s.backgroundImage = bgField.getText().trim();
        s.save();
        win.applyTheme();
        win.refreshAll();
        hint.setText("已保存：" + p.name + " · 目录 " + win.game().gameDir());
        win.setStatus("设置已保存");
        if (!wasIsolated && p.isolate) adoptAfterIsolateEnabled();
    }

    /**
     * 从「共用目录」切到「版本隔离」时，把共用目录里已有的存档复制进各版本目录。
     *
     * <p>不做这一步的话，用户打开隔离再进存档页，看到的就是一片空白 —— 文件还在，
     * 只是游戏改去 {@code versions/<id>/saves} 找了。复制只做一次，且**不删除**共用目录里的原件。
     */
    private void adoptAfterIsolateEnabled() {
        Game game = win.game();
        List<VersionManager.InstalledVersion> installed;
        try {
            installed = game.versions().list();
        } catch (Exception e) {
            return;
        }
        if (installed.isEmpty()) return;
        final int total = installed.size();
        win.setStatus("正在为 " + total + " 个版本准备独立目录…");
        win.pool().submit(() -> {
            int adopted = 0;
            long bytes = 0;
            for (VersionManager.InstalledVersion v : installed) {
                Isolation.Result r = game.prepareVersionDir(v.id);
                if (r.adopted()) {
                    adopted++;
                    bytes += r.bytes;
                }
            }
            final int n = adopted;
            final long size = bytes;
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (n > 0) {
                    win.setStatus("已启用版本隔离：" + n + " 个版本各拿到一份独立目录"
                            + "（共复制 " + humanSize(size) + "，共用目录里的原件保留）");
                } else {
                    win.setStatus("已启用版本隔离：每个版本使用 versions/<版本> 作为游戏目录");
                }
                win.refreshAll();
            });
        });
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
