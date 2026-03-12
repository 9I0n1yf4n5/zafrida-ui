package com.zafrida.ui.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.zafrida.ui.adb.AdbService;
import com.zafrida.ui.api.ZaFridaLocalHttpApiService;
import com.zafrida.ui.diagnostics.EnvironmentDoctorDialog;
import com.intellij.icons.AllIcons;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.ActionLink;
import com.zafrida.ui.frida.*;
import com.zafrida.ui.fridaproject.*;
import com.zafrida.ui.fridaproject.ui.CreateZaFridaProjectDialog;
import com.zafrida.ui.fridaproject.ui.ZaFridaProjectSettingsDialog;
import com.zafrida.ui.session.RunningSession;
import com.zafrida.ui.session.ZaFridaSessionService;
import com.zafrida.ui.session.ZaFridaSessionType;
import com.zafrida.ui.python.ProjectPythonEnvResolver;
import com.zafrida.ui.python.PythonEnvInfo;
import com.zafrida.ui.ui.components.SearchableComboBoxPanel;
import com.zafrida.ui.ui.components.SimpleDocumentListener;
import com.zafrida.ui.ui.render.DeviceCellRenderer;
import com.zafrida.ui.util.ProjectFileUtil;
import com.zafrida.ui.util.ZaFridaNetUtil;
import com.zafrida.ui.util.ZaFridaIcons;
import com.zafrida.ui.util.ZaFridaNotifier;
import com.zafrida.ui.util.ZaFridaTextUtil;
import com.zafrida.ui.util.ZaStrUtil;
import com.zafrida.ui.settings.ZaFridaSettingsService;
import com.zafrida.ui.settings.ZaFridaSettingsState;
import com.intellij.util.io.HttpRequests;
import com.intellij.util.text.VersionComparatorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jdom.Document;
import org.jdom.Element;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * [UI组件] 运行控制主面板。
 * <p>
 * <strong>功能聚合：</strong>
 * 集成了设备选择、脚本选择、目标设置以及运行控制按钮。
 * <p>
 * <strong>数据流：</strong>
 * UI 操作 -> 更新 {@link ZaFridaProjectConfig} -> 调用 {@link FridaCliService} 执行命令。
 * <p>
 * <strong>注意：</strong>
 * 刷新设备列表操作 {@link #reloadDevicesAsync()} 必须在后台线程执行，避免阻塞 EDT。
 */
public final class ZaFridaRunPanel extends JPanel implements Disposable {

    private static final String PLUGIN_ID = "com.zafrida.ui";
    private static final String MARKETPLACE_PLUGIN_DETAILS_URL =
            String.format("https://plugins.jetbrains.com/plugins/list?pluginId=%s", PLUGIN_ID);
    private static final String USB_DEVICE_TYPE = "usb";
    private static final String ADB_SHELL_COMMAND = "adb shell";

    /** IDE 项目实例 */
    private final @NotNull Project project;
    /** 控制台选项卡面板 */
    private final @NotNull ZaFridaConsoleTabsPanel consoleTabsPanel;
    /** Run 控制台面板 */
    private final @NotNull ZaFridaConsolePanel runConsolePanel;
    /** Attach 控制台面板 */
    private final @NotNull ZaFridaConsolePanel attachConsolePanel;
    /** 模板面板 */
    private final @NotNull ZaFridaTemplatePanel templatePanel;

    /** Frida CLI 服务 */
    private final @NotNull FridaCliService fridaCli;
    /** 会话服务 */
    private final @NotNull ZaFridaSessionService sessionService;
    /** ADB 服务 */
    private final @NotNull AdbService adbService;
    /** 本地 HTTP API 服务 */
    private final @NotNull ZaFridaLocalHttpApiService localHttpApiService;

    /** 设备下拉框 */
    private final ComboBox<FridaDevice> deviceCombo = new ComboBox<>();
    /** 刷新设备按钮 */
    private final JButton refreshDevicesBtn = new JButton("");
    /** 添加远程设备按钮 */
    private final JButton addRemoteBtn = new JButton("");

    /** Run 脚本输入框 */
    private final JBTextField runScriptField = new JBTextField();
    /** 定位 Run 脚本按钮 */
    private final JButton locateRunScriptBtn = new JButton("");
    /** 选择 Run 脚本按钮 */
    private final JButton chooseRunScriptBtn = new JButton("");
    /** Attach 脚本输入框 */
    private final JBTextField attachScriptField = new JBTextField();
    /** 定位 Attach 脚本按钮 */
    private final JButton locateAttachScriptBtn = new JButton("");
    /** 选择 Attach 脚本按钮 */
    private final JButton chooseAttachScriptBtn = new JButton("");

    /** 目标包名/进程输入框 */
    private final JBTextField targetField = new JBTextField();

    /** 额外参数输入框 */
    private final JBTextField extraArgsField = new JBTextField();

    /** Run 按钮 */
    private final JButton runBtn = new JButton("Run");
    /** Stop 按钮 */
    private final JButton stopBtn = new JButton("Stop");
    /** Attach 按钮 */
    private final JButton attachBtn = new JButton("Attach");
    /** Stop Attach 按钮 */
    private final JButton stopAttachBtn = new JButton("StopAttach");
    /** 强制停止按钮 */
    private final JButton forceStopBtn = new JButton("App");
    /** 打开 App 按钮 */
    private final JButton openAppBtn = new JButton("App");

    /** 插件版本显示 */
    private final JLabel versionValueLabel = new JLabel();
    /** 更新提示链接 */
    private final ActionLink updateLink = new ActionLink("Update available", (ActionListener) e -> openPluginUpdates());

    /** Run 脚本文件 */
    private @Nullable VirtualFile runScriptFile;
    /** Attach 脚本文件 */
    private @Nullable VirtualFile attachScriptFile;
    /** 当前项目目录（后台加载） */
    private @Nullable VirtualFile activeProjectDir;
    /** 最后一次应用到 UI 的项目 */
    private @Nullable ZaFridaFridaProject lastAppliedProject;
    /** 等待项目切换完成后的待执行动作 */
    private @Nullable PendingProjectAction pendingProjectAction;

    /** 是否已输出工具链信息 */
    private boolean printedToolchainInfo = false;
    /** 当前插件版本 */
    private @Nullable String currentPluginVersion;
    /** 是否已触发更新检查 */
    private boolean updateCheckStarted = false;
    /** 是否已提示 USB 设备为空 */
    private boolean warnedNoUsbDevices = false;
    /** 上次提示缺失的 USB 设备 ID */
    private @Nullable String lastMissingUsbDeviceId;

    /** ZAFrida 项目管理器 */
    private final ZaFridaProjectManager fridaProjectManager;
    /** ZAFrida 项目选择器 */
    private final SearchableComboBoxPanel<ZaFridaFridaProject> fridaProjectSelector =
            new SearchableComboBoxPanel<>(p -> p == null ? "" : p.getName());
    /** 项目平台图标 */
    private final JLabel projectTypeIcon = new JLabel();
    /** 项目选择器是否在更新中 */
    private boolean updatingFridaProjectSelector = false;
    /** 设备列表是否在更新中 */
    private boolean updatingDeviceCombo = false;
    /** 运行字段是否在更新中 */
    private boolean updatingRunFields = false;
    /** 外部 Run 按钮（Header 中） */
    private @Nullable JButton externalRunBtn;
    /** 外部 Stop 按钮（Header 中） */
    private @Nullable JButton externalStopBtn;


    /**
     * 构造函数。
     * @param project 当前 IDE 项目
     * @param consoleTabsPanel 控制台选项卡面板
     * @param templatePanel 模板面板
     */
    public ZaFridaRunPanel(@NotNull Project project,
                           @NotNull ZaFridaConsoleTabsPanel consoleTabsPanel,
                           @NotNull ZaFridaTemplatePanel templatePanel) {
        super(new BorderLayout());
        this.project = project;
        this.consoleTabsPanel = consoleTabsPanel;
        this.runConsolePanel = consoleTabsPanel.getRunConsolePanel();
        this.attachConsolePanel = consoleTabsPanel.getAttachConsolePanel();
        this.templatePanel = templatePanel;

        this.fridaCli = ApplicationManager.getApplication().getService(FridaCliService.class);
        this.sessionService = project.getService(ZaFridaSessionService.class);
        this.adbService = ApplicationManager.getApplication().getService(AdbService.class);
        this.fridaProjectManager = project.getService(ZaFridaProjectManager.class);
        this.localHttpApiService = project.getService(ZaFridaLocalHttpApiService.class);


        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        int row = 0;
        row = addRow(form, row, new JLabel("Project"), buildFridaProjectRow());
        row = addRow(form, row, new JLabel("Device"), buildDeviceRow());
        row = addRow(form, row, new JLabel("Run Script"), buildRunScriptRow());
        row = addRow(form, row, new JLabel("Attach Script"), buildAttachScriptRow());
        row = addRow(form, row, new JLabel("Target"), buildTargetRow());
        row = addRow(form, row, new JLabel("Extra"), buildExtraRow());
        row = addRow(form, row, new JLabel(""), buildButtonsRow());

        add(form, BorderLayout.NORTH);

        initUiState();
        bindActions();
        subscribeToFridaProjectChanges();
        reloadFridaProjectsIntoUi();
        applyActiveFridaProjectToUi(fridaProjectManager.getActiveProject());
        localHttpApiService.bindRunPanel(this);
    }

    /**
     * 初始化 UI 状态与默认属性。
     */
    private void initUiState() {
        deviceCombo.setRenderer(new DeviceCellRenderer());
        runScriptField.setEditable(false);
        attachScriptField.setEditable(false);
        runScriptField.setColumns(23);
        attachScriptField.setColumns(23);

        extraArgsField.setToolTipText("Extra args passed to frida, e.g. --realm=emulated");
        projectTypeIcon.setToolTipText("Project platform");

        targetField.setColumns(23);
        extraArgsField.setColumns(23);
        targetField.setToolTipText("Spawn/Attach uses package name");

        refreshDevicesBtn.setIcon(AllIcons.Actions.Refresh);
        refreshDevicesBtn.setToolTipText("Refresh devices");
        addRemoteBtn.setIcon(AllIcons.General.Add);
        addRemoteBtn.setToolTipText("Add remote host");
        locateRunScriptBtn.setIcon(AllIcons.General.Locate);
        locateRunScriptBtn.setToolTipText("Locate run script in Project View");
        chooseRunScriptBtn.setIcon(AllIcons.Actions.MenuOpen);
        chooseRunScriptBtn.setToolTipText("Choose run script");
        locateAttachScriptBtn.setIcon(AllIcons.General.Locate);
        locateAttachScriptBtn.setToolTipText("Locate attach script in Project View");
        chooseAttachScriptBtn.setIcon(AllIcons.Actions.MenuOpen);
        chooseAttachScriptBtn.setToolTipText("Choose attach script");
        runBtn.setIcon(AllIcons.Actions.Execute);
        runBtn.setToolTipText("Start Run session");
        attachBtn.setIcon(AllIcons.Actions.Execute);
        attachBtn.setToolTipText("Start Attach session");
        stopBtn.setIcon(AllIcons.Actions.Suspend);
        stopBtn.setToolTipText("Stop Run session");
        stopAttachBtn.setIcon(AllIcons.Actions.Suspend);
        stopAttachBtn.setToolTipText("Stop Attach session");
        forceStopBtn.setIcon(AllIcons.Actions.Cancel);
        forceStopBtn.setToolTipText("Force Stop App (adb force-stop)");
        openAppBtn.setIcon(AllIcons.Actions.Execute);
        openAppBtn.setToolTipText("Open App (adb)");
        initVersionInfo();
        updateRunningState();
    }

    /**
     * 初始化插件版本与更新提示。
     */
    private void initVersionInfo() {
        currentPluginVersion = resolveCurrentPluginVersion();
        versionValueLabel.setText(currentPluginVersion != null ? currentPluginVersion : "unknown");
        updateLink.setVisible(false);
        updateLink.setIcon(AllIcons.General.Warning);
        updateLink.setToolTipText("Open Plugins settings to update");
        scheduleUpdateCheck();
    }

    /**
     * 解析当前插件版本。
     */
    private @Nullable String resolveCurrentPluginVersion() {
        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
        return descriptor != null ? descriptor.getVersion() : null;
    }

    /**
     * 后台检查 Marketplace 是否有更新。
     */
    private void scheduleUpdateCheck() {
        if (updateCheckStarted || currentPluginVersion == null) return;
        updateCheckStarted = true;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String latestVersion = fetchMarketplaceLatestVersion();
            if (latestVersion == null || currentPluginVersion == null) return;
            boolean updateAvailable = VersionComparatorUtil.compare(latestVersion, currentPluginVersion) > 0;
            if (!updateAvailable) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                updateLink.setVisible(true);
                updateLink.setToolTipText(String.format("Latest version: %s", latestVersion));
            });
        });
    }

    /**
     * 获取 Marketplace 最新版本号。
     */
    private @Nullable String fetchMarketplaceLatestVersion() {
        try {
            String xml = HttpRequests.request(MARKETPLACE_PLUGIN_DETAILS_URL)
                    .userAgent("ZAFrida-UI")
                    .connectTimeout(5_000)
                    .readTimeout(5_000)
                    .readString();
            return parseMarketplaceLatestVersion(xml);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 解析 Marketplace XML 中的最新版本号。
     */
    private @Nullable String parseMarketplaceLatestVersion(@NotNull String xml) {
        if (ZaStrUtil.isBlank(xml)) return null;
        try {
            Document doc = JDOMUtil.loadDocument(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getRootElement();
            if (root == null) return null;
            List<Element> pluginElements = new ArrayList<>();
            collectElementsByName(root, "idea-plugin", pluginElements);
            String best = null;
            for (Element plugin : pluginElements) {
                String id = plugin.getChildTextTrim("id");
                if (!PLUGIN_ID.equals(id)) {
                    continue;
                }
                String version = plugin.getChildTextTrim("version");
                if (ZaStrUtil.isBlank(version)) continue;
                if (best == null || VersionComparatorUtil.compare(version, best) > 0) {
                    best = version;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void collectElementsByName(@NotNull Element element,
                                              @NotNull String name,
                                              @NotNull List<Element> out) {
        if (name.equals(element.getName())) {
            out.add(element);
        }
        for (Element child : element.getChildren()) {
            collectElementsByName(child, name, out);
        }
    }

    /**
     * 构建项目选择行。
     * @return 行面板
     */
    private JPanel buildFridaProjectRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.add(fridaProjectSelector);
        row.add(projectTypeIcon);
        row.add(versionValueLabel);
        row.add(updateLink);
        return row;
    }

    /**
     * 绑定 UI 交互事件。
     */
    private void bindActions() {
        refreshDevicesBtn.addActionListener(e -> reloadDevicesAsync());

        addRemoteBtn.addActionListener(e -> {
            ZaFridaSettingsState st = ApplicationManager.getApplication()
                    .getService(ZaFridaSettingsService.class)
                    .getState();
            String defHost = ZaFridaNetUtil.defaultHost(st.defaultRemoteHost);
            int defPort = ZaFridaNetUtil.defaultPort(st.defaultRemotePort);
            String initial = String.format("%s:%s", defHost, defPort);

            String host = Messages.showInputDialog(this, "host:port", "Add Frida Remote Host", null, initial, null);
            if (host == null) return;
            String h = host.trim();
            if (h.isEmpty()) return;
            ApplicationManager.getApplication().getService(ZaFridaSettingsService.class).addRemoteHost(h);
            reloadDevicesAsync();
        });

        runBtn.addActionListener(e -> runFrida());
        attachBtn.addActionListener(e -> attachFrida());
        stopBtn.addActionListener(e -> stopRunSession());
        stopAttachBtn.addActionListener(e -> stopAttachSession());
        forceStopBtn.addActionListener(e -> forceStopApp());
        openAppBtn.addActionListener(e -> openApp());

        deviceCombo.addActionListener(e -> {
            if (updatingDeviceCombo) return;
            FridaDevice selected = (FridaDevice) deviceCombo.getSelectedItem();
            if (selected == null) return;
            ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
            if (active == null) return;
            fridaProjectManager.updateProjectConfigAsync(active, cfg -> {
                if (selected.getMode() == FridaDeviceMode.HOST) {
                    cfg.lastDeviceHost = selected.getHost();
                    cfg.lastDeviceId = null;
                } else {
                    cfg.lastDeviceId = selected.getId();
                    cfg.lastDeviceHost = null;
                }
            });
        });

        fridaProjectSelector.addActionListener(e -> {
            if (updatingFridaProjectSelector) return;
            fridaProjectManager.setActiveProjectAsync(fridaProjectSelector.getSelectedItem());
        });

        extraArgsField.getDocument().addDocumentListener(new SimpleDocumentListener(this::persistExtraArgs));

        chooseRunScriptBtn.addActionListener(e -> {
            ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
            VirtualFile initial = resolveInitialScriptSelection(runScriptFile, runScriptField.getText(), activeProjectDir);
            VirtualFile file = chooseRunScriptFile(initial);
            if (file == null) return;

            setRunScriptFile(file);

            if (active != null) {
                fridaProjectManager.updateMainScriptPathAsync(active, file);
            }
        });

        chooseAttachScriptBtn.addActionListener(e -> {
            ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
            VirtualFile initial = resolveInitialScriptSelection(attachScriptFile, attachScriptField.getText(), activeProjectDir);
            VirtualFile file = chooseAttachScriptFile(initial);
            if (file == null) return;

            setAttachScriptFile(file);

            if (active != null) {
                fridaProjectManager.updateAttachScriptPathAsync(active, file);
            }
        });

        locateRunScriptBtn.addActionListener(e -> locateRunScriptInProjectView());
        locateAttachScriptBtn.addActionListener(e -> locateAttachScriptInProjectView());

        consoleTabsPanel.addTabChangeListener(e -> updateRunningState());

    }

    /**
     * 订阅 Frida 项目切换事件。
     */
    private void subscribeToFridaProjectChanges() {
        // 用面板本身作为 Disposable，IDE 关闭 ToolWindow 时会自动断开订阅
        project.getMessageBus().connect(this).subscribe(ZaFridaProjectManager.TOPIC, new ZaFridaProjectListener() {
            @Override
            public void onActiveProjectChanged(@Nullable ZaFridaFridaProject newProject) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    // 项目列表可能被新增/导入，所以这里顺手刷新 selector items
                    reloadFridaProjectsIntoUi();
                    applyActiveFridaProjectToUi(newProject);
                });
            }
        });
    }

    /**
     * 刷新项目列表到 UI。
     */
    private void reloadFridaProjectsIntoUi() {
        updatingFridaProjectSelector = true;
        try {
            List<ZaFridaFridaProject> list = fridaProjectManager.listProjects();
            fridaProjectSelector.setItems(list);
            fridaProjectSelector.setSelectedItem(fridaProjectManager.getActiveProject());

            // 没有项目时，禁用一些按钮（可选）
            boolean has = !list.isEmpty();
            fridaProjectSelector.setEnabled(true);

            if (!has) {
                // 这里不强制清空脚本/target，避免用户临时用“无项目模式”
            }
        } finally {
            updatingFridaProjectSelector = false;
        }
    }

    /**
     * 将激活项目配置应用到 UI。
     * @param active 当前激活项目
     */
    private void applyActiveFridaProjectToUi(@Nullable ZaFridaFridaProject active) {
        updatingFridaProjectSelector = true;
        try {
            fridaProjectSelector.setSelectedItem(active);
        } finally {
            updatingFridaProjectSelector = false;
        }

        updateProjectTypeIcon(active);
        templatePanel.setCurrentPlatform(active == null ? null : active.getPlatform());

        if (active == null) {
            // 不强制清空，让用户仍可用“自由脚本模式”
            targetField.setEnabled(true);
            targetField.setToolTipText(null);
            activeProjectDir = null;
            lastAppliedProject = null;
            pendingProjectAction = null;
            reloadDevicesAsyncWithConfig(null);
            return;
        }

        fridaProjectManager.loadProjectUiStateAsync(active, state -> {
            ZaFridaProjectConfig cfg = state.getConfig();
            activeProjectDir = state.getProjectDir();

            updatingRunFields = true;
            try {
                String extraArgs = cfg.extraArgs;
                if (extraArgs == null) {
                    extraArgs = "";
                }
                extraArgsField.setText(extraArgs);
            } finally {
                updatingRunFields = false;
            }

            // 1) 恢复 lastTarget（由设置页保存）
            if (ZaStrUtil.isNotBlank(cfg.lastTarget)) {
                targetField.setText(cfg.lastTarget);
            } else {
                targetField.setText("");
            }

            applyConnectionUi(cfg);

            // 2) 恢复 mainScript/attachScript（项目内相对路径）
            VirtualFile mainScript = state.getMainScriptFile();
            if (mainScript != null && !mainScript.isDirectory()) {
                setRunScriptFile(mainScript);
            } else if (ZaStrUtil.isNotBlank(cfg.mainScript)) {
                runConsolePanel.warn(String.format("[ZAFrida] Main script not found in project: %s", cfg.mainScript));
            }

            VirtualFile attachScript = state.getAttachScriptFile();
            if (attachScript != null && !attachScript.isDirectory()) {
                setAttachScriptFile(attachScript);
            } else if (ZaStrUtil.isNotBlank(cfg.attachScript)) {
                runConsolePanel.warn(String.format("[ZAFrida] Attach script not found in project: %s", cfg.attachScript));
            } else {
                attachScriptFile = null;
                attachScriptField.setText("");
            }

            reloadDevicesAsyncWithConfig(cfg);
            lastAppliedProject = active;
            consumePendingProjectAction(active);
        });
    }

    /**
     * 更新项目平台图标。
     * @param active 当前激活项目
     */
    private void updateProjectTypeIcon(@Nullable ZaFridaFridaProject active) {
        if (active == null) {
            projectTypeIcon.setIcon(null);
            projectTypeIcon.setToolTipText("No active project");
            return;
        }
        projectTypeIcon.setIcon(ZaFridaIcons.forPlatform(active.getPlatform()));
        projectTypeIcon.setToolTipText(String.format("Platform: %s", active.getPlatform().name()));
    }

    /**
     * 持久化额外参数到项目配置。
     */
    private void persistExtraArgs() {
        if (updatingRunFields) return;
        ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
        if (active == null) return;
        String args = extraArgsField.getText();
        fridaProjectManager.updateProjectConfigAsync(active, c -> {
            if (args == null) {
                c.extraArgs = "";
            } else {
                c.extraArgs = args;
            }
        });
    }

    /**
     * 选择 Run 脚本文件。
     * @param initialSelection 初始选中（文件或目录）
     * @return 脚本文件或 null
     */
    private @Nullable VirtualFile chooseRunScriptFile(@Nullable VirtualFile initialSelection) {
        ZaFridaSettingsState st = ApplicationManager.getApplication()
                .getService(ZaFridaSettingsService.class)
                .getState();
        if (st.useIdeScriptChooser) {
            return ProjectFileUtil.chooseJavaScriptFileInProject(project, initialSelection);
        }
        return ProjectFileUtil.chooseJavaScriptFile(project, initialSelection);
    }

    /**
     * 选择 Attach 脚本文件。
     * @param initialSelection 初始选中（文件或目录）
     * @return 脚本文件或 null
     */
    private @Nullable VirtualFile chooseAttachScriptFile(@Nullable VirtualFile initialSelection) {
        return chooseRunScriptFile(initialSelection);
    }

    /**
     * 解析文件选择器的初始选中项。
     * @param cachedFile 已缓存的脚本文件
     * @param pathText 输入框中的路径文本
     * @param fallbackDir 兜底目录
     * @return 初始选中（文件或目录）
     */
    private @Nullable VirtualFile resolveInitialScriptSelection(@Nullable VirtualFile cachedFile,
                                                                @Nullable String pathText,
                                                                @Nullable VirtualFile fallbackDir) {
        if (cachedFile != null && cachedFile.isValid()) {
            return cachedFile;
        }
        VirtualFile fromPath = resolveVirtualFileFromText(pathText);
        if (fromPath != null) {
            return fromPath;
        }
        if (fallbackDir != null && fallbackDir.isValid()) {
            return fallbackDir;
        }
        return null;
    }

    /**
     * 从路径文本解析文件或其父目录。
     * @param pathText 路径文本
     * @return 文件或目录
     */
    private @Nullable VirtualFile resolveVirtualFileFromText(@Nullable String pathText) {
        if (ZaStrUtil.isBlank(pathText)) return null;
        String path = pathText.trim();
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
        if (file != null && file.isValid()) {
            return file;
        }
        String parentPath = new File(path).getParent();
        if (parentPath == null || parentPath.isEmpty()) return null;
        VirtualFile parent = LocalFileSystem.getInstance().findFileByPath(parentPath);
        return parent != null && parent.isValid() ? parent : null;
    }

    /**
     * 创建新的 Frida 项目。
     */
    private void createNewFridaProject() {
        CreateZaFridaProjectDialog dialog = new CreateZaFridaProjectDialog(project);
        if (!dialog.showAndGet()) return;

        String name = dialog.getProjectName();
        if (name.isEmpty()) {
            ZaFridaNotifier.warn(project, "ZAFrida", "Project name is empty");
            return;
        }

        ZaFridaPlatform platform = dialog.getPlatform();

        fridaProjectManager.createAndActivateAsync(name, platform, created -> {
            reloadFridaProjectsIntoUi();
            applyActiveFridaProjectToUi(created);
            runConsolePanel.info(String.format("[ZAFrida] Created project: %s (%s)", created.getName(), created.getRelativeDir()));
        }, t -> {
            runConsolePanel.error(String.format("[ZAFrida] Create project failed: %s", t.getMessage()));
            ZaFridaNotifier.error(project, "ZAFrida", String.format("Create project failed: %s", t.getMessage()));
        });
    }

    /**
     * 打开项目设置对话框。
     */
    private void openProjectSettings() {
        ZaFridaProjectSettingsDialog dialog = new ZaFridaProjectSettingsDialog(
                project,
                fridaProjectManager,
                fridaCli,
                () -> (FridaDevice) deviceCombo.getSelectedItem(),
                runConsolePanel::error
        );
        if (dialog.showAndGet()) {
            applyActiveFridaProjectToUi(fridaProjectManager.getActiveProject());
        }
    }

    /**
     * 打开全局设置对话框。
     */
    private void openGlobalSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "ZAFrida");
    }

    /**
     * 打开插件更新设置。
     */
    private void openPluginUpdates() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Plugins");
    }

    /**
     * 打开新建项目对话框。
     */
    public void openNewProjectDialog() {
        createNewFridaProject();
    }

    /**
     * 打开项目设置对话框（UI 入口）。
     */
    public void openProjectSettingsDialog() {
        openProjectSettings();
    }

    /**
     * 打开全局设置对话框（UI 入口）。
     */
    public void openGlobalSettingsDialog() {
        openGlobalSettings();
    }

    /**
     * 打开环境医生对话框。
     */
    public void openEnvironmentDoctorDialog() {
        Supplier<FridaDevice> supplier = new Supplier<FridaDevice>() {
            @Override
            public FridaDevice get() {
                return getSelectedDeviceForDiagnostics();
            }
        };
        EnvironmentDoctorDialog dialog = new EnvironmentDoctorDialog(project, supplier);
        dialog.show();
    }

    /**
     * 显示语言切换提示。
     */
    public void showLanguageToggleMessage() {
        Messages.showInfoMessage(
                project,
                "Switch UI language (中文/English) is coming soon.",
                "ZAFrida"
        );
    }

    /**
     * 触发 Run 行为。
     */
    public void triggerRun() {
        if (!runBtn.isEnabled()) return;
        runFrida();
    }

    /**
     * 设置并运行指定 Run 脚本。
     * @param file 脚本文件
     */
    public void runWithRunScript(@NotNull VirtualFile file) {
        if (!file.isValid() || file.isDirectory()) {
            ZaFridaNotifier.warn(project, "ZAFrida", "Invalid script file");
            return;
        }
        setRunScriptFile(file);
        triggerRun();
    }

    /**
     * 切换项目完成后执行 Run。
     * @param expectedProject 期望的激活项目
     * @param file 脚本文件
     */
    public void runWithRunScriptAfterProjectSwitch(@NotNull ZaFridaFridaProject expectedProject,
                                                   @NotNull VirtualFile file) {
        if (!file.isValid() || file.isDirectory()) {
            ZaFridaNotifier.warn(project, "ZAFrida", "Invalid script file");
            return;
        }
        enqueueProjectAction(expectedProject, () -> {
            setRunScriptFile(file);
            triggerRun();
        });
    }

    /**
     * 触发 Attach 行为。
     */
    public void triggerAttach() {
        if (!attachBtn.isEnabled()) return;
        attachFrida();
    }

    /**
     * 设置并执行 Attach 脚本。
     * @param file 脚本文件
     */
    public void attachWithScript(@NotNull VirtualFile file) {
        if (!file.isValid() || file.isDirectory()) {
            ZaFridaNotifier.warn(project, "ZAFrida", "Invalid attach script file");
            return;
        }
        setAttachScriptFile(file);
        triggerAttach();
    }

    /**
     * 切换项目完成后执行 Attach。
     * @param expectedProject 期望的激活项目
     * @param file 脚本文件
     */
    public void attachWithScriptAfterProjectSwitch(@NotNull ZaFridaFridaProject expectedProject,
                                                   @NotNull VirtualFile file) {
        if (!file.isValid() || file.isDirectory()) {
            ZaFridaNotifier.warn(project, "ZAFrida", "Invalid attach script file");
            return;
        }
        enqueueProjectAction(expectedProject, () -> {
            setAttachScriptFile(file);
            triggerAttach();
        });
    }

    /**
     * 记录等待项目切换完成后的动作。
     * @param expectedProject 期望激活的项目
     * @param action 待执行动作
     */
    private void enqueueProjectAction(@NotNull ZaFridaFridaProject expectedProject, @NotNull Runnable action) {
        if (expectedProject.equals(lastAppliedProject)) {
            action.run();
            return;
        }
        pendingProjectAction = new PendingProjectAction(expectedProject, action);
    }

    /**
     * 如果当前激活项目匹配，执行等待中的动作。
     * @param active 当前激活项目
     */
    private void consumePendingProjectAction(@Nullable ZaFridaFridaProject active) {
        PendingProjectAction pending = pendingProjectAction;
        if (pending == null) {
            return;
        }
        if (active == null) {
            return;
        }
        if (!active.equals(pending.expectedProject)) {
            return;
        }
        pendingProjectAction = null;
        pending.action.run();
    }

    /**
     * 触发停止。
     */
    public void triggerStop() {
        if (!stopBtn.isEnabled()) {
            return;
        }
        stopRunSession();
    }

    /**
     * 触发停止 Attach。
     */
    public void triggerStopAttach() {
        if (!stopAttachBtn.isEnabled()) {
            return;
        }
        stopAttachSession();
    }

    /**
     * 触发强制停止目标应用。
     */
    public void triggerForceStop() {
        forceStopApp();
    }

    /**
     * 触发打开目标应用。
     */
    public void triggerOpenApp() {
        openApp();
    }

    /**
     * 绑定外部 Run/Stop 按钮状态。
     * @param runButton 外部 Run 按钮
     * @param stopButton 外部 Stop 按钮
     */
    public void bindExternalRunStopButtons(@NotNull JButton runButton, @NotNull JButton stopButton) {
        this.externalRunBtn = runButton;
        this.externalStopBtn = stopButton;
        syncExternalRunStopButtons();
    }

    /**
     * 刷新当前设备列表（API 入口）。
     */
    public void refreshDevicesForApi() {
        reloadDevicesAsync();
    }

    /**
     * 刷新当前激活项目配置到 UI（API 入口）。
     */
    public void refreshActiveProjectUiForApi() {
        applyActiveFridaProjectToUi(fridaProjectManager.getActiveProject());
    }

    /**
     * 获取当前选中设备（API 入口）。
     * @return 设备或 null
     */
    public @Nullable FridaDevice getSelectedDeviceForApi() {
        return (FridaDevice) deviceCombo.getSelectedItem();
    }

    /**
     * 按设备 ID 选中设备（API 入口）。
     * @param deviceId 设备 ID
     * @return true 表示已选中
     */
    public boolean selectDeviceByIdForApi(@NotNull String deviceId) {
        String normalized = deviceId.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        int count = deviceCombo.getItemCount();
        for (int i = 0; i < count; i++) {
            FridaDevice item = deviceCombo.getItemAt(i);
            if (normalized.equals(item.getId())) {
                deviceCombo.setSelectedItem(item);
                return true;
            }
        }
        return false;
    }

    /**
     * 按 host:port 选中设备（API 入口）。
     * @param host host:port
     * @return true 表示已选中
     */
    public boolean selectDeviceByHostForApi(@NotNull String host) {
        String normalized = host.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        int count = deviceCombo.getItemCount();
        for (int i = 0; i < count; i++) {
            FridaDevice item = deviceCombo.getItemAt(i);
            String itemHost = item.getHost();
            if (itemHost != null && normalized.equals(itemHost)) {
                deviceCombo.setSelectedItem(item);
                return true;
            }
        }
        return false;
    }

    /**
     * 获取目标文本（API 入口）。
     * @return 目标文本
     */
    public @NotNull String getTargetTextForApi() {
        String text = targetField.getText();
        if (text == null) {
            return "";
        }
        return text.trim();
    }

    /**
     * 设置目标文本（API 入口）。
     * @param target 目标文本
     */
    public void setTargetTextForApi(@Nullable String target) {
        String normalized;
        if (target == null) {
            normalized = "";
        } else {
            normalized = target.trim();
        }
        targetField.setText(normalized);
        ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
        if (active == null) {
            return;
        }
        fridaProjectManager.updateProjectConfigAsync(active, cfg -> {
            if (normalized.isEmpty()) {
                cfg.lastTarget = null;
            } else {
                cfg.lastTarget = normalized;
            }
        });
    }

    /**
     * 获取 Extra Args（API 入口）。
     * @return 参数字符串
     */
    public @NotNull String getExtraArgsForApi() {
        String text = extraArgsField.getText();
        if (text == null) {
            return "";
        }
        return text;
    }

    /**
     * 设置 Extra Args（API 入口）。
     * @param args 参数字符串
     */
    public void setExtraArgsForApi(@Nullable String args) {
        String normalized;
        if (args == null) {
            normalized = "";
        } else {
            normalized = args;
        }
        extraArgsField.setText(normalized);
    }

    /**
     * 获取 Run 脚本路径（API 入口）。
     * @return Run 脚本路径
     */
    public @NotNull String getRunScriptPathForApi() {
        String text = runScriptField.getText();
        if (text == null) {
            return "";
        }
        return text.trim();
    }

    /**
     * 获取 Attach 脚本路径（API 入口）。
     * @return Attach 脚本路径
     */
    public @NotNull String getAttachScriptPathForApi() {
        String text = attachScriptField.getText();
        if (text == null) {
            return "";
        }
        return text.trim();
    }

    /**
     * 设置 Run 脚本路径（API 入口）。
     * @param path 脚本路径
     * @return true 表示设置成功
     */
    public boolean setRunScriptPathForApi(@NotNull String path) {
        String normalized = path.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized);
        if (file == null) {
            return false;
        }
        if (!file.isValid() || file.isDirectory()) {
            return false;
        }
        setRunScriptFile(file);
        ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
        if (active != null) {
            fridaProjectManager.updateMainScriptPathAsync(active, file);
        }
        return true;
    }

    /**
     * 设置 Attach 脚本路径（API 入口）。
     * @param path 脚本路径
     * @return true 表示设置成功
     */
    public boolean setAttachScriptPathForApi(@NotNull String path) {
        String normalized = path.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized);
        if (file == null) {
            return false;
        }
        if (!file.isValid() || file.isDirectory()) {
            return false;
        }
        setAttachScriptFile(file);
        ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
        if (active != null) {
            fridaProjectManager.updateAttachScriptPathAsync(active, file);
        }
        return true;
    }

    /**
     * 获取 Run 控制台（API 入口）。
     * @return Run 控制台面板
     */
    public @NotNull ZaFridaConsolePanel getRunConsolePanelForApi() {
        return runConsolePanel;
    }

    /**
     * 获取 Attach 控制台（API 入口）。
     * @return Attach 控制台面板
     */
    public @NotNull ZaFridaConsolePanel getAttachConsolePanelForApi() {
        return attachConsolePanel;
    }






    /**
     * 构建设备选择行。
     * @return 行面板
     */
    private JPanel buildDeviceRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        deviceCombo.setPrototypeDisplayValue(new FridaDevice(USB_DEVICE_TYPE, USB_DEVICE_TYPE, "Android"));
        deviceCombo.setMinimumAndPreferredWidth(258);
        p.add(deviceCombo);
        p.add(refreshDevicesBtn);
        p.add(addRemoteBtn);
        return p;
    }

    /**
     * 构建 Run 脚本选择行。
     * @return 行面板
     */
    private JPanel buildRunScriptRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        runScriptField.setColumns(22);
        p.add(runScriptField);
        p.add(locateRunScriptBtn);
        p.add(chooseRunScriptBtn);
        return p;
    }

    /**
     * 构建 Attach 脚本选择行。
     * @return 行面板
     */
    private JPanel buildAttachScriptRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        attachScriptField.setColumns(22);
        p.add(attachScriptField);
        p.add(locateAttachScriptBtn);
        p.add(chooseAttachScriptBtn);
        return p;
    }

    /**
     * 构建目标输入行。
     * @return 行面板
     */
    private JPanel buildTargetRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.add(targetField);
        p.add(forceStopBtn);
        p.add(openAppBtn);
        return p;
    }

    /**
     * 构建额外参数行。
     * @return 行面板
     */
    private JPanel buildExtraRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.add(extraArgsField);
        p.add(new JLabel("(Args)"));
        return p;
    }

    /**
     * 构建运行控制按钮行。
     * @return 行面板
     */
    private JPanel buildButtonsRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.add(runBtn);
        p.add(stopBtn);
        p.add(attachBtn);
        p.add(stopAttachBtn);
        return p;
    }

    /**
     * 向表单追加一行布局。
     * @param form 表单面板
     * @param row 当前行索引
     * @param label 左侧标签
     * @param right 右侧组件
     * @return 下一行索引
     */
    private int addRow(JPanel form, int row, JLabel label, JPanel right) {
        GridBagConstraints c1 = new GridBagConstraints();
        c1.gridx = 0;
        c1.gridy = row;
        c1.insets = new Insets(6, 8, 6, 8);
        c1.anchor = GridBagConstraints.WEST;
        form.add(label, c1);

        GridBagConstraints c2 = new GridBagConstraints();
        c2.gridx = 1;
        c2.gridy = row;
        c2.weightx = 1;
        c2.fill = GridBagConstraints.HORIZONTAL;
        c2.insets = new Insets(6, 8, 6, 8);
        form.add(right, c2);
        return row + 1;
    }

    /**
     * 设置 Run 脚本文件并更新 UI。
     * @param file 脚本文件
     */
    private void setRunScriptFile(@NotNull VirtualFile file) {
        this.runScriptFile = file;
        this.runScriptField.setText(file.getPath());
        this.templatePanel.setCurrentScriptFile(file);
    }

    /**
     * 设置 Attach 脚本文件并更新 UI。
     * @param file 脚本文件
     */
    private void setAttachScriptFile(@NotNull VirtualFile file) {
        this.attachScriptFile = file;
        this.attachScriptField.setText(file.getPath());
    }

    /**
     * 在 Project 视图中定位 Run 脚本。
     */
    private void locateRunScriptInProjectView() {
        String path = runScriptField.getText();
        VirtualFile file = resolveRunScriptFileForLocate();
        if (file == null || !file.isValid() || file.isDirectory()) {
            if (ZaStrUtil.isBlank(path)) {
                ZaFridaNotifier.warn(project, "ZAFrida", "No script file selected");
            } else {
                ZaFridaNotifier.warn(project, "ZAFrida", String.format("Script file not found: %s", path.trim()));
            }
            return;
        }
        ProjectFileUtil.openAndSelectInProject(project, file);
    }

    /**
     * 在 Project 视图中定位 Attach 脚本。
     */
    private void locateAttachScriptInProjectView() {
        String path = attachScriptField.getText();
        VirtualFile file = resolveAttachScriptFileForLocate();
        if (file == null || !file.isValid() || file.isDirectory()) {
            if (ZaStrUtil.isBlank(path)) {
                ZaFridaNotifier.warn(project, "ZAFrida", "No attach script file selected");
            } else {
                ZaFridaNotifier.warn(project, "ZAFrida", String.format("Attach script file not found: %s", path.trim()));
            }
            return;
        }
        ProjectFileUtil.openAndSelectInProject(project, file);
    }

    /**
     * 解析可定位的 Run 脚本文件。
     * @return 脚本文件或 null
     */
    private @Nullable VirtualFile resolveRunScriptFileForLocate() {
        if (runScriptFile != null && runScriptFile.isValid()) {
            return runScriptFile;
        }
        VirtualFile templateFile = templatePanel.getCurrentScriptFile();
        if (templateFile != null && templateFile.isValid()) {
            return templateFile;
        }
        String path = runScriptField.getText();
        if (ZaStrUtil.isBlank(path)) return null;
        return LocalFileSystem.getInstance().findFileByPath(path.trim());
    }

    /**
     * 解析可定位的 Attach 脚本文件。
     * @return 脚本文件或 null
     */
    private @Nullable VirtualFile resolveAttachScriptFileForLocate() {
        if (attachScriptFile != null && attachScriptFile.isValid()) {
            return attachScriptFile;
        }
        String path = attachScriptField.getText();
        if (ZaStrUtil.isBlank(path)) return null;
        return LocalFileSystem.getInstance().findFileByPath(path.trim());
    }


    /**
     * 输出工具链信息（仅一次）。
     */
    private void printToolchainInfoOnce() {
        if (printedToolchainInfo) return;
        printedToolchainInfo = true;

        PythonEnvInfo env = ProjectPythonEnvResolver.resolve(project);
        if (env == null) {
            runConsolePanel.warn("[ZAFrida] Project Python interpreter env not detected. Using IDE/system PATH for frida-tools.");
            return;
        }

        runConsolePanel.info(String.format("[ZAFrida] Project Python: %s", env.getPythonHome()));
        if (!env.getPathEntries().isEmpty()) {
            runConsolePanel.info(String.format("[ZAFrida] Project PATH prepend: %s", String.join(File.pathSeparator, env.getPathEntries())));
        }

        ZaFridaSettingsState st = ApplicationManager.getApplication().getService(ZaFridaSettingsService.class).getState();
        String ls = ProjectPythonEnvResolver.findTool(env, st.fridaLsDevicesExecutable);
        String ps = ProjectPythonEnvResolver.findTool(env, st.fridaPsExecutable);
        String frida = ProjectPythonEnvResolver.findTool(env, st.fridaExecutable);

        if (ls != null) {
            runConsolePanel.info(String.format("[ZAFrida] Resolved frida-ls-devices: %s", ls));
        } else {
            runConsolePanel.warn("[ZAFrida] frida-ls-devices not found in project interpreter; will fallback to system PATH if available.");
        }
        if (ps != null) {
            runConsolePanel.info(String.format("[ZAFrida] Resolved frida-ps: %s", ps));
        }
        if (frida != null) {
            runConsolePanel.info(String.format("[ZAFrida] Resolved frida: %s", frida));
        }
    }

    /**
     * 异步刷新设备列表。
     */
    private void reloadDevicesAsync() {
        ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
        if (active == null) {
            reloadDevicesAsyncWithConfig(null);
            return;
        }
        fridaProjectManager.loadProjectConfigAsync(active, this::reloadDevicesAsyncWithConfig);
    }

    private void reloadDevicesAsyncWithConfig(@Nullable ZaFridaProjectConfig cfg) {
        disableControls(true);
        printToolchainInfoOnce();
        runConsolePanel.info("[ZAFrida] Loading devices...");

        FridaConnectionMode connectionMode;
        if (cfg != null && cfg.connectionMode != null) {
            connectionMode = cfg.connectionMode;
        } else {
            connectionMode = FridaConnectionMode.USB;
        }
        final FridaConnectionMode finalConnectionMode = connectionMode;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<FridaDevice> devices = new ArrayList<>(fridaCli.listDevices(project));
                // add remotes from settings
                // 从设置中追加远程设备
                ZaFridaSettingsService settingsService =
                        ApplicationManager.getApplication().getService(ZaFridaSettingsService.class);
                List<String> remotes = settingsService.getRemoteHosts();
                for (String host : remotes) {
                    if (!containsHost(devices, host)) {
                        devices.add(new FridaDevice(String.format("remote:%s", host), "remote", "Remote", FridaDeviceMode.HOST, host));
                    }
                }

                if (cfg != null && (finalConnectionMode == FridaConnectionMode.REMOTE || finalConnectionMode == FridaConnectionMode.GADGET)) {
                    String host = resolveHostPort(cfg);
                    if (!containsHost(devices, host)) {
                        String type;
                        String name;
                        if (finalConnectionMode == FridaConnectionMode.GADGET) {
                            type = "gadget";
                            name = "Gadget";
                        } else {
                            type = "remote";
                            name = "Remote";
                        }
                        devices.add(new FridaDevice(String.format("%s:%s", type, host), type, name, FridaDeviceMode.HOST, host));
                    }
                }
                List<FridaDevice> sortedDevices = sortUsbDevicesFirst(devices);

                ApplicationManager.getApplication().invokeLater(() -> {
                    updatingDeviceCombo = true;
                    try {
                        deviceCombo.removeAllItems();
                        for (FridaDevice d : sortedDevices) {
                            deviceCombo.addItem(d);
                        }
                        selectSavedDevice(sortedDevices, cfg);
                    } finally {
                        updatingDeviceCombo = false;
                    }
                    runConsolePanel.info(String.format("[ZAFrida] Devices loaded: %s", sortedDevices.size()));
                    applyUsbDeviceHints(sortedDevices, cfg, finalConnectionMode);
                    disableControls(false);
                });
            } catch (Throwable t) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    runConsolePanel.error(String.format("[ZAFrida] Load devices failed: %s", t.getMessage()));
                    disableControls(false);
                });
            }
        });
    }

    /**
     * 根据连接模式调整 UI 状态。
     * @param cfg 项目配置
     */
    private void applyConnectionUi(@NotNull ZaFridaProjectConfig cfg) {
        FridaConnectionMode mode = cfg.connectionMode != null ? cfg.connectionMode : FridaConnectionMode.USB;
        boolean gadgetMode = mode == FridaConnectionMode.GADGET;
        targetField.setEnabled(!gadgetMode);
        if (gadgetMode) {
            targetField.setToolTipText("Gadget mode uses -F; target is ignored.");
        } else {
            targetField.setToolTipText("Spawn/Attach uses package name");
        }
    }

    /**
     * 从设备列表中选中上次保存的设备。
     * @param devices 设备列表
     * @param cfg 项目配置
     */
    private void selectSavedDevice(@NotNull List<FridaDevice> devices, @Nullable ZaFridaProjectConfig cfg) {
        FridaDevice match = null;
        if (cfg != null) {
            if (cfg.connectionMode == FridaConnectionMode.REMOTE || cfg.connectionMode == FridaConnectionMode.GADGET) {
                String host = resolveHostPort(cfg);
                match = findDeviceByHost(devices, host);
            }
            if (match == null) {
                if (ZaStrUtil.isNotBlank(cfg.lastDeviceHost)) {
                    match = findDeviceByHost(devices, cfg.lastDeviceHost);
                } else if (ZaStrUtil.isNotBlank(cfg.lastDeviceId)) {
                    match = findDeviceById(devices, cfg.lastDeviceId);
                }
            }
        }
        if (match != null) {
            deviceCombo.setSelectedItem(match);
            return;
        }
        if (!devices.isEmpty()) {
            deviceCombo.setSelectedIndex(0);
        }
    }

    /**
     * 通过 host 查找设备。
     * @param devices 设备列表
     * @param host 主机地址
     * @return 设备或 null
     */
    private static @Nullable FridaDevice findDeviceByHost(@NotNull List<FridaDevice> devices, @NotNull String host) {
        for (FridaDevice d : devices) {
            if (host.equals(d.getHost())) {
                return d;
            }
        }
        return null;
    }

    /**
     * 通过 ID 查找设备。
     * @param devices 设备列表
     * @param id 设备 ID
     * @return 设备或 null
     */
    private static @Nullable FridaDevice findDeviceById(@NotNull List<FridaDevice> devices, @NotNull String id) {
        for (FridaDevice d : devices) {
            if (id.equals(d.getId())) {
                return d;
            }
        }
        return null;
    }

    /**
     * 判断设备列表是否包含指定 host。
     * @param devices 设备列表
     * @param host 主机地址
     * @return true 表示包含
     */
    private static boolean containsHost(@NotNull List<FridaDevice> devices, @NotNull String host) {
        return findDeviceByHost(devices, host) != null;
    }

    /**
     * USB 设备优先排序（保持相对顺序）。
     * @param devices 设备列表
     * @return 排序后的设备列表
     */
    private static @NotNull List<FridaDevice> sortUsbDevicesFirst(@NotNull List<FridaDevice> devices) {
        if (devices.isEmpty()) {
            return devices;
        }
        List<FridaDevice> usbDevices = new ArrayList<>();
        List<FridaDevice> others = new ArrayList<>();
        for (FridaDevice device : devices) {
            if (isUsbDevice(device)) {
                usbDevices.add(device);
            } else {
                others.add(device);
            }
        }
        if (usbDevices.isEmpty() || others.isEmpty()) {
            return devices;
        }
        List<FridaDevice> sorted = new ArrayList<>(devices.size());
        sorted.addAll(usbDevices);
        sorted.addAll(others);
        return sorted;
    }

    /**
     * 提示 USB 相关信息（不打断流程）。
     * @param devices 设备列表
     * @param cfg 项目配置
     * @param connectionMode 连接模式
     */
    private void applyUsbDeviceHints(@NotNull List<FridaDevice> devices,
                                     @Nullable ZaFridaProjectConfig cfg,
                                     @NotNull FridaConnectionMode connectionMode) {
        boolean hasUsb = hasUsbDevice(devices);
        if (hasUsb) {
            warnedNoUsbDevices = false;
        } else {
            if (!warnedNoUsbDevices) {
                runConsolePanel.warn(String.format(
                        "[ZAFrida] No USB devices found. If you're using USB mode, run \"%s\" in a terminal to initialize the ADB connection.",
                        ADB_SHELL_COMMAND));
                warnedNoUsbDevices = true;
            }
        }

        if (cfg == null) {
            lastMissingUsbDeviceId = null;
            return;
        }
        if (connectionMode != FridaConnectionMode.USB) {
            lastMissingUsbDeviceId = null;
            return;
        }
        if (ZaStrUtil.isBlank(cfg.lastDeviceId)) {
            lastMissingUsbDeviceId = null;
            return;
        }
        if (findDeviceById(devices, cfg.lastDeviceId) != null) {
            lastMissingUsbDeviceId = null;
            return;
        }
        if (!cfg.lastDeviceId.equals(lastMissingUsbDeviceId)) {
            ZaFridaNotifier.warn(project, "ZAFrida",
                    String.format("Saved USB device not found: %s. Please refresh or switch device.", cfg.lastDeviceId));
            lastMissingUsbDeviceId = cfg.lastDeviceId;
        }
    }

    /**
     * 是否存在 USB 设备。
     * @param devices 设备列表
     * @return true 表示存在
     */
    private static boolean hasUsbDevice(@NotNull List<FridaDevice> devices) {
        for (FridaDevice device : devices) {
            if (isUsbDevice(device)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否为 USB 设备。
     * @param device 设备
     * @return true 表示 USB
     */
    private static boolean isUsbDevice(@NotNull FridaDevice device) {
        return USB_DEVICE_TYPE.equalsIgnoreCase(device.getType());
    }

    /**
     * 解析 host:port 字符串。
     * @param cfg 项目配置
     * @return host:port
     */
    private @NotNull String resolveHostPort(@Nullable ZaFridaProjectConfig cfg) {
        return String.format("%s:%s", resolveRemoteHost(cfg), resolveRemotePort(cfg));
    }

    /**
     * 解析远程主机地址。
     * @param cfg 项目配置
     * @return 主机地址
     */
    private @NotNull String resolveRemoteHost(@Nullable ZaFridaProjectConfig cfg) {
        if (cfg != null && ZaStrUtil.isNotBlank(cfg.remoteHost)) {
            return cfg.remoteHost.trim();
        }
        ZaFridaSettingsState st = ApplicationManager.getApplication()
                .getService(ZaFridaSettingsService.class)
                .getState();
        return ZaFridaNetUtil.defaultHost(st.defaultRemoteHost);
    }

    /**
     * 解析远程端口。
     * @param cfg 项目配置
     * @return 端口号
     */
    private int resolveRemotePort(@Nullable ZaFridaProjectConfig cfg) {
        if (cfg != null && cfg.remotePort > 0) {
            return cfg.remotePort;
        }
        ZaFridaSettingsState st = ApplicationManager.getApplication()
                .getService(ZaFridaSettingsService.class)
                .getState();
        return ZaFridaNetUtil.defaultPort(st.defaultRemotePort);
    }


    /**
     * 执行 Run 流程。
     */
    private void runFrida() {
        ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
        String targetText = targetField.getText();
        String target;
        if (targetText == null) {
            target = "";
        } else {
            target = targetText.trim();
        }
        String extraArgsText = extraArgsField.getText();
        String extraArgs;
        if (extraArgsText == null) {
            extraArgs = "";
        } else {
            extraArgs = extraArgsText;
        }
        VirtualFile preferredScript = runScriptFile;
        if (preferredScript == null) {
            preferredScript = templatePanel.getCurrentScriptFile();
        }
        final VirtualFile finalPreferredScript = preferredScript;

        if (active == null) {
            runFridaWithConfig(null, null, target, extraArgs, finalPreferredScript);
            return;
        }
        fridaProjectManager.loadProjectConfigAsync(active, cfg ->
                runFridaWithConfig(active, cfg, target, extraArgs, finalPreferredScript));
    }

    /**
     * 执行 Attach 流程。
     */
    private void attachFrida() {
        ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
        String targetText = targetField.getText();
        String target;
        if (targetText == null) {
            target = "";
        } else {
            target = targetText.trim();
        }
        String extraArgsText = extraArgsField.getText();
        String extraArgs;
        if (extraArgsText == null) {
            extraArgs = "";
        } else {
            extraArgs = extraArgsText;
        }
        VirtualFile preferredScript = attachScriptFile;
        final VirtualFile finalPreferredScript = preferredScript;

        if (active == null) {
            attachFridaWithConfig(null, null, target, extraArgs, finalPreferredScript);
            return;
        }
        fridaProjectManager.loadProjectConfigAsync(active, cfg ->
                attachFridaWithConfig(active, cfg, target, extraArgs, finalPreferredScript));
    }

    private void runFridaWithConfig(@Nullable ZaFridaFridaProject active,
                                    @Nullable ZaFridaProjectConfig projectConfig,
                                    @NotNull String target,
                                    @NotNull String extraArgs,
                                    @Nullable VirtualFile preferredScript) {
        FridaConnectionMode connectionMode;
        if (projectConfig != null && projectConfig.connectionMode != null) {
            connectionMode = projectConfig.connectionMode;
        } else {
            connectionMode = FridaConnectionMode.USB;
        }
        final FridaConnectionMode finalConnectionMode = connectionMode;
        final boolean gadgetMode = connectionMode == FridaConnectionMode.GADGET;

        FridaDevice dev = resolveDevice(projectConfig, connectionMode, gadgetMode);
        if (dev == null) {
            return;
        }

        if (!gadgetMode && target.isEmpty()) {
            ZaFridaNotifier.warn(project, "ZAFrida", "Target is empty");
            return;
        }

        resolveRunScriptAsync(active, target, gadgetMode, preferredScript, script -> {
            if (script == null) {
                ZaFridaNotifier.warn(project, "ZAFrida", "Choose a run script file first");
                return;
            }

            if (active != null && !gadgetMode) {
                fridaProjectManager.updateProjectConfigAsync(active, c -> c.lastTarget = target);
            }
            if (active != null) {
                fridaProjectManager.updateMainScriptPathAsync(active, script);
            }

            FridaRunMode mode = gadgetMode ? new FrontmostRunMode() : new SpawnRunMode(target);

            FridaRunConfig cfg = new FridaRunConfig(
                    dev,
                    mode,
                    script.getPath(),
                    extraArgs
            );

            String fridaProjectDir = null;
            if (activeProjectDir != null && activeProjectDir.isValid()) {
                fridaProjectDir = activeProjectDir.getPath();
            }

            String targetPackage = null;
            if (!gadgetMode && !target.isEmpty()) {
                targetPackage = target;
            }

            ZaFridaConsolePanel console = runConsolePanel;
            consoleTabsPanel.showRunConsole();
            String finalFridaProjectDir = fridaProjectDir;
            String finalTargetPackage = targetPackage;
            Runnable startSession = () ->
                    startFridaSession(ZaFridaSessionType.RUN, cfg, console, finalFridaProjectDir, finalTargetPackage);
            boolean needsAdbForward = (finalConnectionMode == FridaConnectionMode.REMOTE || gadgetMode)
                    && ZaFridaNetUtil.isLoopbackHost(resolveRemoteHost(projectConfig));
            if (needsAdbForward) {
                adbService.forwardTcp(resolveRemotePort(projectConfig), console::info, console::warn, startSession);
                return;
            }

            startSession.run();
        });
    }

    private void attachFridaWithConfig(@Nullable ZaFridaFridaProject active,
                                       @Nullable ZaFridaProjectConfig projectConfig,
                                       @NotNull String target,
                                       @NotNull String extraArgs,
                                       @Nullable VirtualFile preferredScript) {
        FridaConnectionMode connectionMode;
        if (projectConfig != null && projectConfig.connectionMode != null) {
            connectionMode = projectConfig.connectionMode;
        } else {
            connectionMode = FridaConnectionMode.USB;
        }
        final FridaConnectionMode finalConnectionMode = connectionMode;
        final boolean gadgetMode = connectionMode == FridaConnectionMode.GADGET;

        FridaDevice dev = resolveDevice(projectConfig, connectionMode, gadgetMode);
        if (dev == null) {
            return;
        }

        if (!gadgetMode && target.isEmpty()) {
            ZaFridaNotifier.warn(project, "ZAFrida", "Target is empty");
            return;
        }

        resolveAttachScriptAsync(active, preferredScript, script -> {
            if (script == null) {
                ZaFridaNotifier.warn(project, "ZAFrida", "Choose an attach script file first");
                return;
            }

            if (active != null) {
                fridaProjectManager.updateAttachScriptPathAsync(active, script);
            }
            if (active != null && !gadgetMode) {
                fridaProjectManager.updateProjectConfigAsync(active, c -> c.lastTarget = target);
            }

            FridaRunMode mode = gadgetMode ? new FrontmostRunMode() : new AttachNameRunMode(target);

            FridaRunConfig cfg = new FridaRunConfig(
                    dev,
                    mode,
                    script.getPath(),
                    extraArgs
            );

            String fridaProjectDir = null;
            if (activeProjectDir != null && activeProjectDir.isValid()) {
                fridaProjectDir = activeProjectDir.getPath();
            }

            ZaFridaConsolePanel console = attachConsolePanel;
            consoleTabsPanel.showAttachConsole();
            String targetPackage = null;
            if (!gadgetMode && !target.isEmpty()) {
                targetPackage = target;
            }
            String finalFridaProjectDir = fridaProjectDir;
            String finalTargetPackage = targetPackage;
            Runnable startSession = () ->
                    startFridaSession(ZaFridaSessionType.ATTACH, cfg, console, finalFridaProjectDir, finalTargetPackage);
            boolean needsAdbForward = (finalConnectionMode == FridaConnectionMode.REMOTE || gadgetMode)
                    && ZaFridaNetUtil.isLoopbackHost(resolveRemoteHost(projectConfig));
            if (needsAdbForward) {
                adbService.forwardTcp(resolveRemotePort(projectConfig), console::info, console::warn, startSession);
                return;
            }

            startSession.run();
        });
    }

    private void resolveRunScriptAsync(@Nullable ZaFridaFridaProject active,
                                       @NotNull String target,
                                       boolean gadgetMode,
                                       @Nullable VirtualFile preferredScript,
                                       @NotNull Consumer<VirtualFile> uiConsumer) {
        if (preferredScript != null && preferredScript.isValid() && !preferredScript.isDirectory()) {
            uiConsumer.accept(preferredScript);
            return;
        }
        if (active == null) {
            uiConsumer.accept(null);
            return;
        }
        fridaProjectManager.resolveRunScriptFileAsync(active, target, gadgetMode, script -> {
            if (script != null && !script.isDirectory()) {
                setRunScriptFile(script);
            }
            uiConsumer.accept(script);
        });
    }

    private void resolveAttachScriptAsync(@Nullable ZaFridaFridaProject active,
                                          @Nullable VirtualFile preferredScript,
                                          @NotNull Consumer<VirtualFile> uiConsumer) {
        if (preferredScript != null && preferredScript.isValid() && !preferredScript.isDirectory()) {
            uiConsumer.accept(preferredScript);
            return;
        }
        if (active == null) {
            uiConsumer.accept(null);
            return;
        }
        fridaProjectManager.resolveAttachScriptFileAsync(active, script -> {
            if (script != null && !script.isDirectory()) {
                setAttachScriptFile(script);
            }
            uiConsumer.accept(script);
        });
    }

    /**
     * 根据连接模式解析设备。
     * @param projectConfig 项目配置
     * @param connectionMode 连接模式
     * @param gadgetMode 是否为 Gadget 模式
     * @return 设备或 null
     */
    private @Nullable FridaDevice resolveDevice(@Nullable ZaFridaProjectConfig projectConfig,
                                                @NotNull FridaConnectionMode connectionMode,
                                                boolean gadgetMode) {
        if (connectionMode == FridaConnectionMode.REMOTE || gadgetMode) {
            String hostValue = resolveRemoteHost(projectConfig);
            int portValue = resolveRemotePort(projectConfig);
            String host = String.format("%s:%s", hostValue, portValue);
            String type = gadgetMode ? "gadget" : "remote";
            String name = gadgetMode ? "Gadget" : "Remote";
            return new FridaDevice(String.format("%s:%s", type, host), type, name, FridaDeviceMode.HOST, host);
        }
        FridaDevice dev = (FridaDevice) deviceCombo.getSelectedItem();
        if (dev == null) {
            ZaFridaNotifier.warn(project, "ZAFrida", "No device selected");
            return null;
        }
        return dev;
    }

    /**
     * 获取当前选中设备（用于诊断）。
     * @return 设备或 null
     */
    private @Nullable FridaDevice getSelectedDeviceForDiagnostics() {
        return (FridaDevice) deviceCombo.getSelectedItem();
    }

    /**
     * 启动 Frida 会话并绑定日志输出。
     * @param type 会话类型
     * @param cfg 运行配置
     * @param console 控制台面板
     * @param fridaProjectDir Frida 项目目录
     * @param targetPackage 目标包名
     */
    private void startFridaSession(@NotNull ZaFridaSessionType type,
                                   @NotNull FridaRunConfig cfg,
                                   @NotNull ZaFridaConsolePanel console,
                                   @Nullable String fridaProjectDir,
                                   @Nullable String targetPackage) {
        try {
            RunningSession session = sessionService.start(
                    type,
                    cfg,
                    console.getConsoleView(),
                    console::info,
                    console::error,
                    fridaProjectDir,
                    targetPackage
            );

            session.getProcessHandler().addProcessListener(sessionService.createUiStateListener(this::updateRunningState));

            updateRunningState();
            console.setLogFilePath(session.getLogFilePath());
            console.info(String.format("[ZAFrida] Log file: %s", session.getLogFilePath()));
        } catch (Throwable t) {
            console.error(String.format("[ZAFrida] Start failed: %s", t.getMessage()));
            ZaFridaNotifier.error(project, "ZAFrida", String.format("Start failed: %s", t.getMessage()));
        }
    }

    /**
     * 停止当前会话。
     */
    private void stopRunSession() {
        stopSession(ZaFridaSessionType.RUN);
    }

    private void stopAttachSession() {
        stopSession(ZaFridaSessionType.ATTACH);
    }

    private void stopSession(@NotNull ZaFridaSessionType type) {
        sessionService.stop(type);
        updateRunningState();
        resolveConsoleForSessionType(type).info("[ZAFrida] Stopped");
    }

    /**
     * 强制停止目标应用（通过 adb）。
     */
    private void forceStopApp() {
        ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
        String targetRaw = targetField.getText();
        String targetText;
        if (targetRaw == null) {
            targetText = "";
        } else {
            targetText = targetRaw.trim();
        }
        if (active == null) {
            forceStopWithConfig(null, targetText);
            return;
        }
        fridaProjectManager.loadProjectConfigAsync(active, cfg -> forceStopWithConfig(cfg, targetText));
    }

    /**
     * 启动目标应用（通过 adb）。
     */
    private void openApp() {
        ZaFridaFridaProject active = fridaProjectManager.getActiveProject();
        String targetRaw = targetField.getText();
        String targetText;
        if (targetRaw == null) {
            targetText = "";
        } else {
            targetText = targetRaw.trim();
        }
        if (active == null) {
            openAppWithConfig(null, targetText);
            return;
        }
        fridaProjectManager.loadProjectConfigAsync(active, cfg -> openAppWithConfig(cfg, targetText));
    }

    private void forceStopWithConfig(@Nullable ZaFridaProjectConfig projectConfig, @NotNull String targetText) {
        String packageName = resolveForceStopPackage(projectConfig, targetText);
        if (ZaStrUtil.isBlank(packageName)) {
            ZaFridaNotifier.warn(project, "ZAFrida", "Force stop requires a package name");
            runConsolePanel.warn("[ZAFrida] Force stop requires a package name.");
            return;
        }

        FridaDevice selected = (FridaDevice) deviceCombo.getSelectedItem();
        String deviceId = null;
        if (selected != null && selected.getMode() == FridaDeviceMode.DEVICE_ID) {
            String id = selected.getId();
            if (ZaStrUtil.isNotBlank(id) && !"usb".equalsIgnoreCase(id)) {
                deviceId = id;
            }
        }
        adbService.forceStop(packageName, deviceId, runConsolePanel::info, runConsolePanel::error);
    }

    private void openAppWithConfig(@Nullable ZaFridaProjectConfig projectConfig, @NotNull String targetText) {
        String packageName = resolveForceStopPackage(projectConfig, targetText);
        if (ZaStrUtil.isBlank(packageName)) {
            ZaFridaNotifier.warn(project, "ZAFrida", "Open app requires a package name");
            runConsolePanel.warn("[ZAFrida] Open app requires a package name.");
            return;
        }

        FridaDevice selected = (FridaDevice) deviceCombo.getSelectedItem();
        String deviceId = null;
        if (selected != null && selected.getMode() == FridaDeviceMode.DEVICE_ID) {
            String id = selected.getId();
            if (ZaStrUtil.isNotBlank(id) && !"usb".equalsIgnoreCase(id)) {
                deviceId = id;
            }
        }
        adbService.openApp(packageName, deviceId, runConsolePanel::info, runConsolePanel::error);
    }

    /**
     * 解析用于强制停止/打开应用的包名。
     * @param cfg 项目配置
     * @return 包名或 null
     */
    private @Nullable String resolveForceStopPackage(@Nullable ZaFridaProjectConfig cfg, @NotNull String targetText) {
        boolean gadgetMode = cfg != null && cfg.connectionMode == FridaConnectionMode.GADGET;
        String target = gadgetMode ? "" : targetText;
        if (!target.isEmpty()) {
            if (!ZaFridaTextUtil.isNumeric(target)) {
                return target;
            }
        }
        if (cfg != null && ZaStrUtil.isNotBlank(cfg.lastTarget)) {
            return cfg.lastTarget.trim();
        }
        return null;
    }

    /**
     * 更新运行按钮可用性。
     */
    private void updateRunningState() {
        boolean runRunning = sessionService.isRunning(ZaFridaSessionType.RUN);
        boolean attachRunning = sessionService.isRunning(ZaFridaSessionType.ATTACH);
        runBtn.setEnabled(!runRunning);
        attachBtn.setEnabled(!attachRunning);
        stopBtn.setEnabled(runRunning);
        stopAttachBtn.setEnabled(attachRunning);
        syncExternalRunStopButtons();
    }

    /**
     * 根据会话类型选择控制台。
     * @param type 会话类型
     * @return 控制台面板
     */
    private @NotNull ZaFridaConsolePanel resolveConsoleForSessionType(@NotNull ZaFridaSessionType type) {
        return type == ZaFridaSessionType.ATTACH ? attachConsolePanel : runConsolePanel;
    }

    /**
     * 同步外部 Run/Stop 按钮状态。
     */
    private void syncExternalRunStopButtons() {
        if (externalRunBtn != null) {
            externalRunBtn.setEnabled(runBtn.isEnabled());
        }
        if (externalStopBtn != null) {
            externalStopBtn.setEnabled(stopBtn.isEnabled());
        }
    }

    /**
     * 启用/禁用部分控件。
     * @param disabled 是否禁用
     */
    private void disableControls(boolean disabled) {
        deviceCombo.setEnabled(!disabled);
        refreshDevicesBtn.setEnabled(!disabled);
        addRemoteBtn.setEnabled(!disabled);
    }

    /**
     * 项目切换完成后的待执行动作。
     */
    private static final class PendingProjectAction {
        private final @NotNull ZaFridaFridaProject expectedProject;
        private final @NotNull Runnable action;

        private PendingProjectAction(@NotNull ZaFridaFridaProject expectedProject, @NotNull Runnable action) {
            this.expectedProject = expectedProject;
            this.action = action;
        }
    }

    /**
     * 释放资源。
     */
    @Override
    public void dispose() {
        localHttpApiService.unbindRunPanel(this);
        // project service handles stop
        // 项目服务负责停止会话
    }
}
