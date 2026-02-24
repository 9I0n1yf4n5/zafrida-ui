package com.zafrida.ui.ui;

import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SearchTextField;
import com.zafrida.ui.util.ProjectFileUtil;
import com.zafrida.ui.util.ZaFridaIcons;
import com.zafrida.ui.util.ZaFridaNotifier;
import com.zafrida.ui.util.ZaFridaVsCodeUtil;
import com.zafrida.ui.util.ZaStrUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.io.File;

/**
 * [UI组件] 自定义控制台面板。
 * <p>
 * 封装了 {@link ConsoleView}，并提供日志文件工具栏（定位/打开/VS Code）与简单的搜索工具栏。
 * 它是 {@link com.zafrida.ui.session.ZaFridaSessionService} 输出日志的目标容器。
 */
public final class ZaFridaConsolePanel extends JPanel implements Disposable {

    /** 当前 IDE 项目 */
    private final @NotNull Project project;

    /** 日志文件路径提示标签 */
    private final JLabel logFileLabel = new JLabel("Log: (not started)");
    /** 定位日志文件按钮 */
    private final JButton locateLogFileBtn = new JButton("");
    /** 打开日志文件按钮（IDE 编辑器） */
    private final JButton openLogFileBtn = new JButton("");
    /** 使用 VS Code 打开日志文件按钮 */
    private final JButton openLogFileInVsCodeBtn = new JButton("");
    /** 清空当前控制台按钮 */
    private final JButton clearConsoleBtn = new JButton("");
    /** 最近一次会话日志文件路径（用于工具栏按钮） */
    private @Nullable String lastLogFilePath;

    /** 控制台视图 */
    private final ConsoleView consoleView;
    /** 搜索输入框 */
    private final SearchTextField searchField = new SearchTextField();
    /** 上一次匹配起始位置 */
    private int lastMatchStart = -1;
    /** 上一次查询关键字 */
    private String lastQuery = "";

