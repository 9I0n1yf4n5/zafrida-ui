package com.zafrida.ui.settings;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.zafrida.ui.util.ProjectFileUtil;
import com.zafrida.ui.util.ZaStrUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
/**
 * [UI组件] 全局设置面板的 Swing 实现。
 * <p>
 * <strong>包含控件：</strong>
 * <ul>
 * <li>Frida 工具链路径输入框 (frida, frida-ps, frida-ls-devices)。</li>
 * <li>VS Code 路径输入框（可选，用于一键打开日志文件）。</li>
 * <li>010 Editor 路径输入框（可选，用于一键打开二进制日志/trace 文件）。</li>
 * <li>日志目录配置。</li>
 * <li>远程主机列表 (Remote Hosts) 管理。</li>
 * </ul>
 * 它是 {@link ZaFridaSettingsConfigurable} 的视图层。
 */
public final class ZaFridaSettingsComponent {

    /** ZAFRIDA 根目录名 */
    private static final String ZAFRIDA_DIR_NAME = ".zafrida";
    /** 模板目录名 */
    private static final String TEMPLATES_DIR_NAME = "templates";

    /** frida 路径输入框 */
    private final JBTextField fridaField = new JBTextField();
    /** frida-ps 路径输入框 */
    private final JBTextField fridaPsField = new JBTextField();
    /** frida-ls-devices 路径输入框 */
    private final JBTextField fridaLsDevicesField = new JBTextField();
    /** Frida 主版本输入框 */
    private final JBTextField fridaVersionField = new JBTextField();
    /** VS Code 路径输入框 */
    private final JBTextField vscodeField = new JBTextField();
    /** 010 Editor 路径输入框 */
    private final JBTextField editor010Field = new JBTextField();
    /** 日志目录输入框 */
    private final JBTextField logsDirField = new JBTextField();
    /** 默认远程主机输入框 */
    private final JBTextField defaultRemoteHostField = new JBTextField();
    /** 默认远程端口输入框 */
    private final JBTextField defaultRemotePortField = new JBTextField();
    /** 是否使用 IDE 脚本选择器 */
    private final JBCheckBox useIdeScriptChooserCheckBox = new JBCheckBox("Use IDE script chooser (Project tree)");
    /** 是否开启 Skills 本地 HTTP API */
    private final JBCheckBox enableSkillsHttpApiCheckBox = new JBCheckBox("Enable Skills local HTTP API");
    /** Skills 本地 HTTP API 端口 */
    private final JBTextField skillsApiPortField = new JBTextField();
    /** 手动启动 Skills API */
    private final JButton startSkillsApiBtn = new JButton("Start");
    /** 手动停止 Skills API */
    private final JButton stopSkillsApiBtn = new JButton("Stop");
    /** Skills API 状态提示 */
    private final JLabel skillsApiStatusLabel = new JLabel("Stopped");
    /** 模板根目录模式下拉框 */
    private final JComboBox<TemplateRootOption> templatesRootModeCombo = new JComboBox<>();
    /** 模板根目录路径显示框 */
    private final TextFieldWithBrowseButton templatesRootPathField = new TextFieldWithBrowseButton();

    /** 远程主机列表模型 */
    private final DefaultListModel<String> remoteModel = new DefaultListModel<>();
    /** 远程主机列表组件 */
    private final JBList<String> remoteList = new JBList<>(remoteModel);
    /** 添加远程主机按钮 */
    private final JButton addRemoteBtn = new JButton("Add");
    /** 移除远程主机按钮 */
    private final JButton removeRemoteBtn = new JButton("Remove");

    /** 根面板 */
    private final JComponent panel;
    /** 手动启动回调 */
    private @Nullable Runnable startSkillsApiHandler;
    /** 手动停止回调 */
    private @Nullable Runnable stopSkillsApiHandler;
    /** 当前运行状态 */
    private boolean skillsApiRunning = false;
    /** UI 是否在批量更新中 */
    private boolean updatingUi = false;

