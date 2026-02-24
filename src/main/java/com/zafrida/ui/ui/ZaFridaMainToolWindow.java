package com.zafrida.ui.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.icons.AllIcons;
import com.zafrida.ui.util.ZaFridaIcons;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
/**
 * [UI 根组件] ZAFrida ToolWindow 的顶层容器。
 * <p>
 * <strong>布局结构：</strong>
 * <ul>
 * <li><strong>Header:</strong> 顶部工具栏（创建项目、设置、全局运行控制）。</li>
 * <li><strong>TabPane:</strong> 中间选项卡（Run Panel / Template Panel）。</li>
 * <li><strong>Console:</strong> 底部日志控制台（Run/Attach 分离）。</li>
 * </ul>
 * <strong>职责：</strong> 负责各子组件的初始化、布局组装及全局按钮事件的分发。
 */
public final class ZaFridaMainToolWindow extends JPanel implements Disposable {

    /** 顶部选项卡面板 */
    private final JBTabbedPane tabbedPane;
    /** Run 面板 */
    private final ZaFridaRunPanel runPanel;
    /** 模板面板 */
    private final ZaFridaTemplatePanel templatePanel;
    /** 工具面板（不常用功能集合） */
    private final ZaFridaToolsPanel toolsPanel;
    /** 控制台选项卡面板 */
    private final ZaFridaConsoleTabsPanel consoleTabsPanel;

