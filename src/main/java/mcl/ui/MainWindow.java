package mcl.ui;

import mcl.util.Dirs;
import mcl.util.Compat;

import mcl.MCL;
import mcl.config.Settings;
import mcl.game.Game;
import mcl.game.GameLauncher;
import mcl.install.Installer;
import mcl.install.OptiFineInstaller;
import mcl.mod.Modrinth;
import mcl.modpack.Modpack;
import mcl.util.Log;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.nio.file.Path;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 主窗口：左侧导航 + 内容卡片 + 底部状态栏（HMCL 风格布局）。 */
public class MainWindow extends JFrame {
    private final Settings settings;
    private final Game game;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcl-worker");
        t.setDaemon(true);
        return t;
    });

    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final BackgroundPanel root = new BackgroundPanel();
    private final JComboBox<String> versionBox = new JComboBox<>();
    private final JLabel profileLabel = Ui.muted("");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JLabel statusLabel = Ui.muted("就绪");
    private final Ui.RoundButton launchBtn = (Ui.RoundButton) Ui.primary("启动游戏");
    private final Ui.NavButton[] navs = new Ui.NavButton[6];

    private HomeView homeView;
    private DownloadView downloadView;
    private ModsView modsView;
    private WorldsView worldsView;
    private AccountView accountView;
    private SettingsView settingsView;
    private JDialog logDialog;
    private JTextArea logArea;

    /** 重建版本下拉框时抑制选中事件，避免把用户的选中项改写成列表第一项。 */
    private boolean populatingVersions;
    private long lastBytesMark;
    private long lastTimeMark;
    private volatile Process running;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public MainWindow(Settings settings) {
        this.settings = settings;
        this.game = new Game(settings);
        Ui.setAccent(parseColor(settings.themeColor));
        setTitle("MCL - Minecraft 启动器 " + MCL.VERSION);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(900, 580));
        setSize(Math.max(900, settings.windowWidth), Math.max(580, settings.windowHeight));
        setLocationRelativeTo(null);
        // 开发 / 截图用：-Dmcl.topmost=true 让窗口浮在最前面，方便 Robot 截屏
        if ("true".equals(System.getProperty("mcl.topmost", ""))) setAlwaysOnTop(true);

        setContentPane(root);
        root.setLayout(new BorderLayout());
        root.loadFrom(settings);
        root.add(buildSidebar(), BorderLayout.WEST);
        root.add(buildContent(), BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                settings.windowWidth = getWidth();
                settings.windowHeight = getHeight();
                settings.save();
            }
        });
        refreshAll();
        tidyModpacksInBackground();
        // 调试 / 截图用：-Dmcl.page=download 可直接打开指定页面
        String page = System.getProperty("mcl.page", "");
        int index = 0;
        if ("download".equals(page)) index = 1;
        else if ("mods".equals(page)) index = 2;
        else if ("worlds".equals(page)) index = 3;
        else if ("account".equals(page)) index = 4;
        else if ("settings".equals(page)) index = 5;
        showPage(index);
        scheduleShot();
    }

    /**
     * 开发 / 截图用：{@code -Dmcl.shot=out.png} 会把窗口内容渲染成 PNG 再退出。
     *
     * <p>用 {@code printAll} 直接把组件树画进图片，而不是用 Robot 抓屏幕，
     * 这样别的窗口挡在前面也不影响结果，弹窗也不会混进来。
     */
    private void scheduleShot() {
        String path = System.getProperty("mcl.shot", "");
        if (path == null || path.length() == 0) return;
        int delay = 6000;
        try {
            delay = Integer.parseInt(System.getProperty("mcl.shot.delay", "6000"));
        } catch (NumberFormatException ignored) {
            // 用默认值
        }
        javax.swing.Timer timer = new javax.swing.Timer(delay, e -> {
            ((javax.swing.Timer) e.getSource()).stop();
            try {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                        getWidth(), getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                g.setColor(getBackground());
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                printAll(g);
                g.dispose();
                javax.imageio.ImageIO.write(img, "png", new java.io.File(path));
                mcl.util.Log.info("已截图 " + path + " " + img.getWidth() + "x" + img.getHeight());
            } catch (Exception ex) {
                mcl.util.Log.warn("截图失败：" + ex.getMessage());
            }
            dispose();
            System.exit(0);
        });
        timer.setRepeats(false);
        timer.start();
    }

    private Color parseColor(String s) {
        try {
            return Color.decode(s);
        } catch (Exception e) {
            return new Color(0x5C6BC0);
        }
    }

    // ---------------- 布局 ----------------

    private JPanel buildSidebar() {
        JPanel side = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color top = Ui.accent().darker();
                Color bottom = Ui.blend(Ui.accent(), Color.BLACK, 0.18f);
                g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bottom));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        side.setOpaque(false);
        side.setPreferredSize(new Dimension(204, 0));
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(26, 14, 18, 14));

        JLabel logo = Ui.label("MCL", Font.BOLD, 30f, Color.WHITE);
        logo.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel slogan = Ui.label("Minecraft Launcher", Font.PLAIN, 11.5f, new Color(255, 255, 255, 180));
        slogan.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(logo);
        side.add(Box.createVerticalStrut(4));
        side.add(slogan);
        side.add(Box.createVerticalStrut(30));

        String[] names = {"主页", "下载", "模组", "存档", "账户", "设置"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Ui.NavButton b = new Ui.NavButton(names[i]);
            b.addActionListener(e -> showPage(idx));
            navs[i] = b;
            side.add(b);
            side.add(Box.createVerticalStrut(4));
        }
        side.add(Box.createVerticalGlue());
        JLabel ver = Ui.label("v" + MCL.VERSION, Font.PLAIN, 11f, new Color(255, 255, 255, 140));
        ver.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(ver);
        return side;
    }

    private JPanel buildContent() {
        JPanel wrap = Ui.transparent();
        wrap.setLayout(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
        pages.setOpaque(false);
        homeView = new HomeView(this);
        downloadView = new DownloadView(this);
        modsView = new ModsView(this);
        worldsView = new WorldsView(this);
        accountView = new AccountView(this);
        settingsView = new SettingsView(this);
        pages.add(homeView, "home");
        pages.add(downloadView, "download");
        pages.add(modsView, "mods");
        pages.add(worldsView, "worlds");
        pages.add(accountView, "account");
        pages.add(settingsView, "settings");
        wrap.add(pages, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildStatusBar() {
        JPanel wrap = Ui.transparent();
        wrap.setLayout(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(0, 16, 14, 16));
        Ui.RoundedPanel bar = new Ui.RoundedPanel(16, new Color(255, 255, 255, 245), Ui.LINE);
        bar.setLayout(new BorderLayout(14, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 14));

        versionBox.setPreferredSize(new Dimension(210, 32));
        versionBox.setFont(Ui.font(Font.PLAIN, 13f));
        versionBox.addActionListener(e -> {
            if (populatingVersions) return;
            String id = (String) versionBox.getSelectedItem();
            if (id != null && settings.profile() != null) {
                settings.profile().selectedVersion = id;
                settings.save();
                if (modsView != null) modsView.setVersion(id);
            }
        });
        JPanel left = Ui.col(2, versionBox, profileLabel);
        bar.add(left, BorderLayout.WEST);

        progress.setValue(0);
        progress.setStringPainted(true);
        progress.setString("");
        progress.setBorderPainted(false);
        progress.setPreferredSize(new Dimension(100, 12));
        progress.setForeground(Ui.accent());
        statusLabel.setFont(Ui.font(Font.PLAIN, 12f));
        JPanel center = Ui.col(4, progress, statusLabel);
        bar.add(center, BorderLayout.CENTER);

        Ui.RoundButton logBtn = (Ui.RoundButton) Ui.ghost("日志");
        logBtn.addActionListener(e -> showLogWindow());
        launchBtn.addActionListener(e -> launchSelected());
        bar.add(Ui.row(8, logBtn, launchBtn), BorderLayout.EAST);

        wrap.add(bar, BorderLayout.CENTER);
        return wrap;
    }

    // ---------------- 公共操作 ----------------

    public Settings settings() {
        return settings;
    }

    public Game game() {
        return game;
    }

    public ExecutorService pool() {
        return pool;
    }

    public void showPage(int index) {
        String[] keys = {"home", "download", "mods", "worlds", "account", "settings"};
        cards.show(pages, keys[index]);
        for (int i = 0; i < navs.length; i++) navs[i].setNavSelected(i == index);
        if (index == 0) homeView.refresh();
        if (index == 1) downloadView.ensureLoaded();
        if (index == 2) modsView.setVersion(selectedVersion());
        if (index == 3) worldsView.setVersion(selectedVersion());
        if (index == 4) accountView.refresh();
        if (index == 5) settingsView.reload();
    }

    /** 由其它页面（如主页列表）切换版本时，同步底部下拉框与各页数据。 */
    public void selectVersion(String id) {
        if (id == null || Compat.isBlank(id)) return;
        populatingVersions = true;
        try {
            versionBox.setSelectedItem(id);
        } finally {
            populatingVersions = false;
        }
        if (settings.profile() != null) {
            settings.profile().selectedVersion = id;
            settings.save();
        }
        if (modsView != null) modsView.setVersion(id);
        if (worldsView != null) worldsView.setVersion(id);
    }

    /** 底部状态栏当前选中的版本 id（可能为 null）。 */
    public String selectedVersion() {
        Object o = versionBox.getSelectedItem();
        return o == null ? "" : o.toString();
    }

    /**
     * 老版本的整合包把加载器摆在了共享的 {@code versions/} 下，用户打开整合包文件夹
     * 看不到自己装的 Forge。启动时在后台补一次：把加载器的版本 JSON 收进实例目录。
     *
     * <p>只搬 JSON（几百 KB），不删外面那份 —— 那个加载器版本可能是用户自己装的。
     */
    private void tidyModpacksInBackground() {
        pool.submit(() -> {
            boolean changed = false;
            try {
                List<String> tidied = Modpack.tidyInstances(game.gameDir());
                if (!tidied.isEmpty()) {
                    Log.info("已把加载器私有化进整合包目录：" + String.join("、", tidied));
                    changed = true;
                }
            } catch (Exception e) {
                Log.warn("整理整合包目录失败：" + e.getMessage());
            }
            // 再收一遍「自己装成独立版本」的加载器：并进原版目录，版本列表里少几个看不懂的条目
            try {
                List<String> merged = game.migrateStandaloneLoaders();
                if (!merged.isEmpty()) {
                    Log.info("已把独立加载器版本并回原版：" + String.join("、", merged));
                    changed = true;
                }
            } catch (Exception e) {
                Log.warn("迁移独立加载器版本失败：" + e.getMessage());
            }
            if (changed) {
                SwingUtilities.invokeLater(this::refreshAll);
            }
        });
    }

    /**
     * 老配置里可能选着一个已经不存在的加载器版本（{@code 1.20.1-forge-47.4.28}）。
     *
     * <p>加载器现在并进版本目录了，那个 id 会被清掉。这时候不能直接掉到「列表第一个版本」上 ——
     * 用户点启动进的可能就是别人的整合包。把它换算成并进去的那个版本才安全。
     */
    private static String baseOf(String id) {
        String mc = mcl.game.VersionManager.idInfo(id).mcVersion;
        return mcl.util.Compat.isBlank(mc) || mc.equals(id) ? "" : mc;
    }

    public void refreshAll() {
        List<String> ids = game.versions().list().stream().map(v -> v.id).collect(java.util.stream.Collectors.toList());
        String keep = (String) versionBox.getSelectedItem();
        String wanted = settings.profile() == null ? null : settings.profile().selectedVersion;
        populatingVersions = true;
        try {
            versionBox.removeAllItems();
            for (String id : ids) versionBox.addItem(id);
            String target = null;
            if (wanted != null && ids.contains(wanted)) target = wanted;
            else if (wanted != null && ids.contains(baseOf(wanted))) target = baseOf(wanted);
            else if (keep != null && ids.contains(keep)) target = keep;
            else if (!ids.isEmpty()) target = ids.get(0);
            if (target != null) versionBox.setSelectedItem(target);
            // 选中项被替换（例如版本被删除）时，把新选中项写回配置
            if (settings.profile() != null && target != null && !target.equals(wanted)) {
                settings.profile().selectedVersion = target;
                settings.save();
            }
        } finally {
            populatingVersions = false;
        }
        String accountName = settings.account() == null ? "未选择账户" : settings.account().username;
        profileLabel.setText("配置：" + (settings.profile() == null ? "Default" : settings.profile().name)
                + "  ·  " + accountName);
        launchBtn.setEnabled(!ids.isEmpty());
        // 一个版本都没装时，底部下拉是空的，光把启动按钮变灰没人看得懂。
        // 直接说清楚下一步该干什么 —— 也别让人去点一个空下拉。
        if (ids.isEmpty()) setStatus("还没有安装任何游戏版本：去「下载」页装一个，或直接装整合包");
        if (homeView != null) homeView.refresh();
        if (modsView != null) modsView.setVersion(selectedVersion());
    }

    public void setStatus(String text) {
        statusLabel.setText(text);
    }

    public void setProgress(int done, int total, long bytes, long bytesTotal) {
        long now = System.currentTimeMillis();
        if (bytesTotal > 0) {
            int v = (int) Math.min(1000, bytes * 1000 / bytesTotal);
            progress.setIndeterminate(false);
            progress.setValue(v);
            progress.setString(String.format("%.1f%%  %s / %s  %s  (%d/%d)",
                    v / 10.0, Ui.bytes(bytes), Ui.bytes(bytesTotal), speedText(bytes, now), done, total));
        } else {
            progress.setIndeterminate(true);
            progress.setString(done + "/" + total);
        }
    }

    /** 下载速度（滑动窗口估计，避免数字乱跳）。 */
    private String speedText(long bytes, long now) {
        if (lastBytesMark == 0) {
            lastBytesMark = Math.max(1, bytes);
            lastTimeMark = now;
            return "";
        }
        if (now - lastTimeMark < 400) return "";
        long db = bytes - lastBytesMark;
        long dt = Math.max(1, now - lastTimeMark);
        double bps = db * 1000.0 / dt;
        lastBytesMark = bytes;
        lastTimeMark = now;
        if (bps < 1024) return "";
        if (bps < 1048576) return String.format("%.0f KB/s", bps / 1024);
        return String.format("%.1f MB/s", bps / 1048576);
    }

    public void progressIdle() {
        progress.setIndeterminate(false);
        progress.setValue(0);
        progress.setString("");
        lastBytesMark = 0;
        lastTimeMark = 0;
    }

    /** 安装进度 -> 状态栏。 */
    private Installer.Listener progressListener(long[] lastUpdate) {
        return new Installer.Listener() {
            @Override
            public void onStage(String stage) {
                SwingUtilities.invokeLater(() -> setStatus(stage));
            }

            @Override
            public void onProgress(int done, int total, long bytes, long bytesTotal, String current) {
                long now = System.currentTimeMillis();
                if (now - lastUpdate[0] < 120 && done < total) return;
                lastUpdate[0] = now;
                SwingUtilities.invokeLater(() -> {
                    setProgress(done, total, bytes, bytesTotal);
                    setStatus("下载 " + current);
                });
            }
        };
    }

    /** 安装一个版本（异步，进度显示在状态栏）。 */
    public void installVersion(String versionId) {
        if (!busy.compareAndSet(false, true)) {
            setStatus("已有任务在进行中");
            return;
        }
        setStatus("正在准备安装 " + versionId);
        progress.setIndeterminate(true);
        progress.setString("准备中…");
        pool.submit(() -> {
            try {
                game.install(versionId, true, progressListener(new long[]{0}));
                SwingUtilities.invokeLater(() -> {
                    settings.profile().selectedVersion = versionId;
                    settings.save();
                    refreshAll();
                    setStatus(versionId + " 安装完成，可以启动了");
                    progressIdle();
                    downloadView.markInstalled(versionId);
                });
            } catch (Exception e) {
                Log.error("安装 " + versionId + " 失败", e);
                SwingUtilities.invokeLater(() -> {
                    progressIdle();
                    setStatus("安装失败：" + e.getMessage());
                    JOptionPane.showMessageDialog(this, e.getMessage(), "安装失败", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                busy.set(false);
            }
        });
    }

    /** 安装 Fabric 加载器（必要时先装原版）。 */
    public void installFabric(String gameVersion, String loaderVersion) {
        installLoader("Fabric", gameVersion, loaderVersion);
    }

    /** 安装任意模组加载器：Fabric / Quilt / Forge / NeoForge。 */
    public void installLoader(String kind, String gameVersion, String loaderVersion) {
        installLoader(kind, gameVersion, loaderVersion, null);
    }

    /**
     * @param targetInstance 整合包实例 id；非空表示给它换加载器 —— 装完把加载器收进实例目录，
     *                       并改它的 inheritsFrom，而不是在 versions/ 里再多摆一个版本
     */
    public void installLoader(String kind, String gameVersion, String loaderVersion, String targetInstance) {
        if (!busy.compareAndSet(false, true)) {
            setStatus("已有任务在进行中");
            return;
        }
        setStatus("正在安装 " + kind + " " + loaderVersion + "（" + gameVersion + "）");
        progress.setIndeterminate(true);
        progress.setString("准备中…");
        pool.submit(() -> {
            try {
                Installer.Listener listener = progressListener(new long[]{0});
                java.util.Set<String> before = new java.util.HashSet<String>();
                for (mcl.game.VersionManager.InstalledVersion v : game.versions().list()) {
                    before.add(v.id);
                }
                String id;
                if (targetInstance != null && !targetInstance.isEmpty()) {
                    // 整合包：先装到临时目录，再私有化进它自己，最后改 inheritsFrom
                    if ("Quilt".equals(kind)) {
                        id = game.installModLoader(gameVersion, loaderVersion, true, listener);
                    } else if ("Forge".equals(kind)) {
                        id = game.installForge(gameVersion, loaderVersion, false, listener);
                    } else if ("NeoForge".equals(kind)) {
                        id = game.installForge(gameVersion, loaderVersion, true, listener);
                    } else {
                        id = game.installModLoader(gameVersion, loaderVersion, false, listener);
                    }
                    Modpack.absorbLoader(game.gameDir(), targetInstance, id, !before.contains(id));
                    Modpack.retargetInstance(game.gameDir(), targetInstance, id);
                    id = targetInstance;
                } else {
                    // 普通版本：加载器并进它自己的目录，版本列表里还是原来那一个
                    id = game.installLoaderInto(gameVersion, kind, loaderVersion, listener);
                }
                String result = id;
                final String shown = result;
                SwingUtilities.invokeLater(() -> {
                    settings.profile().selectedVersion = shown;
                    settings.save();
                    refreshAll();
                    setStatus(targetInstance != null && !targetInstance.isEmpty()
                            ? shown + " 的加载器已更换为 " + kind + " " + loaderVersion
                            : shown + " 安装完成，可以启动了");
                    progressIdle();
                    downloadView.markInstalled(gameVersion);
                    downloadView.markInstalled(shown);
                });
            } catch (Exception e) {
                Log.error("安装 " + kind + " 失败", e);
                SwingUtilities.invokeLater(() -> {
                    progressIdle();
                    setStatus("安装失败：" + e.getMessage());
                    JOptionPane.showMessageDialog(this, e.getMessage(), "安装失败", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                busy.set(false);
            }
        });
    }

    /** 一段可返回"完成提示"的后台任务。 */
    public interface TaskBody {
        String run(Installer.Listener listener) throws Exception;
    }

    /** 统一的后台任务：占用 busy、驱动状态栏进度、失败弹窗。 */
    public void runTask(String startStatus, TaskBody body) {
        if (!busy.compareAndSet(false, true)) {
            setStatus("已有任务在进行中");
            return;
        }
        setStatus(startStatus);
        progress.setIndeterminate(true);
        progress.setString("准备中…");
        pool.submit(() -> {
            try {
                String done = body.run(progressListener(new long[]{0}));
                SwingUtilities.invokeLater(() -> {
                    progressIdle();
                    setStatus(done);
                    refreshAll();
                });
            } catch (Exception e) {
                Log.error(startStatus + " 失败", e);
                SwingUtilities.invokeLater(() -> {
                    progressIdle();
                    setStatus("失败：" + e.getMessage());
                    JOptionPane.showMessageDialog(this, e.getMessage(), "操作失败", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                busy.set(false);
            }
        });
    }

    /** 从 Modrinth 下载模组 / 资源包 / 光影到当前版本。 */
    public void downloadModrinth(String versionId, String slug, String projectType) {
        downloadFrom(versionId, slug, projectType, mcl.mod.ModSource.MODRINTH);
    }

    /** 从指定内容源（Modrinth / CurseForge）安装模组、资源包或光影。 */
    public void downloadFrom(String versionId, String slug, String projectType, mcl.mod.ModSource src) {
        mcl.mod.ModSource source = src == null ? mcl.mod.ModSource.MODRINTH : src;
        runTask("从 " + source.label + " 安装 " + slug, l -> {
            mcl.mod.ModInstaller.Result result = game.installModrinth(versionId, slug, projectType,
                    source, true, l);
            return Modrinth.typeLabel(projectType) + " 安装完成：" + result.summary()
                    + " → " + game.contentDir(versionId, projectType);
        });
    }

    /** 从 Modrinth 安装整合包：下载 .mrpack 后走整合包导入流程。 */
    public void installModpackFromModrinth(String slug, String mcVersion, String loader) {
        installModpackFrom(mcl.mod.ModSource.MODRINTH, slug, mcVersion, loader);
    }

    /**
     * 从指定源安装整合包。
     *
     * <p>Modrinth 上是 {@code .mrpack}，下载后直接按清单逐项拉取；
     * CurseForge 上是 {@code .zip}（里面有自己的 manifest），下载完交给
     * 本地整合包导入流程，让用户确认版本 / 加载器。
     */
    public void installModpackFrom(mcl.mod.ModSource src, String slug, String mcVersion, String loader) {
        final mcl.mod.ModSource source = src == null ? mcl.mod.ModSource.MODRINTH : src;
        runTask("查询整合包 " + slug, l -> {
            Modrinth.VersionInfo v = mcl.mod.ModApi.bestVersion(source, slug,
                    Compat.isBlank(mcVersion) ? null : mcVersion, null);
            if (v == null) throw new java.io.IOException(source.label + " 上没有该整合包可用版本");
            Modrinth.FileInfo file = v.primaryFile();
            boolean mrpack = file != null && file.filename.toLowerCase().endsWith(".mrpack");
            if (!mrpack) {
                // 先找 .mrpack；CurseForge 的整包是 .zip，退而取主文件
                for (Modrinth.FileInfo f : v.files) {
                    if (f.filename.toLowerCase().endsWith(".mrpack")) {
                        file = f;
                        mrpack = true;
                        break;
                    }
                }
            }
            if (file == null) throw new java.io.IOException("该版本没有提供可下载的整合包文件");
            Path tmp = settings.launcherDir().resolve("cache").resolve("modpacks").resolve(file.filename);
            l.onStage("下载整合包 " + file.filename);
            mcl.mod.ModInstaller.downloadFile(file.url, tmp, file.sha1, file.size, l);
            if (mrpack) {
                String id = game.importModpack(tmp, "", null, null, l);
                settings.profile().selectedVersion = id;
                settings.save();
                return "整合包已安装：" + id;
            }
            // CurseForge 的整包：交给本地导入流程确认版本与加载器
            final Path zip = tmp;
            javax.swing.SwingUtilities.invokeLater(() -> importModpack(zip));
            return "整合包已下载：" + file.filename + "（请在弹出的窗口里确认版本与加载器）";
        });
    }

    /** 选择本地整合包文件并安装。 */
    public void importModpackDialog() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("选择整合包（.zip / .mrpack）");
        chooser.setFileFilter(new FileNameExtensionFilter("整合包 (*.zip, *.mrpack)", "zip", "mrpack"));
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        importModpack(chooser.getSelectedFile().toPath());
    }

    /** 安装指定整合包文件。 */
    public void importModpack(Path zip) {
        Modpack.Info info;
        try {
            info = Modpack.inspect(zip);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "无法读取整合包：\n" + e.getMessage(),
                    "导入失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JTextField mcField = new JTextField(info.mcVersion == null ? "" : info.mcVersion, 14);
        JComboBox<String> kindBox = new JComboBox<>(
                new String[]{"按整合包自动", "vanilla（原版）", "fabric", "quilt", "forge", "neoforge"});
        kindBox.setSelectedIndex(0);
        String format;
        if (info.format == Modpack.Format.MRPACK) {
            format = "Modrinth (.mrpack)";
        } else if (info.format == Modpack.Format.CURSEFORGE) {
            format = "CurseForge (manifest.json)";
        } else {
            format = "裸压缩包（直接解压）";
        }
        java.util.List<Object> msg = new java.util.ArrayList<>();
        msg.add("名称：" + info.name + "  版本：" + info.version);
        msg.add("格式：" + format + "  文件数：" + info.fileCount);
        if (info.format == Modpack.Format.CURSEFORGE && Compat.isBlank(settings.curseforgeApiKey)) {
            msg.add("提示：还没填 CurseForge API Key，本次只安装加载器与配置文件。");
        }
        msg.add("游戏版本（留空则按整合包清单）：");
        msg.add(mcField);
        msg.add("加载器：");
        msg.add(kindBox);
        if (JOptionPane.showConfirmDialog(this, msg.toArray(), "导入整合包 " + info.name,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String mc = mcField.getText() == null ? "" : mcField.getText().trim();
        String kind = kindBox.getSelectedIndex() == 0 ? null
                : ((String) kindBox.getSelectedItem()).split("（")[0].trim();
        runTask("安装整合包 " + info.name, l -> {
            String id = game.importModpack(zip, mc, kind, null, l);
            settings.profile().selectedVersion = id;
            settings.save();
            return "整合包已安装：" + id + "（已按实例隔离模组与存档）";
        });
    }

    /** 安装 OptiFine。 */
    /** 原版装 OptiFine：官方安装器另开一个带 OptiFine 的版本。 */
    public void installOptiFine(String mcVersion, String edition) {
        runTask("安装 OptiFine " + mcVersion, l -> {
            String id = game.installOptiFine(mcVersion, edition, l);
            settings.profile().selectedVersion = id;
            settings.save();
            return "OptiFine 安装完成：" + id;
        });
    }

    /**
     * 给已装版本加 OptiFine。
     *
     * <p>OptiFine 是模组：目标版本有 Forge / NeoForge / Fabric / Quilt 时，
     * 只是往它的 mods 里放一个 jar，已有模组不受影响；只有原版才另开版本。
     */
    public void installOptiFineInto(String versionId, String edition) {
        runTask("安装 OptiFine " + versionId, l -> {
            String result = game.installOptiFineInto(versionId, edition, l);
            if (game.versions().isInstalled(result)) {
                settings.profile().selectedVersion = result;
                settings.save();
                return "OptiFine 安装完成：" + result;
            }
            return result;
        });
    }

    /** 导出当前版本为整合包。 */
    public void exportModpackDialog() {
        String id = selectedVersion();
        if (id == null || Compat.isBlank(id)) {
            JOptionPane.showMessageDialog(this, "请先在底部选择一个版本");
            return;
        }
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("导出整合包");
        chooser.setSelectedFile(new File(id + ".zip"));
        chooser.setFileFilter(new FileNameExtensionFilter("整合包 (*.zip)", "zip"));
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath();
        if (!target.toString().toLowerCase().endsWith(".zip")) target = java.nio.file.Paths.get(target + ".zip");
        String name = JOptionPane.showInputDialog(this, "整合包名称：", id);
        if (name == null || Compat.isBlank(name)) name = id;
        String versionName = JOptionPane.showInputDialog(this, "整合包版本号：", "1.0");
        if (versionName == null || Compat.isBlank(versionName)) versionName = "1.0";
        String author = settings.account() == null ? "MCL" : settings.account().username;
        String finalName = name;
        String finalVersion = versionName;
        Path finalTarget = target;
        runTask("导出整合包", l -> {
            Path out = game.exportModpack(id, finalTarget, finalName, finalVersion, author, l);
            return "整合包已导出：" + out;
        });
    }

    /** 重命名当前版本。 */
    public void renameVersionDialog() {
        String id = selectedVersion();
        if (id == null || Compat.isBlank(id)) return;
        String next = JOptionPane.showInputDialog(this, "新的版本 id：", id);
        if (next == null) return;
        String newId = next.trim();
        if (newId.isEmpty() || newId.equals(id)) return;
        runTask("重命名版本 " + id, l -> {
            game.versions().rename(id, newId);
            settings.profile().selectedVersion = newId;
            settings.save();
            return "已重命名为 " + newId;
        });
    }

    /** 复制当前版本。 */
    public void copyVersionDialog() {
        String id = selectedVersion();
        if (id == null || Compat.isBlank(id)) return;
        String next = JOptionPane.showInputDialog(this, "新版本的 id：", id + "-copy");
        if (next == null) return;
        String newId = next.trim();
        if (newId.isEmpty() || newId.equals(id)) return;
        runTask("复制版本 " + id, l -> {
            game.versions().copy(id, newId);
            settings.profile().selectedVersion = newId;
            settings.save();
            return "已复制为 " + newId;
        });
    }

    /** 非 0 退出时做一次本地崩溃分析，把结论摆给用户，而不是让人自己翻日志。 */
    private void showCrashReport(String versionId, int exitCode) {
        pool.submit(() -> {
            try {
                mcl.meta.VersionMeta meta = null;
                try {
                    meta = game.versions().load(versionId);
                } catch (Exception ignored) {
                }
                mcl.game.CrashAnalyzer.Report report =
                        mcl.game.CrashAnalyzer.analyze(game.runDir(versionId), exitCode, meta);
                SwingUtilities.invokeLater(() -> {
                    String text = report.toText();
                    setStatus("游戏异常退出：" + report.summary());
                    Object[] options = {"打开日志目录", "关闭"};
                    int r = JOptionPane.showOptionDialog(this,
                            new javax.swing.JScrollPane(Ui.textArea(text, 620, 320)),
                            "崩溃分析 · " + versionId,
                            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
                    if (r == 0) openDir(game.runDir(versionId).resolve("logs"));
                });
            } catch (Exception e) {
                Log.warn("崩溃分析失败：" + e.getMessage());
            }
        });
    }

    /** 打开一个目录（不存在则先创建）。 */
    public void openDir(java.nio.file.Path dir) {
        try {
            Dirs.ensure(dir);
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "打不开目录：\n" + dir + "\n" + e.getMessage());
        }
    }

    public void launchSelected() {
        launchSelected(null);
    }

    /**
     * @param server 非空时启动后直接进服（{@code host} 或 {@code host:port}）
     */
    public void launchSelected(String server) {
        String id = (String) versionBox.getSelectedItem();
        if (id == null) {
            JOptionPane.showMessageDialog(this, "还没有安装任何版本，请先到「下载」页面安装。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (running != null && running.isAlive()) {
            setStatus("游戏已经在运行中");
            return;
        }
        if (settings.account() == null) {
            JOptionPane.showMessageDialog(this, "请先在「账户」页面添加一个离线账户。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            showPage(4);
            return;
        }
        setStatus("正在启动 " + id + "…");
        progress.setIndeterminate(true);
        pool.submit(() -> {
            try {
                Process p = game.launch(id, new GameLauncher.Listener() {
                    @Override
                    public void onLog(String line) {
                        appendLog(line);
                    }

                    @Override
                    public void onExit(int code) {
                        running = null;
                        SwingUtilities.invokeLater(() -> {
                            setStatus("游戏已退出（代码 " + code + "）");
                            launchBtn.setEnabled(true);
                        });
                        if (code != 0) showCrashReport(id, code);
                    }
                }, new Game.Stage() {
                    @Override
                    public void onStage(String stage) {
                        SwingUtilities.invokeLater(() -> {
                            setStatus(stage);
                            progress.setIndeterminate(true);
                            progress.setString("准备中…");
                        });
                    }

                    @Override
                    public void onProgress(int done, int total, long bytes, long bytesTotal, String file) {
                        SwingUtilities.invokeLater(() -> setProgress(done, total, bytes, bytesTotal));
                    }
                }, server);
                running = p;
                SwingUtilities.invokeLater(() -> {
                    progressIdle();
                    setStatus("游戏已启动，日志见「日志」窗口");
                });
            } catch (Exception e) {
                Log.error("启动失败", e);
                SwingUtilities.invokeLater(() -> {
                    progressIdle();
                    setStatus("启动失败：" + e.getMessage());
                    JOptionPane.showMessageDialog(this, e.getMessage(), "启动失败", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    public void applyTheme() {
        Ui.setAccent(parseColor(settings.themeColor));
        root.loadFrom(settings);
        recolor(root);
        root.repaint();
        progress.setForeground(Ui.accent());
        launchBtn.refreshColors();
        if (homeView != null) homeView.refresh();
    }

    private static void recolor(Container c) {
        for (Component comp : c.getComponents()) {
            if (comp instanceof Ui.RoundButton) ((Ui.RoundButton) comp).refreshColors();
            if (comp instanceof Container) recolor((Container) comp);
        }
    }

    public void showLogWindow() {
        if (logDialog == null) {
            logDialog = new JDialog(this, "运行日志", false);
            logArea = new JTextArea();
            logArea.setEditable(false);
            logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
            logArea.setBackground(new Color(0x1E1F26));
            logArea.setForeground(new Color(0xD8DBE6));
            for (String line : Log.buffer()) logArea.append(line + "\n");
            JScrollPane sp = new JScrollPane(logArea);
            logDialog.setContentPane(sp);
            logDialog.setSize(820, 480);
            logDialog.setLocationRelativeTo(this);
            Log.addListener(line -> SwingUtilities.invokeLater(() -> {
                if (logArea != null) {
                    logArea.append(line + "\n");
                    if (logArea.getLineCount() > 3000) {
                        try {
                            logArea.replaceRange("", 0, logArea.getLineStartOffset(500));
                        } catch (Exception ignored) {
                        }
                    }
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                }
            }));
        }
        logDialog.setVisible(true);
    }

    private void appendLog(String line) {
        if (logArea != null && logDialog != null && logDialog.isVisible()) {
            SwingUtilities.invokeLater(() -> logArea.append("[游戏] " + line + "\n"));
        }
    }

    /**
     * 打开当前选中版本的目录。
     *
     * <p>开版本隔离后 mods / saves 在 {@code versions/<id>} 里，再打开 {@code .minecraft}
     * 会让用户以为「我的模组不见了」。所以优先开选中版本的运行目录。
     */
    public void openGameDir() {
        String id = settings.profile() == null ? "" : settings.profile().selectedVersion;
        if (Compat.isBlank(id)) {
            openDir(game.gameDir());
            return;
        }
        openDir(game.runDir(id));
    }
}