    /**
     * 构造函数，初始化 UI。
     */
    public ZaFridaSettingsComponent() {
        addRemoteBtn.setIcon(AllIcons.General.Add);
        removeRemoteBtn.setIcon(AllIcons.General.Remove);

        JPanel remoteButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        remoteButtons.add(addRemoteBtn);
        remoteButtons.add(removeRemoteBtn);

        JPanel remotePanel = new JPanel(new BorderLayout(0, 8));
        remotePanel.add(new JBScrollPane(remoteList), BorderLayout.CENTER);
        remotePanel.add(remoteButtons, BorderLayout.SOUTH);

        defaultRemoteHostField.setColumns(16);
        defaultRemotePortField.setColumns(6);
        fridaVersionField.setColumns(6);
        skillsApiPortField.setColumns(6);
        defaultRemoteHostField.getEmptyText().setText("127.0.0.1");
        defaultRemotePortField.getEmptyText().setText("14725");
        skillsApiPortField.getEmptyText().setText(String.valueOf(ZaFridaSettingsState.DEFAULT_SKILLS_API_PORT));
        fridaVersionField.getEmptyText().setText(ZaFridaSettingsService.DEFAULT_FRIDA_VERSION);
        fridaVersionField.setToolTipText("Frida major version. e.g. 16 / 17");
        vscodeField.getEmptyText().setText("code / code.cmd / Code.exe");
        vscodeField.setToolTipText("Optional. Used for opening log file in VS Code.");
        editor010Field.getEmptyText().setText("/Applications/010 Editor.app / 010Editor.exe");
        editor010Field.setToolTipText("Optional. Used for opening file in 010 Editor.");
        JPanel defaultRemotePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        defaultRemotePanel.add(defaultRemoteHostField);
        defaultRemotePanel.add(new JLabel(":"));
        defaultRemotePanel.add(defaultRemotePortField);

        startSkillsApiBtn.setIcon(AllIcons.Actions.Execute);
        stopSkillsApiBtn.setIcon(AllIcons.Actions.Suspend);
        JPanel skillsApiPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        skillsApiPanel.add(enableSkillsHttpApiCheckBox);
        skillsApiPanel.add(new JLabel("Port"));
        skillsApiPanel.add(skillsApiPortField);
        skillsApiPanel.add(startSkillsApiBtn);
        skillsApiPanel.add(stopSkillsApiBtn);
        skillsApiPanel.add(skillsApiStatusLabel);

        templatesRootModeCombo.addItem(new TemplateRootOption(
                ZaFridaSettingsState.TEMPLATE_ROOT_MODE_SYSTEM,
                "System (User Home)"
        ));
        templatesRootModeCombo.addItem(new TemplateRootOption(
                ZaFridaSettingsState.TEMPLATE_ROOT_MODE_IDE,
                "IDE Project Root"
        ));
        templatesRootModeCombo.addActionListener(e -> updateTemplatesRootPathField());

        templatesRootPathField.getTextField().setEditable(false);
        templatesRootPathField.setToolTipText("Open templates folder");
        templatesRootPathField.addActionListener(e -> locateTemplatesFolder());

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("frida", fridaField, 1, false)
                .addLabeledComponent("frida-ps", fridaPsField, 1, false)
                .addLabeledComponent("frida-ls-devices", fridaLsDevicesField, 1, false)
                .addLabeledComponent("Frida Version", fridaVersionField, 1, false)
                .addLabeledComponent("VS Code (optional)", vscodeField, 1, false)
                .addLabeledComponent("010 Editor (optional)", editor010Field, 1, false)
                .addLabeledComponent("Logs Dir (relative to project)", logsDirField, 1, false)
                .addLabeledComponent("Templates Root", templatesRootModeCombo, 1, false)
                .addLabeledComponent("Templates Path", templatesRootPathField, 1, false)
                .addLabeledComponent("Script Chooser", useIdeScriptChooserCheckBox, 1, false)
                .addLabeledComponent("Skills HTTP API", skillsApiPanel, 1, false)
                .addLabeledComponent("Default Remote Host:Port", defaultRemotePanel, 1, false)
                .addLabeledComponent("Remote Hosts (host:port)", remotePanel, 1, false)
                .getPanel();

        addRemoteBtn.addActionListener(e -> {
            String defHost = textOrDefault(defaultRemoteHostField.getText(), "127.0.0.1");
            String defPort = textOrDefault(defaultRemotePortField.getText(), "14725");
            String initial = String.format("%s:%s", defHost, defPort);
            String input = Messages.showInputDialog(panel, "host:port", "ZAFrida", null, initial, null);
            if (input == null) {
                return;
            }
            String h = input.trim();
            if (!h.isEmpty() && !containsRemote(h)) {
                remoteModel.addElement(h);
            }
        });

        removeRemoteBtn.addActionListener(e -> {
            int idx = remoteList.getSelectedIndex();
            if (idx >= 0) {
                remoteModel.remove(idx);
            }
        });

        enableSkillsHttpApiCheckBox.addActionListener(e -> {
            if (updatingUi) {
                return;
            }
            updateSkillsApiControlsEnabled();
            if (enableSkillsHttpApiCheckBox.isSelected()) {
                if (startSkillsApiHandler != null) {
                    startSkillsApiHandler.run();
                }
                return;
            }
            if (stopSkillsApiHandler != null) {
                stopSkillsApiHandler.run();
            }
        });

        startSkillsApiBtn.addActionListener(e -> {
            if (startSkillsApiHandler != null) {
                startSkillsApiHandler.run();
            }
        });

        stopSkillsApiBtn.addActionListener(e -> {
            if (stopSkillsApiHandler != null) {
                stopSkillsApiHandler.run();
            }
        });

        updateSkillsApiControlsEnabled();
    }

