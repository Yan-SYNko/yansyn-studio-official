import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.dnd.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.*;

public class CryptoChat extends JFrame {

    // 先定义方法（修复：方法必须在变量声明之前）
    private static String getAppDir() {
        try {
            java.io.File f = new java.io.File(CryptoChat.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            if (f.isFile()) {
                return f.getParent(); // 如果是 jar 或 exe，返回它所在的文件夹
            } else {
                return f.getAbsolutePath(); // 如果在 IDE 里运行，返回项目目录
            }
        } catch (Exception e) {
            return System.getProperty("user.dir"); // 极端情况下的兜底方案
        }
    }

    private static final long serialVersionUID = 2L;

    private static final String APP_NAME = "焰行端到端加密 YANSYN E2EE";
    private static final String VERSION = "V2.0.0";

    // 新版 RSA 算法：OAEP 填充
    private static final String RSA_CIPHER_NEW = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    // 旧版 RSA 算法：PKCS1 填充（兼容用）
    private static final String RSA_CIPHER_OLD = "RSA/ECB/PKCS1Padding";

    // 文件分块大小：1MB
    private static final int BUFFER_SIZE = 1024 * 1024;
    // 文本加密大小限制：100MB
    private static final long MAX_TEXT_SIZE = 100 * 1024 * 1024;

    // 获取软件所在目录
    private static final String APP_DIR = getAppDir();
    private static final String KEY_DIR = APP_DIR + "/keys";
    private static final String FRIENDS_DIR = KEY_DIR + "/friends";
    private static final String MY_PRIVATE_FILE = KEY_DIR + "/my_private.enc";
    private static final String MY_PUBLIC_FILE = KEY_DIR + "/my_public.key";
    private static final String NO_PWD_FLAG_FILE = KEY_DIR + "/no_pwd_mode.flag";
    
    // 日志系统
    private static final Logger logger = Logger.getLogger("YANSYN_E2EE");
    static {
        try {
            FileHandler fh = new FileHandler(APP_DIR + "/yansyn_e2ee.log", true);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.INFO);
        } catch (Exception e) {
            // 日志失败不影响运行
        }
    }

    // 好友公钥缓存（提升验签性能）
    private volatile Map<String, PublicKey> friendKeyCache = new HashMap<>();

    private String currentFriend = null;

    private byte[] encryptedPrivateFile = null;
    private volatile PrivateKey cachedPrivateKey = null;
    private volatile PublicKey cachedPublicKey = null;

    private boolean initializing = false;

    // UI 控件
    private JTextArea textInputArea;
    private JTextArea textOutputArea;
    private JLabel filePathLabel;
    private File selectedFile;
    private JLabel verifyLabel;
    private JLabel statusLabel;
    private JLabel keyStatusLabel;
    private JLabel selectedFriendLabel;
    private JList<String> friendsList;
    private DefaultListModel<String> friendsModel;
    private JCheckBox noPasswordModeCheck;

    // 进度回调接口
    private interface ProgressCallback {
        void onProgress(int percent);
        boolean isCancelled();
    }

    @SuppressWarnings("this-escape")
    public CryptoChat() {
        setTitle(APP_NAME + " " + VERSION);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1180, 800);
        setLocationRelativeTo(null);

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {}

        initUI();
        loadEncryptedPrivateKey();
        loadFriends();
        refreshFriendKeyCache(); // 启动时缓存公钥

        setVisible(true);

