package mcl.ui;

import mcl.config.Account;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JPasswordField;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.nio.file.Path;

/** 账户页：离线账户管理（正版登录在后续里程碑接入）。 */
public class AccountView extends JPanel {
    private final MainWindow win;
    private final DefaultListModel<Account> model = new DefaultListModel<>();
    private final JList<Account> list = new JList<>(model);
    private final JTextField nameField = new JTextField();
    private final JLabel hint = Ui.muted("使用离线账户可以直接进入单机与离线服务器");
    private final SkinPreview preview = new SkinPreview();
    private final JLabel skinInfo = Ui.muted("皮肤与披风会在联网后自动缓存");
    /** 新建账户时选皮肤（放在输入框右边，创建前就定好）。 */
    private final SkinPicker newSkin = new SkinPicker(false);
    private mcl.auth.ProfileSkins.Skin skinMeta;

    public AccountView(MainWindow win) {
        this.win = win;
        setOpaque(false);
        setLayout(new BorderLayout(0, 12));

        JPanel header = Ui.transparent();
        header.setLayout(new BorderLayout());
        header.add(Ui.col(4, Ui.title("账户"),
                Ui.subtitle("离线账户使用本地生成的 UUID，皮肤需正版登录后生效")), BorderLayout.WEST);
        add(header, BorderLayout.NORTH);

        Ui.RoundedPanel card = (Ui.RoundedPanel) Ui.card();
        card.setLayout(new BorderLayout(0, 10));

        nameField.setPreferredSize(new Dimension(220, 34));
        // BoxLayout 会按容器高度拉伸子组件，不封住 max 的话输入框会变成一条竖条
        nameField.setMaximumSize(new Dimension(Short.MAX_VALUE, 34));
        nameField.setMinimumSize(new Dimension(120, 34));
        nameField.putClientProperty("JTextField.placeholderText", "角色名：3~16 位英文、数字或下划线，例如 Steve");
        Ui.RoundButton add = (Ui.RoundButton) Ui.primary("添加离线账户");
        add.addActionListener(e -> addAccount());
        nameField.addActionListener(e -> addAccount());
        Ui.RoundButton msBtn = (Ui.RoundButton) Ui.primary("微软正版登录");
        msBtn.addActionListener(e -> loginMicrosoft());
        Ui.RoundButton authlibBtn = (Ui.RoundButton) Ui.ghost("外置登录（皮肤站）");
        authlibBtn.addActionListener(e -> loginAuthlib());
        // 皮肤选择器摆在输入框右边：不额外占垂直空间，右侧预览才不会被压扁
        JLabel skinTip = Ui.label("只改启动器显示", Font.PLAIN, 10.5f, Ui.MUTED);
        skinTip.setAlignmentX(Component.LEFT_ALIGNMENT);
        skinTip.setToolTipText("离线账户进游戏后的模型由 UUID 决定，这里只影响启动器里的预览");
        JLabel skinLabel = Ui.muted("新角色皮肤");
        skinLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        newSkin.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel skinCol = Ui.col(6, skinLabel, newSkin, skinTip);
        skinCol.add(Box.createVerticalGlue());

        JPanel row1 = Ui.row(10, nameField, add, Box.createHorizontalGlue());
        row1.setMaximumSize(new Dimension(Short.MAX_VALUE, 36));
        JPanel row2 = Ui.row(8, Ui.muted("也可以："), msBtn, authlibBtn, Box.createHorizontalGlue());
        row2.setMaximumSize(new Dimension(Short.MAX_VALUE, 40));
        JPanel left = Ui.col(10, row1, row2);
        // BorderLayout 会把 CENTER 撑满高度，不压住的话两行会被拉散
        left.add(Box.createVerticalGlue());
        JPanel top = Ui.transparent();
        top.setLayout(new BorderLayout(20, 0));
        top.add(left, BorderLayout.CENTER);
        top.add(skinCol, BorderLayout.EAST);
        card.add(top, BorderLayout.NORTH);

        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(62);
        list.setCellRenderer(new Renderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadPreview(list.getSelectedValue());
        });
        card.add(Ui.scroll(list), BorderLayout.CENTER);