    /**
     * 获取根面板。
     * @return 面板组件
     */
    public @NotNull JComponent getPanel() {
        return panel;
    }

    /**
     * 绑定 Skills API 的手动控制回调。
     * @param startHandler 启动回调
     * @param stopHandler 停止回调
     */
    public void bindSkillsApiActions(@Nullable Runnable startHandler, @Nullable Runnable stopHandler) {
        this.startSkillsApiHandler = startHandler;
        this.stopSkillsApiHandler = stopHandler;
        updateSkillsApiControlsEnabled();
    }

    /**
     * 设置 Skills API 状态文案与运行态。
     * @param status 文案
     * @param running 是否运行中
     */
    public void setSkillsApiStatus(@NotNull String status, boolean running) {
        skillsApiStatusLabel.setText(status);
        skillsApiRunning = running;
        updateSkillsApiControlsEnabled();
    }

    /**
     * 展示 Skills API 操作提示。
     * @param message 提示文本
     * @param error 是否错误提示
     */
    public void showSkillsApiTip(@NotNull String message, boolean error) {
        if (error) {
            Messages.showErrorDialog(panel, message, "ZAFrida");
            return;
        }
        Messages.showInfoMessage(panel, message, "ZAFrida");
    }

    /**
     * 是否开启 Skills API。
     * @return true 表示开启
     */
    public boolean isSkillsApiEnabled() {
        return enableSkillsHttpApiCheckBox.isSelected();
    }

    /**
     * 读取 Skills API 端口。
     * @return 端口号
     */
    public int getSkillsApiPort() {
        return parsePort(skillsApiPortField.getText(), ZaFridaSettingsState.DEFAULT_SKILLS_API_PORT);
    }

    /**
     * 使用状态重置 UI。
     * @param state 配置状态
     */
    public void reset(@NotNull ZaFridaSettingsState state) {
        updatingUi = true;
        try {
            fridaField.setText(orDefault(state.fridaExecutable, "frida"));
            fridaPsField.setText(orDefault(state.fridaPsExecutable, "frida-ps"));
            fridaLsDevicesField.setText(orDefault(state.fridaLsDevicesExecutable, "frida-ls-devices"));
            fridaVersionField.setText(normalizeFridaVersion(state.fridaVersion));
            vscodeField.setText(orDefault(state.vscodeExecutable, ""));
            editor010Field.setText(orDefault(state.editor010Executable, ""));
            logsDirField.setText(orDefault(state.logsDirName, "zafrida-logs"));
            defaultRemoteHostField.setText(orDefault(state.defaultRemoteHost, "127.0.0.1"));
            defaultRemotePortField.setText(String.valueOf(state.defaultRemotePort > 0 ? state.defaultRemotePort : 14725));
            useIdeScriptChooserCheckBox.setSelected(state.useIdeScriptChooser);
            enableSkillsHttpApiCheckBox.setSelected(state.enableSkillsHttpApi);
            int apiPort = state.skillsApiPort;
            if (apiPort <= 0 || apiPort > 65535) {
                apiPort = ZaFridaSettingsState.DEFAULT_SKILLS_API_PORT;
            }
            skillsApiPortField.setText(String.valueOf(apiPort));
            setSelectedTemplatesRootMode(state.templatesRootMode);
            updateTemplatesRootPathField();

            remoteModel.clear();
            if (state.remoteHosts != null) {
                for (String h : state.remoteHosts) {
                    if (ZaStrUtil.isNotBlank(h)) {
                        remoteModel.addElement(h);
                    }
                }
            }
            skillsApiRunning = false;
            skillsApiStatusLabel.setText(state.enableSkillsHttpApi ? "Stopped" : "Disabled");
            updateSkillsApiControlsEnabled();
        } finally {
            updatingUi = false;
        }
    }