    /**
     * 构造函数。
     * @param project 当前 IDE 项目
     */
    public ZaFridaMainToolWindow(@NotNull Project project) {
        super(new BorderLayout());

        this.consoleTabsPanel = new ZaFridaConsoleTabsPanel(project);
        this.templatePanel = new ZaFridaTemplatePanel(project, consoleTabsPanel.getRunConsolePanel());
        this.runPanel = new ZaFridaRunPanel(project, consoleTabsPanel, templatePanel);
        this.toolsPanel = new ZaFridaToolsPanel(project, consoleTabsPanel);

        Disposer.register(this, consoleTabsPanel);
        Disposer.register(this, templatePanel);
        Disposer.register(this, runPanel);
        Disposer.register(this, toolsPanel);

        tabbedPane = new JBTabbedPane();
        tabbedPane.setTabComponentInsets(JBUI.emptyInsets());

        tabbedPane.addTab("Run", runPanel);
        tabbedPane.addTab("Templates", templatePanel);
        tabbedPane.addTab("Tools", toolsPanel);

        JPanel header = buildHeader();
        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.add(header, BorderLayout.NORTH);
        topContainer.add(tabbedPane, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setTopComponent(topContainer);
        splitPane.setBottomComponent(consoleTabsPanel);
        splitPane.setResizeWeight(0.6);
        splitPane.setDividerSize(JBUI.scale(4));
        splitPane.setBorder(JBUI.Borders.empty());

        add(splitPane, BorderLayout.CENTER);
    }

    /**
     * 获取控制台选项卡面板。
     * @return 控制台面板
     */
    public @NotNull ZaFridaConsoleTabsPanel getConsoleTabsPanel() {
        return consoleTabsPanel;
    }

    /**
     * 获取 Run 面板。
     * @return Run 面板
     */
    public @NotNull ZaFridaRunPanel getRunPanel() {
        return runPanel;
    }

    /**
     * 获取模板面板。
     * @return 模板面板
     */
    public @NotNull ZaFridaTemplatePanel getTemplatePanel() {
        return templatePanel;
    }

    /**
     * 构建顶部工具栏区域。
     * @return Header 面板
     */
    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(JBUI.Borders.empty(6, 8));

        JPanel projectRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton newProjectBtn = new JButton("New Project");
        newProjectBtn.setIcon(scaleIcon(ZaFridaIcons.FRIDA_PROJECT, 0.875F));
        newProjectBtn.setToolTipText("New Frida Project");
        newProjectBtn.addActionListener(e -> runPanel.openNewProjectDialog());

        JButton projectSettingsBtn = new JButton("Project");
        projectSettingsBtn.setIcon(AllIcons.General.Settings);
        projectSettingsBtn.setToolTipText("Open project settings");
        projectSettingsBtn.addActionListener(e -> runPanel.openProjectSettingsDialog());

        JButton globalSettingsBtn = new JButton("Global");
        globalSettingsBtn.setIcon(AllIcons.General.Settings);
        globalSettingsBtn.setToolTipText("Open global settings");
        globalSettingsBtn.addActionListener(e -> runPanel.openGlobalSettingsDialog());

        JButton doctorBtn = new JButton("Doctor");
        doctorBtn.setIcon(AllIcons.General.InspectionsOK);
        doctorBtn.setToolTipText("Environment doctor");
        doctorBtn.addActionListener(e -> runPanel.openEnvironmentDoctorDialog());

        /*JButton languageToggleBtn = new JButton(
                IconLoader.getIcon("/META-INF/icons/lang-toggle.svg", ZaFridaMainToolWindow.class)
        );
        languageToggleBtn.setToolTipText("中文 / English");
        languageToggleBtn.addActionListener(e -> runPanel.showLanguageToggleMessage());*/

        projectRow.add(newProjectBtn);
        projectRow.add(projectSettingsBtn);
        projectRow.add(globalSettingsBtn);
        projectRow.add(doctorBtn);
        // projectRow.add(languageToggleBtn);

        JPanel runRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton runBtn = new JButton("Run");
        runBtn.setIcon(AllIcons.Actions.Execute);
        runBtn.addActionListener(e -> runPanel.triggerRun());

        JButton stopBtn = new JButton("Stop");
        stopBtn.setIcon(AllIcons.Actions.Suspend);
        stopBtn.addActionListener(e -> runPanel.triggerStop());

        JButton forceStopBtn = new JButton("Stop App");
        forceStopBtn.setIcon(AllIcons.Actions.Cancel);
        forceStopBtn.setToolTipText("Force Stop App (adb force-stop)");
        forceStopBtn.addActionListener(e -> runPanel.triggerForceStop());

        JButton openAppBtn = new JButton("Open App");
        openAppBtn.setIcon(AllIcons.Actions.Execute);
        openAppBtn.setToolTipText("Open App (adb)");
        openAppBtn.addActionListener(e -> runPanel.triggerOpenApp());

        runPanel.bindExternalRunStopButtons(runBtn, stopBtn);

        runRow.add(runBtn);
        runRow.add(stopBtn);
        runRow.add(forceStopBtn);
        runRow.add(openAppBtn);

        header.add(projectRow);
        header.add(Box.createVerticalStrut(JBUI.scale(4)));
        header.add(runRow);

        return header;
    }

    /**
     * 对按钮图标做轻量等比缩放，用于与同排按钮视觉对齐。
     *
     * @param baseIcon 原始图标
     * @param scale 缩放比例（0~1 表示缩小）
     * @return 缩放后的图标
     */
    private static @NotNull Icon scaleIcon(@NotNull Icon baseIcon, float scale) {
        float effectiveScale = scale;
        if (effectiveScale <= 0.0F) {
            effectiveScale = 1.0F;
        }
        final float finalScale = effectiveScale;
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.translate(x, y);
                    g2.scale(finalScale, finalScale);
                    baseIcon.paintIcon(c, g2, 0, 0);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return Math.max(1, Math.round(baseIcon.getIconWidth() * finalScale));
            }

            @Override
            public int getIconHeight() {
                return Math.max(1, Math.round(baseIcon.getIconHeight() * finalScale));
            }
        };
    }

    /**
     * 释放资源。
     */
    @Override
    public void dispose() {
        // children disposed via Disposer
        // 子组件由 Disposer 统一释放
    }
}