        Ui.RoundButton use = (Ui.RoundButton) Ui.ghost("设为当前账户");
        use.addActionListener(e -> selectAccount());
        Ui.RoundButton del = (Ui.RoundButton) Ui.ghost("删除账户");
        del.addActionListener(e -> deleteAccount());
        Ui.RoundButton skinBtn = (Ui.RoundButton) Ui.ghost("上传皮肤");
        skinBtn.addActionListener(e -> uploadSkin());
        Ui.RoundButton repick = (Ui.RoundButton) Ui.ghost("换皮肤");
        repick.addActionListener(e -> changeSkin());
        Ui.RoundButton rename = (Ui.RoundButton) Ui.ghost("改名");
        rename.setToolTipText("离线账户的角色名含中文会进不了世界，点这里换成英文名");
        rename.addActionListener(e -> renameAccount());
        card.add(Ui.row(8, hint, Box.createHorizontalGlue(), rename, repick, skinBtn, del, use), BorderLayout.SOUTH);
        card.add(buildPreviewPanel(), BorderLayout.EAST);
        add(card, BorderLayout.CENTER);
    }

    /** 右侧：皮肤小人 + 披风状态。 */
    private JPanel buildPreviewPanel() {
        JPanel right = Ui.transparent();
        right.setLayout(new javax.swing.BoxLayout(right, javax.swing.BoxLayout.Y_AXIS));
        right.setPreferredSize(new Dimension(212, 0));
        right.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));

        preview.setPreferredSize(new Dimension(200, 220));
        preview.setMaximumSize(new Dimension(220, 232));
        preview.setAlignmentX(Component.LEFT_ALIGNMENT);

        skinInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        skinInfo.setFont(Ui.font(Font.PLAIN, 11.5f));

        Ui.RoundButton refresh = (Ui.RoundButton) Ui.ghost("刷新皮肤");
        refresh.addActionListener(e -> refreshSkin());
        Ui.RoundButton useSkin = (Ui.RoundButton) Ui.ghost("导入本地皮肤");
        useSkin.setToolTipText("选一张 64×64 的 PNG，会存到启动器目录的 skins/ 里，下次打开还在");
        useSkin.addActionListener(e -> useLocalSkin());
        JPanel btns = Ui.row(8, refresh, useSkin);
        btns.setAlignmentX(Component.LEFT_ALIGNMENT);

        right.add(preview);
        right.add(Box.createVerticalStrut(6));
        right.add(skinInfo);
        right.add(Box.createVerticalStrut(8));
        right.add(btns);
        return right;
    }

    public void refresh() {
        SwingUtilities.invokeLater(() -> {
            model.clear();
            for (Account a : win.settings().accounts) model.addElement(a);
            Account current = win.settings().account();
            if (current != null) {
                for (int i = 0; i < model.size(); i++) {
                    if (model.get(i).username.equals(current.username)) list.setSelectedIndex(i);
                }
            } else if (!model.isEmpty()) {
                list.setSelectedIndex(0);
            }
            hint.setText(win.settings().accounts.isEmpty()
                    ? "还没有账户，先添加一个离线账户"
                    : "当前账户：" + (win.settings().account() == null ? "未选择" : win.settings().account().username));
            Account sel = list.getSelectedValue();
            loadPreview(sel != null ? sel : win.settings().account());
        });
    }

    // ---------------- 皮肤预览 ----------------

    private void loadPreview(Account a) {
        if (a == null) {
            preview.clear();
            skinInfo.setText("还没有账户");
            skinMeta = null;
            return;
        }
        boolean offline = "offline".equals(a.type);
        java.nio.file.Path dir = win.settings().launcherDir();
        // 本地导入的皮肤优先级最高：用户自己选的图，不能被"刷新到的缓存"盖掉
        java.awt.image.BufferedImage skin = offline ? mcl.auth.ProfileSkins.localSkin(dir, a.uuid) : null;
        boolean local = skin != null;
        java.awt.image.BufferedImage cape = mcl.auth.ProfileSkins.cachedCape(dir, a.uuid);
        skinMeta = mcl.auth.ProfileSkins.cachedMeta(dir, a.uuid);
        if (skin == null) skin = mcl.auth.ProfileSkins.cachedSkin(dir, a.uuid);
        boolean builtin = false;
        if (skin == null && offline) {
            skin = mcl.auth.ProfileSkins.defaultSkin(a.uuid, a.skinModel);
            builtin = true;
        }
        String sub = a.typeLabel() + (local ? " · 本地皮肤" : "")
                + (skinMeta != null && skinMeta.hasCape() ? " · 披风" : "");
        preview.setSkin(skin, cape, a.username, sub);
        if (skin == null) {
            skinInfo.setText("没有缓存，点「刷新皮肤」");
        } else if (local) {
            // 现在游戏内也会生效（启动器临时顶替 Yggdrasil 服务下发这张纹理），
            // 所以这里不再写「游戏内仍是 Steve」——那句话已经过时了。
            skinInfo.setText("本地导入 · 游戏内生效"
                    + (mcl.auth.ProfileSkins.isSlim(a.uuid) ? "（Alex）" : "（Steve）"));
        } else if (builtin) {
            skinInfo.setText(builtinLabel(a));
        } else {
            String model = skinMeta == null ? "" : " · " + ("slim".equals(skinMeta.model) ? "Alex" : "Steve");
            String when = skinMeta == null || skinMeta.fetchedAt == 0 ? ""
                    : " · " + new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(skinMeta.fetchedAt));
            skinInfo.setText(skin.getWidth() + "x" + skin.getHeight() + model + when);
        }
    }

    /**
     * 离线账户的皮肤说明。
     *
     * <p>选的和 UUID 算出来的一样，就是"和游戏内一致"；不一样的话必须说出来 ——
     * 否则用户会以为进游戏也是这个模型，白高兴一场。
     */
    private static String builtinLabel(Account a) {
        String picked = mcl.auth.ProfileSkins.normalizeModel(a.skinModel);
        boolean autoSlim = mcl.auth.ProfileSkins.isSlim(a.uuid);
        String autoName = autoSlim ? "Alex" : "Steve";
        if (picked.isEmpty()) return "按 UUID 自动选 · " + autoName;
        boolean pickedSlim = "slim".equals(picked);
        String pickedName = pickedSlim ? "Alex" : "Steve";
        if (pickedSlim == autoSlim) return "内置 " + pickedName + " · 与游戏内一致";
        return "内置 " + pickedName + " · 游戏内仍是 " + autoName;
    }

    /** 给已有离线账户换 Steve / Alex，或导入本地皮肤。 */
    private void changeSkin() {
        Account a = list.getSelectedValue();
        if (a == null) {
            JOptionPane.showMessageDialog(this, "请先在列表里选一个账户");
            return;
        }
        if (!"offline".equals(a.type)) {
            JOptionPane.showMessageDialog(this, a.username + " 是" + a.typeLabel() + "账户，\n"
                    + "皮肤模型由服务器下发，不能在本地换。\n\n"
                    + "想自己定皮肤和模型，用「外置登录（皮肤站）」登录一个小皮肤站即可。");
            return;
        }
        boolean slim = mcl.auth.ProfileSkins.isSlim(a.uuid, a.skinModel);
        String auto = mcl.auth.ProfileSkins.modelLabel(
                mcl.auth.ProfileSkins.isSlim(a.uuid) ? "slim" : "classic");
        boolean hasLocal = mcl.auth.ProfileSkins.hasLocalSkin(win.settings().launcherDir(), a.uuid);
        java.util.List<String> opts = new java.util.ArrayList<String>();
        opts.add("Steve（经典）");
        opts.add("Alex（纤细）");
        opts.add("导入本地皮肤…");
        if (hasLocal) opts.add("清除本地皮肤");
        opts.add("取消");
        int def = hasLocal ? opts.size() - 1 : (slim ? 1 : 0);
        int r = JOptionPane.showOptionDialog(this,
                "给 " + a.username + " 选皮肤：\n\n"
                        + (hasLocal
                        ? "现在用的是你导入的本地皮肤，Steve / Alex 决定它按哪种模型显示，\n"
                                + "游戏内也会跟着变。"
                        : "这个账户的 UUID 决定了游戏内模型是 " + auto + "，\n"
                                + "光选 Steve / Alex 只改启动器预览；\n"
                                + "导入本地皮肤之后，游戏内才会用上你自己选的那张。"),
                "换皮肤", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                opts.toArray(), opts.get(def));
        if (r < 0 || r >= opts.size()) return;
        String picked = opts.get(r);
        if ("导入本地皮肤…".equals(picked)) {
            useLocalSkin();
            return;
        }
        if ("清除本地皮肤".equals(picked)) {
            clearLocalSkin();
            return;
        }
        if ("取消".equals(picked)) return;
        a.skinModel = r == 0 ? "classic" : "slim";
        win.settings().save();
        loadPreview(a);
        list.repaint();
        if (hasLocal) {
            win.setStatus(a.username + " 已换成 " + mcl.auth.ProfileSkins.modelLabel(a.skinModel)
                    + "（本地皮肤按这个模型显示）");
        } else {
            win.setStatus(a.username + " 已切换为 " + mcl.auth.ProfileSkins.modelLabel(a.skinModel));
        }
    }

    /** 联网刷新皮肤（微软 / 外置账户）。 */
    private void refreshSkin() {
        Account a = list.getSelectedValue();
        if (a == null) {
            JOptionPane.showMessageDialog(this, "请先选择一个账户");
            return;
        }
        if ("offline".equals(a.type)) {
            JOptionPane.showMessageDialog(this, "离线账户没有远端皮肤可以刷新。\n\n"
                    + "想换 Steve / Alex 点下面的「换皮肤」；\n"
                    + "想用自己画的皮肤，得用「外置登录（皮肤站）」登录一个小皮肤站。");
            return;
        }
        win.setStatus("正在获取 " + a.username + " 的皮肤…");
        win.pool().submit(() -> {
            try {
                if ("msa".equals(a.type)) {
                    // 令牌可能过期，先续期再查
                    mcl.auth.MicrosoftAuth.ensureValid(a);
                }
                mcl.auth.ProfileSkins.Skin s = mcl.auth.ProfileSkins.fetch(a);
                java.awt.image.BufferedImage img = mcl.auth.ProfileSkins.sync(s, win.settings().launcherDir());
                final Account acc = a;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    loadPreview(acc);
                    if (img == null) {
                        win.setStatus("没有取到皮肤纹理（该账户可能未设置皮肤）");
                    } else {
                        win.setStatus("皮肤已更新：" + acc.username + (s.hasCape() ? "（含披风）" : ""));
                    }
                });
            } catch (Exception ex) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    win.setStatus("获取皮肤失败");
                    JOptionPane.showMessageDialog(this, "获取皮肤失败：\n" + ex.getMessage());
                });
            }
        });
    }

    /**
     * 导入一张本地 PNG 作为这个账户的皮肤，并且<b>存下来</b>。
     *
     * <p>以前这里只是把图片塞给预览控件，什么都没写盘：切个账户再切回来就变回 Steve，
     * 重启更是完全消失。现在会把图片规整成 64×64 存到 {@code <启动器目录>/skins/<uuid>.png}。
     */
    private void useLocalSkin() {
        Account a = list.getSelectedValue();
        if (a == null) {
            JOptionPane.showMessageDialog(this, "请先在列表里选一个账户");
            return;
        }
        if (!"offline".equals(a.type)) {
            JOptionPane.showMessageDialog(this, a.username + " 是" + a.typeLabel() + "账户。\n\n"
                    + "它的皮肤由服务器下发，本地导入没有意义 ——\n"
                    + "想用自己的皮肤，点「上传皮肤」传上去（皮肤站账户），或在正版官网改。");
            return;
        }
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("选择皮肤 PNG（64×64 或 64×32）");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG 图片", "png"));
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(chooser.getSelectedFile());
            if (img == null) {
                JOptionPane.showMessageDialog(this, "读不出这张图片，确认是 PNG 文件");
                return;
            }
            java.nio.file.Path dest = mcl.auth.ProfileSkins.saveLocalSkin(
                    win.settings().launcherDir(), a.uuid, img);
            loadPreview(a);
            list.repaint();
            win.setStatus("已保存 " + a.username + " 的本地皮肤");
            JOptionPane.showMessageDialog(this,
                    "皮肤已保存到：\n" + dest + "\n\n"
                            + "下局启动时会自动带上：单人存档里就能看到这张皮肤，不用装模组。\n"
                            + "（启动器会在本机临时起一个只服务你自己的认证服务，游戏退出就关掉。）\n\n"
                            + "连别人的离线服务器时，对方服务端不认这套，皮肤还是看不到 ——\n"
                            + "这一点和 HMCL 一样，不是启动器能绕过的。\n\n"
                            + "想反悔：「换皮肤」里点「清除本地皮肤」。",
                    "已保存", JOptionPane.INFORMATION_MESSAGE);
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(this, "导入失败：\n" + ex.getMessage());
        }
    }

    /** 清掉这个账户的本地皮肤，回到内置 Steve / Alex。 */
    private void clearLocalSkin() {
        Account a = list.getSelectedValue();
        if (a == null) return;
        if (!mcl.auth.ProfileSkins.hasLocalSkin(win.settings().launcherDir(), a.uuid)) {
            JOptionPane.showMessageDialog(this, a.username + " 现在用的就是内置皮肤，没有本地图可以清");
            return;
        }
        mcl.auth.ProfileSkins.deleteLocalSkin(win.settings().launcherDir(), a.uuid);
        loadPreview(a);
        list.repaint();
        win.setStatus(a.username + " 已清除本地皮肤，回到内置 " +
                mcl.auth.ProfileSkins.modelLabel(mcl.auth.ProfileSkins.effectiveModel(a.uuid, a.skinModel)));
    }

    private void addAccount() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String bad = Account.checkName(name);
        if (bad != null) {
            JOptionPane.showMessageDialog(this, bad);
            nameField.requestFocusInWindow();
            return;
        }
        for (Account a : win.settings().accounts) {
            if (a.username.equalsIgnoreCase(name)) {
                JOptionPane.showMessageDialog(this, "该账户已存在");
                return;
            }
        }
        Account a = new Account(name, "offline");
        a.skinModel = newSkin.model();
        win.settings().accounts.add(a);
        win.settings().selectedAccount = a.username;
        win.settings().save();
        nameField.setText("");
        refresh();
        win.refreshAll();
        hint.setText("已添加离线账户 " + name + "（" + mcl.auth.ProfileSkins.modelLabel(a.skinModel) + "）");
    }

    private void selectAccount() {
        Account a = list.getSelectedValue();
        if (a == null) return;
        win.settings().selectedAccount = a.username;
        win.settings().save();
        refresh();
        win.refreshAll();
    }

    /**
     * 改离线账户的角色名。
     *
     * <p>存在的意义：中文名能加进账户列表、能启动到主界面，但一进世界就被游戏踢掉
     * （{@code Invalid characters in username}）。以前只能删了重建，现在就地改名。
     */
    private void renameAccount() {
        Account a = list.getSelectedValue();
        if (a == null) {
            JOptionPane.showMessageDialog(this, "请先在列表里选中一个账户");
            return;
        }
        if (!"offline".equals(a.type)) {
            JOptionPane.showMessageDialog(this, a.username + " 是" + a.typeLabel()
                    + "账户，名字由服务器决定，不能在这里改。");
            return;
        }
        Object input = JOptionPane.showInputDialog(this,
                "新的角色名（3~16 位英文、数字或下划线）：", "改名", JOptionPane.PLAIN_MESSAGE,
                null, null, a.username);
        if (input == null) return;
        String name = input.toString().trim();
        if (name.equals(a.username)) return;
        String bad = Account.checkName(name);
        if (bad != null) {
            JOptionPane.showMessageDialog(this, bad);
            return;
        }
        for (Account x : win.settings().accounts) {
            if (x != a && x.username.equalsIgnoreCase(name)) {
                JOptionPane.showMessageDialog(this, "已经有一个叫 " + name + " 的账户了");
                return;
            }
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "把「" + a.username + "」改名为「" + name + "」？\n\n"
                        + "改名后 UUID 会按新名字重算，新开的存档是一个全新的玩家身份；\n"
                        + "旧存档的地形和建筑都还在，但那个存档里的角色装备、背包、坐标会重置。",
                "确认改名", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        String old = a.username;
        String oldUuid = a.uuid;
        a.username = name;
        a.uuid = Account.offlineUuid(name);
        // 改名换 UUID，本地皮肤是按 UUID 存的 —— 不跟着搬就等于"皮肤丢了"
        mcl.auth.ProfileSkins.moveLocalSkin(win.settings().launcherDir(), oldUuid, a.uuid);
        if (old.equals(win.settings().selectedAccount)) win.settings().selectedAccount = name;
        win.settings().save();
        refresh();
        win.refreshAll();
        win.setStatus("角色名已改为 " + name + "，UUID 已按新名字重算");
    }

    private void deleteAccount() {        Account a = list.getSelectedValue();
        if (a == null) return;
        if (JOptionPane.showConfirmDialog(this, "确定删除账户 " + a.username + " 吗？",
                "删除账户", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        win.settings().accounts.remove(a);
        if (a.username.equals(win.settings().selectedAccount)) {
            win.settings().selectedAccount = win.settings().accounts.isEmpty()
                    ? "" : win.settings().accounts.get(0).username;
        }
        win.settings().save();
        refresh();
        win.refreshAll();
    }

    // ---------------- 登录 ----------------

    private void loginMicrosoft() {
        win.setStatus("正在向微软申请登录代码…");
        win.pool().submit(() -> {
            try {
                mcl.auth.MicrosoftAuth.DeviceCode code = mcl.auth.MicrosoftAuth.start();
                SwingUtilities.invokeLater(() -> showMicrosoftDialog(code));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    win.setStatus("申请登录代码失败");
                    JOptionPane.showMessageDialog(this, "申请登录代码失败：\n" + ex.getMessage());
                });
            }
        });
    }

    private void showMicrosoftDialog(mcl.auth.MicrosoftAuth.DeviceCode code) {
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "微软正版登录",
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel step1 = Ui.label("1. 在浏览器打开 microsoft.com/link", Font.PLAIN, 12.5f, Ui.MUTED);
        JLabel codeLabel = Ui.label(code.userCode, Font.BOLD, 30f, Ui.accent());
        JLabel step2 = Ui.label("2. 输入上面的代码并用微软账户登录", Font.PLAIN, 12.5f, Ui.MUTED);
        JLabel status = Ui.muted("等待授权…");

        Ui.RoundButton copy = (Ui.RoundButton) Ui.ghost("复制代码");
        copy.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(code.userCode), null);
            status.setText("代码已复制，去浏览器粘贴即可");
        });
        Ui.RoundButton open = (Ui.RoundButton) Ui.primary("打开登录页面");
        open.addActionListener(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(code.verificationUri));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dlg, "无法打开浏览器，请手动访问 " + code.verificationUri);
            }
        });
        Ui.RoundButton cancel = (Ui.RoundButton) Ui.ghost("取消");
        cancel.addActionListener(e -> dlg.dispose());

        javax.swing.JPanel box = new javax.swing.JPanel();
        box.setBackground(Ui.CARD);
        box.setLayout(new javax.swing.BoxLayout(box, javax.swing.BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));
        step1.setAlignmentX(Component.LEFT_ALIGNMENT);
        codeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        step2.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(step1);
        box.add(Box.createVerticalStrut(8));
        box.add(codeLabel);
        box.add(Box.createVerticalStrut(8));
        box.add(step2);
        box.add(Box.createVerticalStrut(14));
        javax.swing.JPanel btns = Ui.row(8, copy, open, cancel, Box.createHorizontalGlue(), status);
        btns.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(btns);
        dlg.setContentPane(box);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);

        // 后台轮询授权结果
        win.setStatus("等待微软授权…");
        win.pool().submit(() -> {
            long deadline = System.currentTimeMillis() + code.expiresIn * 1000L;
            while (dlg.isDisplayable() && System.currentTimeMillis() < deadline) {
                try {
                    mcl.auth.MicrosoftAuth.MsaToken token = mcl.auth.MicrosoftAuth.poll(code);
                    Account a = mcl.auth.MicrosoftAuth.toAccount(token);
                    SwingUtilities.invokeLater(() -> {
                        saveAccount(a);
                        dlg.dispose();
                        win.setStatus("已登录微软账户 " + a.username);
                    });
                    return;
                } catch (mcl.auth.MicrosoftAuth.PendingException e) {
                    try {
                        Thread.sleep(Math.max(2, code.interval) * 1000L);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        status.setText("登录失败：" + ex.getMessage());
                        JOptionPane.showMessageDialog(dlg, "登录失败：\n" + ex.getMessage());
                        dlg.dispose();
                    });
                    return;
                }
            }
        });
    }

    private void loginAuthlib() {
        JTextField root = new JTextField("https://littleskin.cn/api/yggdrasil");
        JTextField user = new JTextField();
        JPasswordField pass = new JPasswordField();
        javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.GridLayout(0, 1, 0, 8));
        panel.add(new JLabel("皮肤站 API 地址"));
        panel.add(root);
        panel.add(new JLabel("账号（邮箱）"));
        panel.add(user);
        panel.add(new JLabel("密码"));
        panel.add(pass);
        panel.setPreferredSize(new Dimension(340, 220));
        int r = JOptionPane.showConfirmDialog(this, panel, "外置登录（authlib-injector）",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        String apiRoot = root.getText() == null ? "" : root.getText().trim();
        String name = user.getText() == null ? "" : user.getText().trim();
        String pwd = new String(pass.getPassword());
        if (apiRoot.isEmpty() || name.isEmpty() || pwd.isEmpty()) {
            JOptionPane.showMessageDialog(this, "地址、账号、密码都要填");
            return;
        }
        win.setStatus("正在登录 " + apiRoot + "…");
        win.pool().submit(() -> {
            try {
                mcl.auth.AuthlibInjector.Server server = mcl.auth.AuthlibInjector.metadata(apiRoot);
                Account a = mcl.auth.AuthlibInjector.authenticate(server, name, pwd, "");
                mcl.auth.AuthlibInjector.ensureJar(win.settings().launcherDir.resolve("libraries").resolve("mcl"));
                SwingUtilities.invokeLater(() -> {
                    saveAccount(a);
                    win.setStatus("已登录 " + a.username + " @ " + server.serverName);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    win.setStatus("外置登录失败");
                    JOptionPane.showMessageDialog(this, "外置登录失败：\n" + ex.getMessage());
                });
            }
        });
    }

    private void uploadSkin() {
        Account a = list.getSelectedValue();
        if (a == null) {
            JOptionPane.showMessageDialog(this, "请先选择一个账户");
            return;
        }
        if (!"authlib".equals(a.type)) {
            JOptionPane.showMessageDialog(this, "只有外置登录账户能上传皮肤。\n"
                    + "微软正版账户请在 minecraft.net 修改皮肤，离线账户的皮肤不被服务器识别。");
            return;
        }
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("选择皮肤 PNG（64x64 或 64x32）");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG 图片", "png"));
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        Path png = chooser.getSelectedFile().toPath();
        int r = JOptionPane.showOptionDialog(this, "选择皮肤模型：", "上传皮肤",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new String[]{"经典（Steve）", "细手臂（Alex）", "取消"}, "经典（Steve）");
        if (r != 0 && r != 1) return;
        boolean slim = r == 1;
        win.setStatus("正在上传皮肤…");
        win.pool().submit(() -> {
            try {
                mcl.auth.Skins.upload(a, png, slim);
                SwingUtilities.invokeLater(() -> {
                    win.setStatus("皮肤已上传：" + a.username);
                    refreshSkin();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    win.setStatus("皮肤上传失败");
                    JOptionPane.showMessageDialog(this, "皮肤上传失败：\n" + ex.getMessage());
                });
            }
        });
    }

    private void saveAccount(Account a) {
        java.util.List<mcl.config.Account> list = win.settings().accounts;
        list.removeIf(x -> x.username.equalsIgnoreCase(a.username)
                && (x.type == null ? "offline" : x.type).equals(a.type));
        list.add(a);
        win.settings().selectedAccount = a.username;
        win.settings().save();
        refresh();
        win.refreshAll();
    }

    private class Renderer extends JPanel implements ListCellRenderer<Account> {
        private final Avatar avatar = new Avatar();
        private final JLabel name = Ui.label("", Font.BOLD, 14f, Ui.TEXT);
        private final JLabel sub = Ui.label("", Font.PLAIN, 11.5f, Ui.MUTED);
        private boolean picked;

        Renderer() {
            setOpaque(false);
            setLayout(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            avatar.setPreferredSize(new Dimension(40, 40));
            add(avatar, BorderLayout.WEST);
            add(Ui.col(3, name, sub), BorderLayout.CENTER);
        }

        /** 选中行画一层浅色圆角底 —— JList 本身是透明的，不自己画就完全看不出来。 */
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (picked) Ui.paintRowSelection(g, this, 0.90f);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Account> l, Account a, int index,
                                                      boolean selected, boolean focus) {
            picked = selected;
            avatar.setAccount(a, win.settings().launcherDir());
            name.setText(a.username);
            boolean current = a.username.equals(win.settings().selectedAccount);
            String model;
            if (!"offline".equals(a.type)) {
                model = "";
            } else if (mcl.auth.ProfileSkins.hasLocalSkin(win.settings().launcherDir(), a.uuid)) {
                model = "  ·  本地皮肤";
            } else {
                model = "  ·  " + (mcl.auth.ProfileSkins.isSlim(a.uuid, a.skinModel) ? "Alex" : "Steve");
            }
            sub.setText(a.typeLabel() + model + "  ·  UUID "
                    + (a.uuid.length() > 8 ? a.uuid.substring(0, 8) : a.uuid)
                    + (current ? "  ·  当前使用" : ""));
            return this;
        }
    }

    /** 列表左侧头像：有缓存皮肤就用真实头像，没有就退回首字母。 */
    private static class Avatar extends JPanel {
        private java.awt.image.BufferedImage image;

        Avatar() {
            setOpaque(false);
        }

        void setAccount(Account a, java.nio.file.Path launcherDir) {
            // 本地导入的皮肤优先：列表头像要和右边的大预览是同一个人
            java.awt.image.BufferedImage skin = "offline".equals(a.type)
                    ? mcl.auth.ProfileSkins.localSkin(launcherDir, a.uuid) : null;
            if (skin == null) skin = mcl.auth.ProfileSkins.cachedSkin(launcherDir, a.uuid);
            if (skin == null && "offline".equals(a.type)) {
                skin = mcl.auth.ProfileSkins.defaultSkin(a.uuid, a.skinModel);
            }
            image = SkinPreview.head(skin, 40);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (image != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
                g2.dispose();
            }
        }
    }
}