    /**
     * 将 UI 值写回状态对象。
     * @param state 配置状态
     */
    public void applyTo(@NotNull ZaFridaSettingsState state) {
        state.fridaExecutable = textOrDefault(fridaField.getText(), "frida");
        state.fridaPsExecutable = textOrDefault(fridaPsField.getText(), "frida-ps");
        state.fridaLsDevicesExecutable = textOrDefault(fridaLsDevicesField.getText(), "frida-ls-devices");
        state.fridaVersion = normalizeFridaVersion(fridaVersionField.getText());
        state.vscodeExecutable = textOrDefault(vscodeField.getText(), "");
        state.editor010Executable = textOrDefault(editor010Field.getText(), "");
        state.logsDirName = textOrDefault(logsDirField.getText(), "zafrida-logs");
        state.defaultRemoteHost = textOrDefault(defaultRemoteHostField.getText(), "127.0.0.1");
        state.defaultRemotePort = parsePort(defaultRemotePortField.getText(), 14725);
        state.useIdeScriptChooser = useIdeScriptChooserCheckBox.isSelected();
        state.enableSkillsHttpApi = enableSkillsHttpApiCheckBox.isSelected();
        state.skillsApiPort = parsePort(skillsApiPortField.getText(), ZaFridaSettingsState.DEFAULT_SKILLS_API_PORT);
        state.templatesRootMode = getSelectedTemplatesRootMode();

        List<String> remotes = new ArrayList<>();
        for (int i = 0; i < remoteModel.size(); i++) {
            remotes.add(remoteModel.getElementAt(i));
        }
        state.remoteHosts = remotes;
    }

    /**
     * 更新模板路径显示。
     */
    private void updateTemplatesRootPathField() {
        String mode = getSelectedTemplatesRootMode();
        Path root = resolveTemplatesRootPreview(mode);
        if (root == null) {
            templatesRootPathField.setText("No open project");
            templatesRootPathField.setToolTipText("No open project");
            return;
        }
        templatesRootPathField.setText(root.toString());
        templatesRootPathField.setToolTipText(root.toString());
    }

    /**
     * 打开或定位模板目录。
     */
    private void locateTemplatesFolder() {
        String mode = getSelectedTemplatesRootMode();
        Path root = resolveTemplatesRootPreview(mode);
        if (root == null) {
            Messages.showWarningDialog(panel, "No open project found to locate templates.", "ZAFrida");
            return;
        }
        if (ZaFridaSettingsState.TEMPLATE_ROOT_MODE_IDE.equals(mode)) {
            openTemplatesFolderInIde(root);
        } else {
            openTemplatesFolderInSystem(root);
        }
    }