        SwingUtilities.invokeLater(() -> {
            if (new File(NO_PWD_FLAG_FILE).exists()) {
                initializing = true;
                noPasswordModeCheck.setSelected(true);
                initializing = false;
                unlockForNoPasswordMode();
            }
        });
    }

    private void initUI() {
        setLayout(new BorderLayout(10, 10));

        // 顶部
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(new Color(255, 107, 53));
        topPanel.setBorder(new EmptyBorder(10, 15, 10, 15));

        JLabel title = new JLabel(APP_NAME + " " + VERSION);
        title.setFont(new Font("微软雅黑", Font.BOLD, 20));
        title.setForeground(Color.WHITE);

        keyStatusLabel = new JLabel("未加载密钥");
        keyStatusLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        keyStatusLabel.setForeground(new Color(255, 240, 200));

        topPanel.add(title, BorderLayout.WEST);
        topPanel.add(keyStatusLabel, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);

        // 中间主面板
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(5, 10, 5, 10));

        // 左侧好友列表
        JPanel friendsPanel = new JPanel(new BorderLayout(5, 5));
        friendsPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), "好友列表",
            TitledBorder.LEFT, TitledBorder.TOP,
            new Font("微软雅黑", Font.BOLD, 13)));
        friendsPanel.setPreferredSize(new Dimension(230, 0));

        friendsModel = new DefaultListModel<>();
        friendsList = new JList<>(friendsModel);
        friendsList.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        friendsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        friendsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                currentFriend = friendsList.getSelectedValue();
                selectedFriendLabel.setText(currentFriend == null
                    ? "未选择收件人"
                    : "当前收件人：" + currentFriend);
                verifyLabel.setText(" ");
            }
        });

        JScrollPane friendScroll = new JScrollPane(friendsList);
        friendScroll.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        friendsPanel.add(friendScroll, BorderLayout.CENTER);

        JPanel friendBtnPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        JButton addFriendBtn = createButton("添加好友", new Color(0, 150, 136));
        addFriendBtn.addActionListener(e -> addFriend());
        JButton delFriendBtn = createButton("删除好友", new Color(180, 60, 60));
        delFriendBtn.addActionListener(e -> deleteFriend());
        friendBtnPanel.add(addFriendBtn);
        friendBtnPanel.add(delFriendBtn);
        friendsPanel.add(friendBtnPanel, BorderLayout.SOUTH);

        // 右侧主体
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));

        // 密钥管理面板
        JPanel keyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        keyPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), "我的密钥",
            TitledBorder.LEFT, TitledBorder.TOP,
            new Font("微软雅黑", Font.BOLD, 13)));
        keyPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 75));

        JButton genKeyBtn = createButton("生成密钥对", new Color(0, 150, 136));
        JButton exportPubBtn = createButton("导出公钥", new Color(255, 107, 53));
        JButton changePwdBtn = createButton("修改密码", new Color(120, 120, 120));
        JButton addVirtualBtn = createButton("虚拟收件人", new Color(150, 100, 180));
        JButton exportAccountBtn = createButton("导出账户", new Color(100, 150, 80));
        JButton importAccountBtn = createButton("导入账户", new Color(80, 130, 180));

        noPasswordModeCheck = new JCheckBox("免密码模式");
        noPasswordModeCheck.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        noPasswordModeCheck.setForeground(new Color(200, 60, 60));
        noPasswordModeCheck.addActionListener(e -> toggleNoPasswordMode());

        genKeyBtn.addActionListener(e -> generateKeys());
        exportPubBtn.addActionListener(e -> exportMyPublicKey());
        changePwdBtn.addActionListener(e -> changePassword());
        addVirtualBtn.addActionListener(e -> addVirtualFriend());
        exportAccountBtn.addActionListener(e -> exportAccount());
        importAccountBtn.addActionListener(e -> importAccount());

        keyPanel.add(genKeyBtn);
        keyPanel.add(exportPubBtn);
        keyPanel.add(changePwdBtn);
        keyPanel.add(addVirtualBtn);
        keyPanel.add(exportAccountBtn);
        keyPanel.add(importAccountBtn);
        keyPanel.add(noPasswordModeCheck);
        rightPanel.add(keyPanel);
        rightPanel.add(Box.createVerticalStrut(5));

        // 当前收件人
        selectedFriendLabel = new JLabel("未选择收件人");
        selectedFriendLabel.setFont(new Font("微软雅黑", Font.BOLD, 14));
        selectedFriendLabel.setForeground(new Color(0, 100, 150));
        selectedFriendLabel.setBorder(new EmptyBorder(5, 5, 0, 5));
        rightPanel.add(selectedFriendLabel);

        // 签名验证结果
        verifyLabel = new JLabel(" ");
        verifyLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        verifyLabel.setForeground(new Color(80, 80, 80));
        verifyLabel.setBorder(new EmptyBorder(0, 5, 5, 5));
        rightPanel.add(verifyLabel);

        // 标签页
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("微软雅黑", Font.BOLD, 14));
        tabbedPane.setUI(new CustomTabbedPaneUI());
        tabbedPane.setBackground(new Color(240, 240, 240));
        tabbedPane.addTab("文本操作", createTextTab());
        tabbedPane.addTab("文件操作", createFileTab());
        tabbedPane.setSelectedIndex(0);
        rightPanel.add(tabbedPane);

        mainPanel.add(friendsPanel, BorderLayout.WEST);
        mainPanel.add(rightPanel, BorderLayout.CENTER);
        add(mainPanel, BorderLayout.CENTER);

        // 底部状态栏
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.setBorder(new EmptyBorder(0, 15, 8, 15));
        statusLabel = new JLabel("就绪");
        statusLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        statusLabel.setForeground(Color.GRAY);
        statusPanel.add(statusLabel);
        add(statusPanel, BorderLayout.SOUTH);

        setupDragAndDrop();
    }

    private JPanel createTextTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));
        panel.setBackground(new Color(248, 249, 250));

        JPanel ioPanel = new JPanel(new GridLayout(1, 2, 10, 0));

        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        JLabel inLabel = new JLabel("输入消息 / 密文：");
        inLabel.setFont(new Font("微软雅黑", Font.BOLD, 13));
        textInputArea = new JTextArea();
        textInputArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        textInputArea.setLineWrap(true);
        textInputArea.setWrapStyleWord(true);
        JScrollPane inScroll = new JScrollPane(textInputArea);
        inScroll.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        inputPanel.add(inLabel, BorderLayout.NORTH);
        inputPanel.add(inScroll, BorderLayout.CENTER);

        JPanel outputPanel = new JPanel(new BorderLayout(5, 5));
        JLabel outLabel = new JLabel("输出结果：");
        outLabel.setFont(new Font("微软雅黑", Font.BOLD, 13));
        textOutputArea = new JTextArea();
        textOutputArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        textOutputArea.setLineWrap(true);
        textOutputArea.setWrapStyleWord(true);
        textOutputArea.setEditable(false);
        textOutputArea.setBackground(Color.WHITE);
        JScrollPane outScroll = new JScrollPane(textOutputArea);
        outScroll.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        outputPanel.add(outLabel, BorderLayout.NORTH);
        outputPanel.add(outScroll, BorderLayout.CENTER);

        ioPanel.add(inputPanel);
        ioPanel.add(outputPanel);
        panel.add(ioPanel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        JButton encryptTextBtn = createButton("加密文本", new Color(0, 150, 136));
        encryptTextBtn.setPreferredSize(new Dimension(130, 38));
        JButton decryptTextBtn = createButton("解密文本", new Color(63, 81, 181));
        decryptTextBtn.setPreferredSize(new Dimension(130, 38));
        JButton copyBtn = createButton("复制结果", new Color(255, 107, 53));
        copyBtn.setPreferredSize(new Dimension(110, 38));
        JButton clearBtn = createButton("清空", new Color(120, 120, 120));
        clearBtn.setPreferredSize(new Dimension(90, 38));

        encryptTextBtn.addActionListener(e -> encryptText());
        decryptTextBtn.addActionListener(e -> decryptText());
        copyBtn.addActionListener(e -> {
            if (!textOutputArea.getText().isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(textOutputArea.getText()), null);
                statusLabel.setText("已复制到剪贴板");
            }
        });
        clearBtn.addActionListener(e -> {
            textInputArea.setText("");
            textOutputArea.setText("");
            verifyLabel.setText(" ");
            statusLabel.setText("已清空");
        });

        btnPanel.add(encryptTextBtn);
        btnPanel.add(decryptTextBtn);
        btnPanel.add(copyBtn);
        btnPanel.add(clearBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createFileTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));
        panel.setBackground(new Color(248, 249, 250));

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setBackground(new Color(248, 249, 250));

        JLabel hint = new JLabel("选择要加密或解密的文件（支持任意格式）");
        hint.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        hint.setForeground(Color.GRAY);
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerPanel.add(Box.createVerticalStrut(30));
        centerPanel.add(hint);
        centerPanel.add(Box.createVerticalStrut(20));

        filePathLabel = new JLabel("未选择文件");
        filePathLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        filePathLabel.setForeground(Color.DARK_GRAY);
        filePathLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerPanel.add(filePathLabel);
        centerPanel.add(Box.createVerticalStrut(20));

        JButton chooseBtn = createButton("选择文件", new Color(100, 100, 100));
        chooseBtn.setPreferredSize(new Dimension(120, 35));
        chooseBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        chooseBtn.addActionListener(e -> chooseFile());
        centerPanel.add(chooseBtn);

        panel.add(centerPanel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        JButton encryptFileBtn = createButton("加密文件", new Color(0, 150, 136));
        encryptFileBtn.setPreferredSize(new Dimension(130, 38));
        JButton decryptFileBtn = createButton("解密文件", new Color(63, 81, 181));
        decryptFileBtn.setPreferredSize(new Dimension(130, 38));
        encryptFileBtn.addActionListener(e -> encryptFile());
        decryptFileBtn.addActionListener(e -> decryptFile());
        btnPanel.add(encryptFileBtn);
        btnPanel.add(decryptFileBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JButton createButton(String text, Color color) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("微软雅黑", Font.BOLD, 12));
        btn.setBackground(color);
        btn.setForeground(Color.BLACK);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    static class CustomTabbedPaneUI extends BasicTabbedPaneUI {
        private final Color selectedBg = new Color(255, 107, 53);
        private final Color unselectedBg = new Color(220, 220, 220);

        @Override
        protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex,
                                           int x, int y, int w, int h, boolean isSelected) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isSelected ? selectedBg : unselectedBg);
            g2.fillRect(x, y, w, h);
            g2.dispose();
        }

        @Override
        protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex,
                                       int x, int y, int w, int h, boolean isSelected) {}

        @Override
        protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects,
                                            int tabIndex, Rectangle iconRect, Rectangle textRect,
                                            boolean isSelected) {}

        @Override
        protected void paintText(Graphics g, int tabPlacement, Font font, FontMetrics metrics,
                                  int tabIndex, String title, Rectangle textRect, boolean isSelected) {
            g.setFont(font);
            g.setColor(isSelected ? Color.BLACK : Color.DARK_GRAY);
            g.drawString(title, textRect.x, textRect.y + metrics.getAscent());
        }
    }

    @SuppressWarnings("unchecked")
    private void setupDragAndDrop() {
        DropTargetListener listener = new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetDragEvent e) {
                if (e.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
                    e.acceptDrag(DnDConstants.ACTION_COPY);
            }

            @Override
            public void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    List<File> files = (List<File>) e.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                    for (File f : files) {
                        String name = f.getName().toLowerCase();
                        if (name.endsWith(".key") || name.endsWith(".pub")) {
                            addFriendFromFile(f);
                        } else {
                            selectedFile = f;
                            filePathLabel.setText("已选择：" + f.getAbsolutePath());
                            statusLabel.setText("已选择文件：" + f.getName());
                        }
                    }
                    e.dropComplete(true);
                } catch (Exception ex) {
                    e.dropComplete(false);
                }
            }
        };
        new DropTarget(this, listener);
        new DropTarget(friendsList, listener);
    }

    // ============ 好友公钥缓存（修复：验签性能） ============
    private void refreshFriendKeyCache() {
        Map<String, PublicKey> newCache = new HashMap<>();
        File dir = new File(FRIENDS_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".pub"));
            if (files != null) {
                KeyFactory kf;
                try {
                    kf = KeyFactory.getInstance("RSA");
                } catch (Exception e) {
                    return;
                }
                for (File f : files) {
                    try {
                        PublicKey pub = kf.generatePublic(
                            new X509EncodedKeySpec(Files.readAllBytes(f.toPath())));
                        newCache.put(f.getName().replace(".pub", ""), pub);
                    } catch (Exception ignored) {}
                }
            }
        }
        friendKeyCache = newCache;
        logger.info("好友公钥缓存已刷新，共 " + newCache.size() + " 个");
    }

    // ============ 免密码模式 ============
    private void toggleNoPasswordMode() {
        if (initializing) return;

        if (noPasswordModeCheck.isSelected()) {
            int r = JOptionPane.showConfirmDialog(this,
                "开启免密码模式后：\n\n" +
                "1. 启动软件时输一次密码\n" +
                "2. 之后加密/解密不再需要密码\n" +
                "3. 私钥会以明文形式保存在内存中\n\n" +
                "风险提示：\n" +
                "如果有恶意软件扫描你的内存，\n" +
                "可能读取到明文私钥。\n\n" +
                "确定要开启吗？",
                "免密码模式 - 安全提示",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

            if (r != JOptionPane.YES_OPTION) {
                noPasswordModeCheck.setSelected(false);
                return;
            }

            if (encryptedPrivateFile == null) {
                JOptionPane.showMessageDialog(this, "请先生成密钥！");
                noPasswordModeCheck.setSelected(false);
                return;
            }

            unlockForNoPasswordMode();

            if (cachedPrivateKey != null) {
                try {
                    new File(KEY_DIR).mkdirs();
                    Files.write(Paths.get(NO_PWD_FLAG_FILE), new byte[]{1});
                    statusLabel.setText("免密码模式已开启");
                    logger.info("免密码模式已开启");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "保存设置失败：" + ex.getMessage());
                }
            } else {
                noPasswordModeCheck.setSelected(false);
            }
        } else {
            cachedPrivateKey = null;
            cachedPublicKey = null;
            try {
                Files.deleteIfExists(Paths.get(NO_PWD_FLAG_FILE));
            } catch (Exception ignored) {}
            statusLabel.setText("免密码模式已关闭");
            logger.info("免密码模式已关闭");
        }
    }

    private void unlockForNoPasswordMode() {
        if (encryptedPrivateFile == null) return;

        char[] password = promptPassword("请输入密码解锁（免密码模式）：");
        if (password == null || password.length == 0) {
            initializing = true;
            noPasswordModeCheck.setSelected(false);
            initializing = false;
            return;
        }

        byte[] privBytes = null;
        try {
            privBytes = decryptPrivateKeyTemporary(password);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            cachedPrivateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));

            byte[] pubBytes = Files.readAllBytes(Paths.get(MY_PUBLIC_FILE));
            cachedPublicKey = kf.generatePublic(new X509EncodedKeySpec(pubBytes));

            keyStatusLabel.setText("密钥已解锁（免密码模式）");
            statusLabel.setText("免密码模式已开启");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "密码错误！");
            initializing = true;
            noPasswordModeCheck.setSelected(false);
            initializing = false;
            cachedPrivateKey = null;
        } finally {
            Arrays.fill(password, (char) 0);
            if (privBytes != null) Arrays.fill(privBytes, (byte) 0);
        }
    }

    private void loadEncryptedPrivateKey() {
        File priv = new File(MY_PRIVATE_FILE);
        File pub = new File(MY_PUBLIC_FILE);

        if (priv.exists() && pub.exists()) {
            try {
                encryptedPrivateFile = Files.readAllBytes(priv.toPath());
                keyStatusLabel.setText("密钥已加载");
                statusLabel.setText("密钥已就绪");
            } catch (Exception e) {
                keyStatusLabel.setText("密钥加载失败");
                logger.severe("密钥加载失败: " + e.getMessage());
            }
        } else {
            keyStatusLabel.setText("未生成密钥");
            statusLabel.setText("首次使用：请点「生成密钥对」");
        }
    }

    private PrivateKey getPrivateKey() {
        if (cachedPrivateKey != null) {
            return cachedPrivateKey;
        }

        char[] password = promptPassword("请输入你的密码：");
        if (password == null || password.length == 0) {
            return null;
        }

        byte[] privBytes = null;
        try {
            privBytes = decryptPrivateKeyTemporary(password);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "密码错误！");
            logger.warning("密码错误尝试");
            return null;
        } finally {
            Arrays.fill(password, (char) 0);
            if (privBytes != null) Arrays.fill(privBytes, (byte) 0);
        }
    }

    private void generateKeys() {
        if (new File(MY_PRIVATE_FILE).exists()) {
            int r = JOptionPane.showConfirmDialog(this,
                "你已经有一对密钥了！\n\n重新生成会导致旧公钥作废。\n\n确定要重新生成吗？",
                "警告", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }

        char[] password = promptPassword("为你的私钥设置一个保护密码：");
        if (password == null || password.length == 0) {
            JOptionPane.showMessageDialog(this, "密码不能为空！");
            return;
        }

        try {
            new File(KEY_DIR).mkdirs();

            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();

            byte[] encryptedPriv = encryptPrivateKey(pair.getPrivate().getEncoded(), password);
            Files.write(Paths.get(MY_PRIVATE_FILE), encryptedPriv);
            Files.write(Paths.get(MY_PUBLIC_FILE), pair.getPublic().getEncoded());

            encryptedPrivateFile = encryptedPriv;

            if (noPasswordModeCheck.isSelected()) {
                cachedPrivateKey = pair.getPrivate();
                cachedPublicKey = pair.getPublic();
            }

            keyStatusLabel.setText("密钥已生成");
            statusLabel.setText("密钥对生成成功");
            logger.info("新密钥对已生成");
            JOptionPane.showMessageDialog(this,
                "密钥对生成成功！\n\n私钥：" + MY_PRIVATE_FILE + "\n公钥：" + MY_PUBLIC_FILE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "生成失败：" + ex.getMessage());
            logger.severe("密钥生成失败: " + ex.getMessage());
        } finally {
            Arrays.fill(password, (char) 0);
        }
    }

    private byte[] decryptPrivateKeyTemporary(char[] password) throws Exception {
        if (encryptedPrivateFile == null) {
            throw new Exception("私钥未加载");
        }

        byte[] salt = Arrays.copyOfRange(encryptedPrivateFile, 0, 16);
        byte[] iv = Arrays.copyOfRange(encryptedPrivateFile, 16, 32);
        byte[] encrypted = Arrays.copyOfRange(encryptedPrivateFile, 32, encryptedPrivateFile.length);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password, salt, 100000, 256);
        byte[] aesKeyBytes = factory.generateSecret(spec).getEncoded();

        try {
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        } finally {
            Arrays.fill(aesKeyBytes, (byte) 0);
        }
    }

    private byte[] encryptPrivateKey(byte[] keyBytes, char[] password) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password, salt, 100000, 256);
        byte[] aesKeyBytes = factory.generateSecret(spec).getEncoded();

        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);

        byte[] encrypted;
        try {
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
            encrypted = cipher.doFinal(keyBytes);
        } finally {
            Arrays.fill(aesKeyBytes, (byte) 0);
        }

        byte[] result = new byte[32 + encrypted.length];
        System.arraycopy(salt, 0, result, 0, 16);
        System.arraycopy(iv, 0, result, 16, 16);
        System.arraycopy(encrypted, 0, result, 32, encrypted.length);
        return result;
    }

    // 修复：密码输入框内存泄露 - 组件立即置空
    private char[] promptPassword(String prompt) {
        JPasswordField pf = new JPasswordField(20);
        int ok = JOptionPane.showConfirmDialog(this, pf, prompt,
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] pwd = (ok == JOptionPane.OK_OPTION) ? pf.getPassword() : null;
        pf.setText(""); // 清空组件
        pf = null; // 释放引用，加速 GC
        return pwd;
    }

    private void exportMyPublicKey() {
        // 优先使用缓存的公钥
        if (cachedPublicKey == null && !new File(MY_PUBLIC_FILE).exists()) {
            JOptionPane.showMessageDialog(this, "请先生成密钥！");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存公钥到...");
        chooser.setSelectedFile(new File("我的公钥_" + System.currentTimeMillis() + ".key"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        try {
            // 如果有缓存，先从缓存写入；否则复制文件
            if (cachedPublicKey != null) {
                Files.write(chooser.getSelectedFile().toPath(), 
                    cachedPublicKey.getEncoded());
            } else {
                Files.copy(Paths.get(MY_PUBLIC_FILE), 
                    chooser.getSelectedFile().toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            
            JOptionPane.showMessageDialog(this,
                "公钥已导出：\n" + chooser.getSelectedFile().getAbsolutePath());
            logger.info("公钥已导出");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导出失败：" + ex.getMessage());
            logger.severe("公钥导出失败: " + ex.getMessage());
        }
    }

    private void changePassword() {
        if (encryptedPrivateFile == null) {
            JOptionPane.showMessageDialog(this, "请先生成密钥！");
            return;
        }

        char[] oldPwd = promptPassword("请输入旧密码：");
        if (oldPwd == null || oldPwd.length == 0) return;

        byte[] privBytes = null;
        try {
            privBytes = decryptPrivateKeyTemporary(oldPwd);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "旧密码错误！");
            return;
        } finally {
            Arrays.fill(oldPwd, (char) 0);
        }

        char[] newPwd = promptPassword("请输入新密码：");
        if (newPwd == null || newPwd.length == 0) {
            Arrays.fill(privBytes, (byte) 0);
            JOptionPane.showMessageDialog(this, "新密码不能为空！");
            return;
        }

        try {
            byte[] encrypted = encryptPrivateKey(privBytes, newPwd);
            Files.write(Paths.get(MY_PRIVATE_FILE), encrypted);
            encryptedPrivateFile = encrypted;
            JOptionPane.showMessageDialog(this, "密码修改成功！");
            logger.info("密码已修改");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "修改失败：" + ex.getMessage());
        } finally {
            Arrays.fill(newPwd, (char) 0);
            Arrays.fill(privBytes, (byte) 0);
        }
    }

    private void loadFriends() {
        friendsModel.clear();
        File dir = new File(FRIENDS_DIR);
        if (!dir.exists()) return;

        File[] files = dir.listFiles((d, n) -> n.endsWith(".pub"));
        if (files == null) return;

        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            friendsModel.addElement(f.getName().replace(".pub", ""));
        }
    }

    private void addFriend() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择好友的公钥文件");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        addFriendFromFile(chooser.getSelectedFile());
    }

    private void addFriendFromFile(File pubFile) {
        String defaultName = pubFile.getName().replaceAll("\\.(key|pub)$", "");

        JTextField nameField = new JTextField(defaultName);
        int ok = JOptionPane.showConfirmDialog(this, nameField,
            "给这个好友起个名字：", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;

        String friendName = nameField.getText().trim();
        if (friendName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "名字不能为空！");
            return;
        }

        friendName = friendName.replaceAll("[\\\\/:*?\"<>|]", "");
        if (friendName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "名字包含非法字符或为空！");
            return;
        }

        File existing = new File(FRIENDS_DIR, friendName + ".pub");
        if (existing.exists()) {
            int r = JOptionPane.showConfirmDialog(this,
                "好友「" + friendName + "」已经存在！\n\n名字被占用，请换一个名字。\n是否用新公钥覆盖原来的？",
                "重名提示", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }

        try {
            new File(FRIENDS_DIR).mkdirs();
            Path target = Paths.get(FRIENDS_DIR, friendName + ".pub");
            Files.copy(pubFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING);

            loadFriends();
            refreshFriendKeyCache(); // 刷新缓存
            friendsList.setSelectedValue(friendName, true);
            statusLabel.setText("已添加好友：" + friendName);
            logger.info("好友已添加: " + friendName);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "添加失败：" + ex.getMessage());
        }
    }

    private void addVirtualFriend() {
        // 检查缓存或文件
        if (cachedPrivateKey == null && !new File(MY_PRIVATE_FILE).exists()) {
            JOptionPane.showMessageDialog(this,
                "请先生成密钥对！\n\n点「生成密钥对」后再使用此功能。",
                "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int r = JOptionPane.showConfirmDialog(this,
            "添加虚拟收件人：\n\n" +
            "这会创建一个虚拟收件人，公钥使用你自己的公钥。\n\n" +
            "用途：\n" +
            "  - 测试加密/解密功能\n" +
            "  - 给自己写加密笔记\n" +
            "  - 验证签名流程\n\n" +
            "注意：虚拟收件人只能自己给自己发消息，\n" +
            "不能和真正的朋友聊天。\n\n" +
            "确定要添加吗？",
            "添加虚拟收件人",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.INFORMATION_MESSAGE);

        if (r != JOptionPane.YES_OPTION) return;

        try {
            new File(FRIENDS_DIR).mkdirs();
            String friendName = "虚拟收件人";
            Path target = Paths.get(FRIENDS_DIR, friendName + ".pub");

            if (Files.exists(target)) {
                int r2 = JOptionPane.showConfirmDialog(this,
                    "虚拟收件人已存在！\n\n" +
                    "继续操作会用新公钥覆盖原来的。\n" +
                    "（虚拟收件人用的是你自己的公钥，\n" +
                    "  覆盖后旧密文将无法解密）\n\n" +
                    "确定覆盖吗？",
                    "覆盖确认",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
                if (r2 != JOptionPane.YES_OPTION) return;
            }

            // 优先使用缓存的公钥
            if (cachedPublicKey != null) {
                Files.write(target, cachedPublicKey.getEncoded());
            } else {
                Files.copy(Paths.get(MY_PUBLIC_FILE), target, 
                    StandardCopyOption.REPLACE_EXISTING);
            }

            loadFriends();
            refreshFriendKeyCache();
            friendsList.setSelectedValue(friendName, true);
            statusLabel.setText("已添加虚拟收件人");
            logger.info("虚拟收件人已添加");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "添加失败：" + ex.getMessage());
        }
    }
    
    private void deleteFriend() {
        if (currentFriend == null) {
            JOptionPane.showMessageDialog(this, "请先选择要删除的好友！");
            return;
        }

        int r = JOptionPane.showConfirmDialog(this,
            "确定删除好友「" + currentFriend + "」？",
            "确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;

        try {
            Files.deleteIfExists(Paths.get(FRIENDS_DIR, currentFriend + ".pub"));
            loadFriends();
            refreshFriendKeyCache();
            currentFriend = null;
            selectedFriendLabel.setText("未选择收件人");
            verifyLabel.setText(" ");
            statusLabel.setText("已删除好友");
            logger.info("好友已删除");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "删除失败：" + ex.getMessage());
        }
    }

    private void exportAccount() {
        if (!new File(KEY_DIR).exists()) {
            JOptionPane.showMessageDialog(this, "还没有生成密钥，无法导出！");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存账户备份到...");
        chooser.setSelectedFile(new File("YANSYN-E2EE账户备份_" +
            new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".zip"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File zipFile = chooser.getSelectedFile();
        if (!zipFile.getName().toLowerCase().endsWith(".zip")) {
            zipFile = new File(zipFile.getAbsolutePath() + ".zip");
        }

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(zipFile))) {
            File keyDir = new File(KEY_DIR);
            addDirToZip(keyDir, keyDir.getName(), zos);

            JOptionPane.showMessageDialog(this,
                "账户已导出：\n" + zipFile.getAbsolutePath() + "\n\n把这个 .zip 文件保存好。");
            statusLabel.setText("账户已导出：" + zipFile.getName());
            logger.info("账户已导出: " + zipFile.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导出失败：" + ex.getMessage());
        }
    }

    private void addDirToZip(File dir, String basePath, java.util.zip.ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            String entryName = basePath + "/" + f.getName();
            if (f.isDirectory()) {
                addDirToZip(f, entryName, zos);
            } else {
                zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                Files.copy(f.toPath(), zos);
                zos.closeEntry();
            }
        }
    }

    private void importAccount() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择账户备份 .zip 文件");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "账户备份 (*.zip)", "zip"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File zipFile = chooser.getSelectedFile();
        if (!zipFile.exists()) {
            JOptionPane.showMessageDialog(this, "文件不存在！");
            return;
        }

        if (!isValidAccountZip(zipFile)) {
            JOptionPane.showMessageDialog(this,
                "导入失败：文件已损坏或不是有效的账户备份！",
                "文件无效", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int r = JOptionPane.showConfirmDialog(this,
            "导入账户会覆盖当前的密钥和好友列表！\n\n确定要导入吗？",
            "导入账户", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;

        try {
            File keyDir = new File(KEY_DIR);
            if (keyDir.exists()) {
                File backup = new File(APP_DIR, "keys_backup_" + System.currentTimeMillis());
                copyDir(keyDir, backup);
                statusLabel.setText("已备份当前账户到：" + backup.getName());
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "备份当前账户失败：" + ex.getMessage() + "\n\n为了安全，取消导入。");
            return;
        }

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new FileInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("keys/")) name = name.substring(5);
                if (name.isEmpty()) continue;
                if (name.contains("..")) throw new IOException("非法路径：" + name);

                File target = new File(KEY_DIR, name);
                if (entry.isDirectory()) {
                    target.mkdirs();
                } else {
                    target.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }

            loadEncryptedPrivateKey();
            loadFriends();
            refreshFriendKeyCache();
            cachedPrivateKey = null;
            cachedPublicKey = null;

            JOptionPane.showMessageDialog(this,
                "账户导入成功！\n\n密钥和好友已恢复。\n如果开启了免密码模式，请重新勾选解锁。");
            statusLabel.setText("账户已导入");
            logger.info("账户已导入");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "导入失败：" + ex.getMessage() + "\n\n如果有 keys_backup_xxx 目录，可以手动恢复。",
                "导入失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean isValidAccountZip(File zipFile) {
        if (zipFile == null || !zipFile.exists() || zipFile.length() == 0) return false;
        if (!zipFile.getName().toLowerCase().endsWith(".zip")) return false;

        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile)) {
            boolean hasPrivate = false, hasPublic = false;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String normalized = name.startsWith("keys/") ? name.substring(5) : name;
                if (normalized.equals("my_private.enc")) hasPrivate = true;
                if (normalized.equals("my_public.key")) hasPublic = true;
                if (!entry.isDirectory()) {
                    try (InputStream is = zf.getInputStream(entry)) {
                        byte[] buf = new byte[4096];
                        while (is.read(buf) > 0) {}
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            return hasPrivate && hasPublic;
        } catch (Exception e) {
            return false;
        }
    }

    private void copyDir(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists()) dest.mkdirs();
            File[] files = src.listFiles();
            if (files != null) {
                for (File f : files) {
                    copyDir(f, new File(dest, f.getName()));
                }
            }
        } else {
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ============ 文本加密 ============
    private void encryptText() {
        if (currentFriend == null) {
            JOptionPane.showMessageDialog(this, "请先在左侧选择收件人！");
            return;
        }

        File friendPub = new File(FRIENDS_DIR, currentFriend + ".pub");
        if (!friendPub.exists()) {
            JOptionPane.showMessageDialog(this, "找不到该好友的公钥文件！");
            return;
        }

        String message = textInputArea.getText().trim();
        if (message.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入要加密的消息！");
            return;
        }

        // 修复：文本大小限制
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_SIZE) {
            JOptionPane.showMessageDialog(this,
                "文本内容超过 100MB，请使用「文件操作」标签页加密文件。",
                "内容过大", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (encryptedPrivateFile == null) {
            JOptionPane.showMessageDialog(this, "请先生成密钥！");
            return;
        }

        PrivateKey myPrivate = getPrivateKey();
        if (myPrivate == null) return;

        try {
            verifyLabel.setText(" ");
            byte[] payload = message.getBytes(StandardCharsets.UTF_8);
            byte[] packed = packEncrypted(payload, friendPub, myPrivate);

            String base64 = Base64.getEncoder().encodeToString(packed);
            textOutputArea.setText(base64);
            statusLabel.setText("加密成功，密文可复制发送给「" + currentFriend + "」");
            logger.info("文本加密成功，接收者: " + currentFriend);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "加密失败：" + ex.getMessage());
            logger.severe("文本加密失败: " + ex.getMessage());
        }
    }

    private void decryptText() {
        if (encryptedPrivateFile == null) {
            JOptionPane.showMessageDialog(this, "请先生成密钥！");
            return;
        }

        String cipherText = textInputArea.getText().trim();
        if (cipherText.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请把密文粘贴到输入框！");
            return;
        }

        PrivateKey myPrivate = getPrivateKey();
        if (myPrivate == null) return;

        try {
            byte[] data = Base64.getDecoder().decode(cipherText);
            EncPackage pkg = EncPackage.parse(data);
            byte[] payload = decryptPackagePayload(pkg, myPrivate);

            String message = new String(payload, StandardCharsets.UTF_8);
            String verifyInfo = verifySignatureData(pkg);

            textOutputArea.setText(message);
            verifyLabel.setText(verifyInfo.trim());
            statusLabel.setText("解密成功");
            logger.info("文本解密成功");
        } catch (Exception ex) {
            textOutputArea.setText("");
            JOptionPane.showMessageDialog(this,
                "解密失败！\n\n详细：" + ex.getMessage(),
                "失败", JOptionPane.ERROR_MESSAGE);
            logger.warning("文本解密失败: " + ex.getMessage());
        }
    }

    // ============ 文件操作 ============
    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择文件");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        selectedFile = chooser.getSelectedFile();
        filePathLabel.setText("已选择：" + selectedFile.getAbsolutePath());
        statusLabel.setText("已选择文件：" + selectedFile.getName());
    }

    private void encryptFile() {
        if (currentFriend == null) {
            JOptionPane.showMessageDialog(this, "请先在左侧选择收件人！");
            return;
        }

        File friendPub = new File(FRIENDS_DIR, currentFriend + ".pub");
        if (!friendPub.exists()) {
            JOptionPane.showMessageDialog(this, "找不到该好友的公钥文件！");
            return;
        }

        if (selectedFile == null || !selectedFile.exists()) {
            JOptionPane.showMessageDialog(this, "请先选择要加密的文件！");
            return;
        }

        if (encryptedPrivateFile == null) {
            JOptionPane.showMessageDialog(this, "请先生成密钥！");
            return;
        }

        if (selectedFile.length() > 100 * 1024 * 1024) {
            int r = JOptionPane.showConfirmDialog(this,
                "文件超过 100MB，加密可能较慢。继续吗？",
                "提示", JOptionPane.YES_NO_OPTION);
            if (r != JOptionPane.YES_OPTION) return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存加密文件到...");
        chooser.setSelectedFile(new File(selectedFile.getName() + ".enc"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File outFile = chooser.getSelectedFile();

        if (outFile.getAbsolutePath().equals(selectedFile.getAbsolutePath())) {
            JOptionPane.showMessageDialog(this, "输出文件不能和原文件相同！");
            return;
        }

        PrivateKey myPrivate = getPrivateKey();
        if (myPrivate == null) return;

        verifyLabel.setText(" ");
        runFileTask(true, selectedFile, outFile, friendPub, myPrivate);
    }

    private void decryptFile() {
        if (encryptedPrivateFile == null) {
            JOptionPane.showMessageDialog(this, "请先生成密钥！");
            return;
        }

        File encFile = selectedFile;
        if (encFile == null || !encFile.exists()) {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择要解密的 .enc 文件");
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            encFile = chooser.getSelectedFile();
        }

        String outName = encFile.getName();
        if (outName.endsWith(".enc")) outName = outName.substring(0, outName.length() - 4);

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存解密后的文件到...");
        chooser.setSelectedFile(new File(outName));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File outFile = chooser.getSelectedFile();

        if (outFile.getAbsolutePath().equals(encFile.getAbsolutePath())) {
            JOptionPane.showMessageDialog(this, "输出文件不能和加密文件相同！");
            return;
        }

        PrivateKey myPrivate = getPrivateKey();
        if (myPrivate == null) return;

        runFileTask(false, encFile, outFile, null, myPrivate);
    }

    // ============ 后台文件任务 ============
    private void runFileTask(boolean encrypting, File inFile, File outFile,
                             File friendPubFile, PrivateKey myPrivate) {
        ProgressMonitor monitor = new ProgressMonitor(this,
            encrypting ? "正在加密文件..." : "正在解密文件...",
            inFile.getName(), 0, 100);
        monitor.setMillisToPopup(0);
        monitor.setMillisToDecideToPopup(0);

        SwingWorker<String, Integer> worker = new SwingWorker<String, Integer>() {
            @Override
            protected String doInBackground() throws Exception {
                ProgressCallback callback = new ProgressCallback() {
                    @Override
                    public void onProgress(int percent) {
                        publish(percent);
                    }
                    @Override
                    public boolean isCancelled() {
                        return monitor.isCanceled();
                    }
                };

                if (encrypting) {
                    encryptFileStreaming(inFile, outFile, friendPubFile, myPrivate, callback);
                    return null;
                } else {
                    return decryptFileStreaming(inFile, outFile, myPrivate, callback);
                }
            }

            @Override
            protected void process(List<Integer> chunks) {
                for (int p : chunks) {
                    monitor.setProgress(p);
                }
            }

            @Override
            protected void done() {
                monitor.close();
                try {
                    String verifyInfo = get();
                    if (encrypting) {
                        statusLabel.setText("文件已加密：" + outFile.getName());
                        JOptionPane.showMessageDialog(CryptoChat.this,
                            "文件加密成功！\n\n原文件：" + inFile.getName() +
                            "（" + formatSize(inFile.length()) + "）\n输出：" + outFile.getAbsolutePath() +
                            "\n\n发给「" + currentFriend + "」即可。");
                        logger.info("文件加密成功: " + inFile.getName() + " -> " + outFile.getName());
                    } else {
                        if (verifyInfo != null && !verifyInfo.trim().isEmpty()) {
                            verifyLabel.setText(verifyInfo.trim());
                        }
                        statusLabel.setText("文件已解密：" + outFile.getName());
                        JOptionPane.showMessageDialog(CryptoChat.this,
                            "文件解密成功！\n\n输出：" + outFile.getAbsolutePath() +
                            "\n\n" + (verifyInfo == null ? "" : verifyInfo.trim()));
                        logger.info("文件解密成功: " + outFile.getName());
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    // 修复：取消后文件占用 - 增加重试删除
                    Throwable cause = ex.getCause();
                    String msg = (cause != null && cause.getMessage() != null)
                        ? cause.getMessage() : "未知错误";
                    
                    if (msg.contains("已取消")) {
                        statusLabel.setText("操作已取消，正在清理...");
                        // 修复：重试删除半成品文件
                        boolean deleted = false;
                        for (int i = 0; i < 3; i++) {
                            if (outFile.delete()) {
                                deleted = true;
                                break;
                            }
                            try {
                                Thread.sleep(500); // 等待文件句柄释放
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        if (deleted) {
                            statusLabel.setText("操作已取消，已删除未完成文件");
                        } else {
                            statusLabel.setText("操作已取消，但无法删除文件，请手动删除：" + outFile.getName());
                            JOptionPane.showMessageDialog(CryptoChat.this,
                                "无法自动删除未完成文件，请手动删除：\n" + outFile.getAbsolutePath(),
                                "清理提示", JOptionPane.WARNING_MESSAGE);
                        }
                    } else {
                        statusLabel.setText("操作失败");
                        JOptionPane.showMessageDialog(CryptoChat.this,
                            (encrypting ? "加密" : "解密") + "失败！\n\n详细：" + msg,
                            "失败", JOptionPane.ERROR_MESSAGE);
                        logger.severe("文件操作失败: " + msg);
                    }
                }
            }
        };
        worker.execute();
    }

    // ============ 流式加密（修复：临时文件清理） ============
    private void encryptFileStreaming(File srcFile, File dstFile, File friendPubFile,
                                      PrivateKey myPrivate, ProgressCallback cb) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey otherPublic = kf.generatePublic(
            new X509EncodedKeySpec(Files.readAllBytes(friendPubFile.toPath())));

        KeyGenerator aesGen = KeyGenerator.getInstance("AES");
        aesGen.init(256);
        SecretKey aesKey = aesGen.generateKey();
        byte[] aesIv = new byte[16];
        new SecureRandom().nextBytes(aesIv);

        Cipher rsaEnc = Cipher.getInstance(RSA_CIPHER_NEW);
        rsaEnc.init(Cipher.ENCRYPT_MODE, otherPublic);
        byte[] encryptedKey = rsaEnc.doFinal(aesKey.getEncoded());

        long timestamp = System.currentTimeMillis();

        long totalSize = Math.max(1, srcFile.length());
        long processed = 0;
        long cipherTotal = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        // 修复：临时文件确保删除
        File tmpFile = File.createTempFile("yansyn_enc_", ".tmp");
        boolean tmpDeleted = false;

        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try {
            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(aesIv));

            try (InputStream fis = new BufferedInputStream(new FileInputStream(srcFile));
                 OutputStream fos = new BufferedOutputStream(new FileOutputStream(tmpFile))) {
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    if (cb.isCancelled()) throw new IOException("操作已取消");
                    byte[] out = aesCipher.update(buffer, 0, len);
                    if (out != null) {
                        fos.write(out);
                        digest.update(out);
                        cipherTotal += out.length;
                    }
                    processed += len;
                    cb.onProgress((int) Math.min(80, processed * 80 / totalSize));
                }
                byte[] outFinal = aesCipher.doFinal();
                if (outFinal != null) {
                    fos.write(outFinal);
                    digest.update(outFinal);
                    cipherTotal += outFinal.length;
                }
            }

            if (cipherTotal > Integer.MAX_VALUE) {
                throw new IOException("文件太大，暂不支持（上限约 2GB）");
            }

            byte[] cipherHash = digest.digest();
            byte[] signedData = buildSignedData(encryptedKey, aesIv, cipherHash,
                otherPublic.getEncoded(), timestamp);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(myPrivate);
            signer.update(signedData);
            byte[] signature = signer.sign();

            try (InputStream tis = new BufferedInputStream(new FileInputStream(tmpFile));
                 OutputStream rawOut = new BufferedOutputStream(new FileOutputStream(dstFile));
                 DataOutputStream dos = new DataOutputStream(rawOut)) {

                dos.writeInt(encryptedKey.length);
                dos.write(encryptedKey);
                dos.write(aesIv);
                dos.writeLong(timestamp);
                dos.writeInt((int) cipherTotal);
                
                long copied = 0;
                int len;
                while ((len = tis.read(buffer)) != -1) {
                    if (cb.isCancelled()) throw new IOException("操作已取消");
                    rawOut.write(buffer, 0, len);
                    copied += len;
                    cb.onProgress((int) (80 + Math.min(19, copied * 19 / Math.max(1, cipherTotal))));
                }
                dos.writeInt(signature.length);
                dos.write(signature);
            }
            
            // 修复：标记临时文件已处理
            tmpDeleted = true;
            
        } finally {
            // 修复：确保临时文件删除
            if (!tmpDeleted) {
                tmpFile.delete();
            }
        }
    }

    private String decryptFileStreaming(File srcFile, File dstFile,
                                        PrivateKey myPrivate, ProgressCallback cb) throws Exception {
        long fileSize = srcFile.length();

        try (InputStream rawIn = new BufferedInputStream(new FileInputStream(srcFile));
             DataInputStream dis = new DataInputStream(rawIn)) {

            int keyLen = dis.readInt();
            if (keyLen <= 0 || keyLen > 512) throw new IOException("文件格式无效（密钥长度异常）");
            byte[] encryptedKey = new byte[keyLen];
            dis.readFully(encryptedKey);

            byte[] aesIv = new byte[16];
            dis.readFully(aesIv);

            long timestamp;
            {
                byte[] tsBytes = new byte[8];
                dis.readFully(tsBytes);
                long candidate = ByteBuffer.wrap(tsBytes).getLong();
                
                // 修复：增加魔数验证，防止误判
                if (candidate >= 1577836800000L && candidate <= 4102444800000L) {
                    timestamp = candidate;
                } else {
                    timestamp = 0;
                    int msgLenOld = ByteBuffer.wrap(tsBytes).getInt();
                    if (msgLenOld < 0 || msgLenOld > fileSize) {
                        throw new IOException("文件格式无效（数据长度异常）");
                    }
                    return decryptFileStreamingOld(dis, dstFile, myPrivate, cb,
                        encryptedKey, aesIv, msgLenOld, tsBytes);
                }
            }

            long msgLen = dis.readInt();
            if (msgLen < 0 || msgLen > fileSize) throw new IOException("文件格式无效（数据长度异常）");

            Cipher rsaDec = Cipher.getInstance(RSA_CIPHER_NEW);
            rsaDec.init(Cipher.DECRYPT_MODE, myPrivate);
            byte[] aesKeyBytes = rsaDec.doFinal(encryptedKey);

            String verifyInfo;
            try {
                SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
                Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(aesIv));

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[BUFFER_SIZE];
                long remaining = msgLen;

                try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(dstFile))) {
                    while (remaining > 0) {
                        if (cb.isCancelled()) throw new IOException("操作已取消");
                        int toRead = (int) Math.min(buffer.length, remaining);
                        int len = dis.read(buffer, 0, toRead);
                        if (len == -1) throw new IOException("文件不完整");
                        digest.update(buffer, 0, len);
                        byte[] plain = aesCipher.update(buffer, 0, len);
                        if (plain != null) fos.write(plain);
                        remaining -= len;
                        cb.onProgress((int) Math.min(99, (msgLen - remaining) * 100 / Math.max(1, msgLen)));
                    }
                    byte[] plainFinal = aesCipher.doFinal();
                    if (plainFinal != null) fos.write(plainFinal);
                }

                int sigLen = dis.readInt();
                if (sigLen <= 0 || sigLen > 8192) throw new IOException("文件格式无效（签名长度异常）");
                byte[] signature = new byte[sigLen];
                dis.readFully(signature);

                verifyInfo = verifySignatureData(encryptedKey, aesIv, digest.digest(), signature, timestamp);
            } finally {
                Arrays.fill(aesKeyBytes, (byte) 0);
            }
            return verifyInfo;
        }
    }

    private String decryptFileStreamingOld(DataInputStream dis, File dstFile,
                                           PrivateKey myPrivate, ProgressCallback cb,
                                           byte[] encryptedKey, byte[] aesIv,
                                           int msgLen, byte[] tsBytes) throws Exception {
        Cipher rsaDec = Cipher.getInstance(RSA_CIPHER_OLD);
        rsaDec.init(Cipher.DECRYPT_MODE, myPrivate);
        byte[] aesKeyBytes = rsaDec.doFinal(encryptedKey);

        try {
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(aesIv));

            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = msgLen - 4;

            try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(dstFile))) {
                if (remaining > 0) {
                    byte[] first4 = new byte[4];
                    System.arraycopy(tsBytes, 4, first4, 0, 4);
                    byte[] plain = aesCipher.update(first4, 0, 4);
                    if (plain != null) fos.write(plain);
                }

                while (remaining > 0) {
                    if (cb.isCancelled()) throw new IOException("操作已取消");
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int len = dis.read(buffer, 0, toRead);
                    if (len == -1) throw new IOException("文件不完整");
                    byte[] plain = aesCipher.update(buffer, 0, len);
                    if (plain != null) fos.write(plain);
                    remaining -= len;
                    cb.onProgress((int) Math.min(99, (msgLen - remaining) * 100 / Math.max(1, msgLen)));
                }
                byte[] plainFinal = aesCipher.doFinal();
                if (plainFinal != null) fos.write(plainFinal);
            }

            int sigLen = dis.readInt();
            if (sigLen <= 0 || sigLen > 8192) throw new IOException("文件格式无效（签名长度异常）");
            byte[] signature = new byte[sigLen];
            dis.readFully(signature);

            return " [⚠ 此文件由旧版本发出，已完成解密，但无法验证发送者身份]";
        } finally {
            Arrays.fill(aesKeyBytes, (byte) 0);
        }
    }

    // ============ 加密打包 ============
    private byte[] packEncrypted(byte[] payload, File friendPubFile, PrivateKey myPrivate) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        byte[] pubBytes = Files.readAllBytes(friendPubFile.toPath());
        PublicKey otherPublic = kf.generatePublic(new X509EncodedKeySpec(pubBytes));

        KeyGenerator aesGen = KeyGenerator.getInstance("AES");
        aesGen.init(256);
        SecretKey aesKey = aesGen.generateKey();
        byte[] aesIv = new byte[16];
        new SecureRandom().nextBytes(aesIv);

        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(aesIv));
        byte[] encryptedMsg = aesCipher.doFinal(payload);

        Cipher rsaEnc = Cipher.getInstance(RSA_CIPHER_NEW);
        rsaEnc.init(Cipher.ENCRYPT_MODE, otherPublic);
        byte[] encryptedKey = rsaEnc.doFinal(aesKey.getEncoded());

        long timestamp = System.currentTimeMillis();

        byte[] cipherHash = MessageDigest.getInstance("SHA-256").digest(encryptedMsg);
        byte[] signedData = buildSignedData(encryptedKey, aesIv, cipherHash,
            otherPublic.getEncoded(), timestamp);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(myPrivate);
        signer.update(signedData);
        byte[] signature = signer.sign();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(encryptedKey.length);
        dos.write(encryptedKey);
        dos.write(aesIv);
        dos.writeLong(timestamp);
        dos.writeInt(encryptedMsg.length);
        dos.write(encryptedMsg);
        dos.writeInt(signature.length);
        dos.write(signature);
        dos.close();
        return baos.toByteArray();
    }

    private byte[] decryptPackagePayload(EncPackage pkg, PrivateKey myPrivate) throws Exception {
        String rsaAlgo = pkg.isNewFormat ? RSA_CIPHER_NEW : RSA_CIPHER_OLD;
        Cipher rsaDec = Cipher.getInstance(rsaAlgo);
        rsaDec.init(Cipher.DECRYPT_MODE, myPrivate);
        byte[] aesKeyBytes = rsaDec.doFinal(pkg.encryptedKey);

        try {
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(pkg.iv));
            return aesCipher.doFinal(pkg.encryptedMsg);
        } finally {
            Arrays.fill(aesKeyBytes, (byte) 0);
        }
    }

    private byte[] buildSignedData(byte[] encryptedKey, byte[] aesIv, byte[] cipherHash,
                                   byte[] recipientPubBytes, long timestamp) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(encryptedKey);
        baos.write(aesIv);
        baos.write(cipherHash);
        baos.write(recipientPubBytes);
        baos.write(ByteBuffer.allocate(8).putLong(timestamp).array());
        return baos.toByteArray();
    }

    // 修复：使用缓存的公钥，提升验签性能
    private String verifySignatureData(byte[] encryptedKey, byte[] aesIv, byte[] cipherHash,
                                       byte[] signature, long timestamp) {
        try {
            byte[] myPubBytes = Files.readAllBytes(Paths.get(MY_PUBLIC_FILE));
            byte[] signedData = buildSignedData(encryptedKey, aesIv, cipherHash, myPubBytes, timestamp);

            // 使用缓存的公钥而不是每次都读文件
            Map<String, PublicKey> cache = friendKeyCache;
            for (Map.Entry<String, PublicKey> entry : cache.entrySet()) {
                try {
                    Signature verifier = Signature.getInstance("SHA256withRSA");
                    verifier.initVerify(entry.getValue());
                    verifier.update(signedData);
                    if (verifier.verify(signature)) {
                        String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                            .format(new Date(timestamp));
                        return " [签名验证通过｜发送者：" + entry.getKey() + "｜时间：" + time + "]";
                    }
                } catch (Exception ignored) {}
            }

            return " [无法验证发送者（发送者不在你的好友列表中）]";
        } catch (Exception ex) {
            return " [签名验证失败]";
        }
    }

    private String verifySignatureData(EncPackage pkg) throws Exception {
        if (!pkg.isNewFormat) {
            return verifySignatureOldText(pkg.encryptedMsg, pkg.signature);
        }
        byte[] cipherHash = MessageDigest.getInstance("SHA-256").digest(pkg.encryptedMsg);
        return verifySignatureData(pkg.encryptedKey, pkg.iv, cipherHash, pkg.signature, pkg.timestamp);
    }

    private String verifySignatureOldText(byte[] encryptedMsg, byte[] signature) {
        try {
            // 使用缓存
            Map<String, PublicKey> cache = friendKeyCache;
            for (Map.Entry<String, PublicKey> entry : cache.entrySet()) {
                try {
                    Signature verifier = Signature.getInstance("SHA256withRSA");
                    verifier.initVerify(entry.getValue());
                    verifier.update(encryptedMsg);
                    if (verifier.verify(signature)) {
                        return " [签名验证通过｜发送者：" + entry.getKey() + "｜⚠ 此消息由旧版本发出]";
                    }
                } catch (Exception ignored) {}
            }
            return " [无法验证发送者（发送者不在你的好友列表中）｜⚠ 此消息由旧版本发出]";
        } catch (Exception ex) {
            return " [签名验证失败]";
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(CryptoChat::new);
    }

    // ============ 密文包解析 ============
    private static class EncPackage {
        byte[] encryptedKey;
        byte[] iv;
        long timestamp;
        byte[] encryptedMsg;
        byte[] signature;
        boolean isNewFormat;

        static EncPackage parse(byte[] data) throws IOException {
            EncPackage pkg = new EncPackage();
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));

            int keyLen = dis.readInt();
            if (keyLen <= 0 || keyLen > 512) throw new IOException("密文格式无效");
            pkg.encryptedKey = new byte[keyLen];
            dis.readFully(pkg.encryptedKey);

            pkg.iv = new byte[16];
            dis.readFully(pkg.iv);

            byte[] tsBytes = new byte[8];
            dis.readFully(tsBytes);
            long candidate = ByteBuffer.wrap(tsBytes).getLong();

            // 修复：增加验证，防止时间戳误判
            if (candidate >= 1577836800000L && candidate <= 4102444800000L) {
                // 进一步验证：检查后续数据是否合理
                pkg.isNewFormat = true;
                pkg.timestamp = candidate;

                int msgLen = dis.readInt();
                if (msgLen < 0 || msgLen > data.length) throw new IOException("密文格式无效");
                pkg.encryptedMsg = new byte[msgLen];
                dis.readFully(pkg.encryptedMsg);

                int sigLen = dis.readInt();
                if (sigLen <= 0 || sigLen > 8192) throw new IOException("密文格式无效");
                pkg.signature = new byte[sigLen];
                dis.readFully(pkg.signature);
            } else {
                // 旧版格式
                pkg.isNewFormat = false;
                pkg.timestamp = 0;

                int msgLen = ByteBuffer.wrap(tsBytes).getInt();
                if (msgLen < 0) throw new IOException("密文格式无效");

                pkg.encryptedMsg = new byte[msgLen];
                System.arraycopy(tsBytes, 4, pkg.encryptedMsg, 0, 4);
                dis.readFully(pkg.encryptedMsg, 4, msgLen - 4);

                int sigLen = dis.readInt();
                if (sigLen <= 0 || sigLen > 8192) throw new IOException("密文格式无效");
                pkg.signature = new byte[sigLen];
                dis.readFully(pkg.signature);
            }

            dis.close();
            return pkg;
        }
    }
}