    /**
     * 构造函数。
     * @param project 当前 IDE 项目
     */
    public ZaFridaConsolePanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.consoleView = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .getConsole();
        initLogToolbar();
        add(buildTopToolbarPanel(), BorderLayout.NORTH);
        add(consoleView.getComponent(), BorderLayout.CENTER);
    }

    /**
     * 获取控制台视图。
     * @return ConsoleView
     */
    public ConsoleView getConsoleView() {
        return consoleView;
    }

    /**
     * 清空控制台内容。
     */
    public void clear() {
        consoleView.clear();
        lastMatchStart = -1;
    }

    /**
     * 打印普通信息。
     * @param message 文本内容
     */
    public void info(String message) {
        consoleView.print(String.format("%s\n", message), ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /**
     * 打印警告信息。
     * @param message 文本内容
     */
    public void warn(String message) {
        consoleView.print(String.format("%s\n", message), ConsoleViewContentType.LOG_WARNING_OUTPUT);
    }

    /**
     * 打印错误信息。
     * @param message 文本内容
     */
    public void error(String message) {
        consoleView.print(String.format("%s\n", message), ConsoleViewContentType.ERROR_OUTPUT);
    }

    /**
     * 设置当前控制台对应的日志文件路径（Run/Attach 各自独立）。
     * <p>
     * 仅用于 UI 展示与快捷操作，不影响日志写入逻辑。
     *
     * @param logFilePath 日志文件路径；当为空或不可用时按钮会禁用
     */
    public void setLogFilePath(@Nullable String logFilePath) {
        this.lastLogFilePath = logFilePath;

        if (ZaStrUtil.isBlank(logFilePath)) {
            logFileLabel.setText("Log: (not started)");
            logFileLabel.setToolTipText("Log: (not started)");
            locateLogFileBtn.setEnabled(false);
            openLogFileBtn.setEnabled(false);
            openLogFileInVsCodeBtn.setEnabled(false);
            return;
        }

        String trimmed = logFilePath.trim();
        logFileLabel.setText(String.format("Log: %s", trimmed));
        logFileLabel.setToolTipText(trimmed);

        boolean enabled = !trimmed.startsWith("(");
        locateLogFileBtn.setEnabled(enabled);
        openLogFileBtn.setEnabled(enabled);
        openLogFileInVsCodeBtn.setEnabled(enabled);
    }

    private void initLogToolbar() {
        locateLogFileBtn.setIcon(AllIcons.General.Locate);
        locateLogFileBtn.setToolTipText("Locate log file in Project View");
        tuneLogToolbarIconButton(locateLogFileBtn);
        locateLogFileBtn.setEnabled(false);

        openLogFileBtn.setIcon(AllIcons.Actions.EditSource);
        openLogFileBtn.setToolTipText("Open log file in editor");
        tuneLogToolbarIconButton(openLogFileBtn);
        openLogFileBtn.setEnabled(false);

        openLogFileInVsCodeBtn.setIcon(ZaFridaIcons.VSCODE);
        openLogFileInVsCodeBtn.setToolTipText("Open log file in VS Code");
        tuneLogToolbarIconButton(openLogFileInVsCodeBtn);
        openLogFileInVsCodeBtn.setEnabled(false);

        clearConsoleBtn.setIcon(AllIcons.Actions.ClearCash);
        clearConsoleBtn.setToolTipText("Clear console (does not affect log file)");
        tuneLogToolbarIconButton(clearConsoleBtn);

        locateLogFileBtn.addActionListener(e -> locateLogFileInProjectView());
        openLogFileBtn.addActionListener(e -> openLogFileInEditor());
        openLogFileInVsCodeBtn.addActionListener(e -> openLogFileInVsCode());
        clearConsoleBtn.addActionListener(e -> clear());
    }

    private JPanel buildTopToolbarPanel() {
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(buildLogToolbarPanel(), BorderLayout.NORTH);
        top.add(buildSearchPanel(), BorderLayout.SOUTH);
        return top;
    }

    private JPanel buildLogToolbarPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.add(logFileLabel, BorderLayout.CENTER);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actionsPanel.add(clearConsoleBtn);
        actionsPanel.add(locateLogFileBtn);
        actionsPanel.add(openLogFileBtn);
        actionsPanel.add(openLogFileInVsCodeBtn);
        panel.add(actionsPanel, BorderLayout.EAST);
        return panel;
    }

    /**
     * 构建搜索工具栏。
     * @return 面板组件
     */
    private JPanel buildSearchPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        searchField.getTextEditor().addActionListener(event -> findNext(true));
        panel.add(searchField, BorderLayout.CENTER);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton prevButton = new JButton(AllIcons.Actions.PreviousOccurence);
        JButton nextButton = new JButton(AllIcons.Actions.NextOccurence);
        prevButton.setToolTipText("Previous Occurrence");
        nextButton.setToolTipText("Next Occurrence");
        prevButton.addActionListener(event -> findNext(false));
        nextButton.addActionListener(event -> findNext(true));
        actionsPanel.add(prevButton);
        actionsPanel.add(nextButton);
        panel.add(actionsPanel, BorderLayout.EAST);
        return panel;
    }

    private void locateLogFileInProjectView() {
        String rawPath = lastLogFilePath;
        String path = normalizeLogFilePath(rawPath);
        if (path == null) {
            notifyLogFileUnavailable(rawPath);
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
            if (file != null && file.isValid() && !file.isDirectory()) {
                ApplicationManager.getApplication().invokeLater(() -> ProjectFileUtil.openAndSelectLogFileReadOnly(project, file));
                return;
            }

            File ioFile = new File(path);
            File parent = ioFile.getParentFile();
            if (parent != null) {
                VirtualFile dir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent);
                if (dir != null && dir.isValid()) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ProjectFileUtil.openAndSelectInProject(project, dir);
                        ZaFridaNotifier.warn(project, "ZAFrida", String.format("Log file not found, located directory: %s", parent.getAbsolutePath()));
                    });
                    return;
                }
            }

            ApplicationManager.getApplication().invokeLater(() ->
                    ZaFridaNotifier.warn(project, "ZAFrida", String.format("Log file not found: %s", path)));
        });
    }

    private void openLogFileInEditor() {
        String rawPath = lastLogFilePath;
        String path = normalizeLogFilePath(rawPath);
        if (path == null) {
            notifyLogFileUnavailable(rawPath);
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
            if (file == null || !file.isValid() || file.isDirectory()) {
                ApplicationManager.getApplication().invokeLater(() ->
                        ZaFridaNotifier.warn(project, "ZAFrida", String.format("Log file not found: %s", path)));
                return;
            }
            ApplicationManager.getApplication().invokeLater(() ->
                    FileEditorManager.getInstance(project).openFile(file, true));
        });
    }

    private void openLogFileInVsCode() {
        String rawPath = lastLogFilePath;
        String path = normalizeLogFilePath(rawPath);
        if (path == null) {
            notifyLogFileUnavailable(rawPath);
            return;
        }
        ZaFridaVsCodeUtil.openFileInVsCodeAsync(project, path);
    }

    private static void tuneLogToolbarIconButton(@NotNull JButton btn) {
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setOpaque(false);
        btn.setMargin(new Insets(0, 0, 0, 0));
        btn.setPreferredSize(new Dimension(18, 18));
    }

    private static @Nullable String normalizeLogFilePath(@Nullable String rawPath) {
        if (ZaStrUtil.isBlank(rawPath)) {
            return null;
        }
        String trimmed = rawPath.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("(")) {
            return null;
        }
        return trimmed;
    }

    private void notifyLogFileUnavailable(@Nullable String rawPath) {
        if (ZaStrUtil.isBlank(rawPath)) {
            ZaFridaNotifier.warn(project, "ZAFrida", "No log file yet");
            return;
        }
        String trimmed = rawPath.trim();
        if (trimmed.startsWith("(")) {
            ZaFridaNotifier.warn(project, "ZAFrida", String.format("Log file not available: %s", trimmed));
            return;
        }
        ZaFridaNotifier.warn(project, "ZAFrida", String.format("Log file not found: %s", trimmed));
    }

    /**
     * 查找下一个匹配项并定位光标。
     * @param forward true 表示向前查找
     */
    private void findNext(boolean forward) {
        String query = searchField.getText();
        if (ZaStrUtil.isBlank(query)) {
            return;
        }
        Editor editor = getEditor();
        if (editor == null) {
            return;
        }
        Document document = editor.getDocument();
        String text = document.getText();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            lastMatchStart = -1;
        }
        if (text.isEmpty()) {
            return;
        }
        // 计算起始位置，优先从上一次匹配处继续
        int caretOffset = editor.getCaretModel().getOffset();
        int startIndex = resolveStartIndex(forward, caretOffset, text.length());
        int matchStart = forward
                ? findForward(text, query, startIndex)
                : findBackward(text, query, startIndex);
        if (matchStart == -1) {
            return;
        }
        // 选中并滚动到匹配位置
        int matchEnd = matchStart + query.length();
        lastMatchStart = matchStart;
        editor.getSelectionModel().setSelection(matchStart, matchEnd);
        editor.getCaretModel().moveToOffset(matchEnd);
        editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
    }

    /**
     * 解析查找起始索引。
     * @param forward 是否向前
     * @param caretOffset 当前光标位置
     * @param textLength 文本长度
     * @return 起始索引
     */
    private int resolveStartIndex(boolean forward, int caretOffset, int textLength) {
        if (lastMatchStart != -1) {
            return forward ? lastMatchStart + 1 : lastMatchStart - 1;
        }
        if (forward) {
            return Math.min(caretOffset, textLength);
        }
        return Math.min(Math.max(caretOffset - 1, 0), Math.max(textLength - 1, 0));
    }

    /**
     * 向前查找匹配。
     * @param text 全文
     * @param query 查询关键字
     * @param startIndex 起始索引
     * @return 匹配起始位置或 -1
     */
    private int findForward(String text, String query, int startIndex) {
        int matchStart = text.indexOf(query, Math.max(startIndex, 0));
        if (matchStart == -1 && startIndex > 0) {
            matchStart = text.indexOf(query);
        }
        return matchStart;
    }

    /**
     * 向后查找匹配。
     * @param text 全文
     * @param query 查询关键字
     * @param startIndex 起始索引
     * @return 匹配起始位置或 -1
     */
    private int findBackward(String text, String query, int startIndex) {
        int safeIndex = Math.min(Math.max(startIndex, 0), Math.max(text.length() - 1, 0));
        int matchStart = text.lastIndexOf(query, safeIndex);
        if (matchStart == -1 && safeIndex < text.length() - 1) {
            matchStart = text.lastIndexOf(query);
        }
        return matchStart;
    }

    /**
     * 获取底层编辑器实例。
     * @return Editor 或 null
     */
    private Editor getEditor() {
        if (consoleView instanceof ConsoleViewImpl consoleViewImpl) {
            return consoleViewImpl.getEditor();
        }
        return null;
    }

    /**
     * 释放资源。
     */
    @Override
    public void dispose() {
        Disposer.dispose(consoleView);
    }
}