    /**
     * 使用系统文件管理器打开模板目录。
     * @param root 模板根目录
     */
    private void openTemplatesFolderInSystem(@NotNull Path root) {
        if (!Desktop.isDesktopSupported()) {
            Messages.showWarningDialog(panel, "Desktop open is not supported on this platform.", "ZAFrida");
            return;
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.OPEN)) {
            Messages.showWarningDialog(panel, "Desktop open action is not supported on this platform.", "ZAFrida");
            return;
        }
        try {
            desktop.open(root.toFile());
        } catch (Exception e) {
            Messages.showErrorDialog(panel, String.format("Failed to open folder: %s", e.getMessage()), "ZAFrida");
        }
    }

    /**
     * 在 IDE 中定位模板目录。
     * @param root 模板根目录
     */
    private void openTemplatesFolderInIde(@NotNull Path root) {
        Project project = resolveActiveProject();
        if (project == null) {
            Messages.showWarningDialog(panel, "No open project found to locate templates.", "ZAFrida");
            return;
        }
        VirtualFile dir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root.toFile());
        if (dir == null) {
            Messages.showWarningDialog(panel, String.format("Templates folder not found: %s", root), "ZAFrida");
            return;
        }
        ProjectFileUtil.openAndSelectInProject(project, dir);
    }

    /**
     * 预览模板根目录路径。
     * @param mode 模板根目录模式
     * @return 预览路径或 null
     */
    private @Nullable Path resolveTemplatesRootPreview(@NotNull String mode) {
        if (ZaFridaSettingsState.TEMPLATE_ROOT_MODE_IDE.equals(mode)) {
            Project project = resolveActiveProject();
            if (project == null) {
                return null;
            }
            String basePath = project.getBasePath();
            if (basePath == null || basePath.trim().isEmpty()) {
                return null;
            }
            return Paths.get(basePath, ZAFRIDA_DIR_NAME, TEMPLATES_DIR_NAME);
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.trim().isEmpty()) {
            return Paths.get(ZAFRIDA_DIR_NAME, TEMPLATES_DIR_NAME).toAbsolutePath();
        }
        return Paths.get(userHome, ZAFRIDA_DIR_NAME, TEMPLATES_DIR_NAME);
    }

    /**
     * 尝试获取当前打开的项目。
     * @return Project 或 null
     */
    private @Nullable Project resolveActiveProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        if (projects.length == 0) {
            return null;
        }
        for (Project project : projects) {
            if (project != null && !project.isDisposed()) {
                return project;
            }
        }
        return null;
    }

    /**
     * 获取当前选中的模板根目录模式。
     * @return 模式值
     */
    private @NotNull String getSelectedTemplatesRootMode() {
        TemplateRootOption option = (TemplateRootOption) templatesRootModeCombo.getSelectedItem();
        if (option == null) {
            return ZaFridaSettingsState.TEMPLATE_ROOT_MODE_SYSTEM;
        }
        return option.getId();
    }

    /**
     * 设置模板根目录模式选择。
     * @param mode 模式值
     */
    private void setSelectedTemplatesRootMode(@Nullable String mode) {
        String normalized = normalizeTemplatesRootMode(mode);
        int count = templatesRootModeCombo.getItemCount();
        for (int i = 0; i < count; i++) {
            TemplateRootOption option = templatesRootModeCombo.getItemAt(i);
            if (option != null && normalized.equals(option.getId())) {
                templatesRootModeCombo.setSelectedItem(option);
                return;
            }
        }
        if (count > 0) {
            templatesRootModeCombo.setSelectedIndex(0);
        }
    }

    /**
     * 标准化模板根目录模式值。
     * @param mode 原始值
     * @return 标准化后的模式值
     */
    private @NotNull String normalizeTemplatesRootMode(@Nullable String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return ZaFridaSettingsState.TEMPLATE_ROOT_MODE_SYSTEM;
        }
        String normalized = mode.trim();
        if (ZaFridaSettingsState.TEMPLATE_ROOT_MODE_IDE.equalsIgnoreCase(normalized)) {
            return ZaFridaSettingsState.TEMPLATE_ROOT_MODE_IDE;
        }
        return ZaFridaSettingsState.TEMPLATE_ROOT_MODE_SYSTEM;
    }

    /**
     * 判断远程主机是否已存在。
     * @param host 主机字符串
     * @return true 表示已存在
     */
    private boolean containsRemote(String host) {
        for (int i = 0; i < remoteModel.size(); i++) {
            if (host.equals(remoteModel.getElementAt(i))) return true;
        }
        return false;
    }

    /**
     * 获取文本或默认值（允许空白）。
     * @param s 输入文本
     * @param d 默认值
     * @return 结果字符串
     */
    private static String textOrDefault(String s, String d) {
        if (ZaStrUtil.isBlank(s)) {
            return d;
        }
        return ZaStrUtil.trim(s);
    }

    /**
     * 获取文本或默认值（空白视为默认）。
     * @param s 输入文本
     * @param d 默认值
     * @return 结果字符串
     */
    private static String orDefault(String s, String d) {
        if (ZaStrUtil.isBlank(s)) {
            return d;
        }
        return s;
    }

    /**
     * 按开关与运行态更新控件可用性。
     */
    private void updateSkillsApiControlsEnabled() {
        boolean enabled = enableSkillsHttpApiCheckBox.isSelected();
        skillsApiPortField.setEnabled(enabled);
        if (!enabled) {
            startSkillsApiBtn.setEnabled(false);
            stopSkillsApiBtn.setEnabled(false);
            return;
        }
        startSkillsApiBtn.setEnabled(!skillsApiRunning);
        stopSkillsApiBtn.setEnabled(skillsApiRunning);
    }

    /**
     * 解析端口文本。
     * @param s 输入文本
     * @param fallback 回退值
     * @return 端口值
     */
    private static int parsePort(String s, int fallback) {
        if (ZaStrUtil.isBlank(s)) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 && v <= 65535 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 标准化 Frida 版本输入值。
     * @param versionText 原始文本
     * @return 标准化版本号
     */
    private static @NotNull String normalizeFridaVersion(@Nullable String versionText) {
        if (ZaStrUtil.isBlank(versionText)) {
            return ZaFridaSettingsService.DEFAULT_FRIDA_VERSION;
        }
        String normalized = versionText.trim();
        if (normalized.isEmpty()) {
            return ZaFridaSettingsService.DEFAULT_FRIDA_VERSION;
        }
        return normalized;
    }

    /**
     * 模板根目录选项。
     */
    private static final class TemplateRootOption {
        private final String id;
        private final String label;

        private TemplateRootOption(@NotNull String id, @NotNull String label) {
            this.id = id;
            this.label = label;
        }

        public String getId() {
            return id;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
