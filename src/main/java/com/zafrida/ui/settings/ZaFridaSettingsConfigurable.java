package com.zafrida.ui.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.zafrida.ui.api.ZaFridaLocalHttpApiService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * [UI入口] IDE "Settings/Preferences" 菜单集成。
 * <p>
 * <strong>功能：</strong>
 * 将 {@link ZaFridaSettingsComponent} (UI 面板) 注册到 IntelliJ 的设置树中。
 * 负责在 UI 和 {@link ZaFridaSettingsService} (持久化状态) 之间同步数据（Apply/Reset 逻辑）。
 */
public final class ZaFridaSettingsConfigurable implements SearchableConfigurable {

    /** 全局设置服务 */
    private final ZaFridaSettingsService settingsService;
    /** UI 组件实例 */
    private @Nullable ZaFridaSettingsComponent component;

    /**
     * 构造函数。
     */
    public ZaFridaSettingsConfigurable() {
        this.settingsService = ApplicationManager.getApplication().getService(ZaFridaSettingsService.class);
    }

    /**
     * 配置项 ID。
     * @return 配置 ID
     */
    @Override
    public @NotNull String getId() {
        return "com.zafrida.ui.settings";
    }

    /**
     * 配置项显示名称。
     * @return 显示名称
     */
    @Override
    public String getDisplayName() {
        return "ZAFrida";
    }

    /**
     * 创建设置面板组件。
     * @return UI 组件
     */
    @Override
    public @Nullable JComponent createComponent() {
        ZaFridaSettingsComponent c = new ZaFridaSettingsComponent();
        c.reset(settingsService.getState());
        c.bindSkillsApiActions(this::handleManualStartSkillsApi, this::handleManualStopSkillsApi);
        this.component = c;
        refreshSkillsApiStatusAsync();
        return c.getPanel();
    }

    /**
     * 判断配置是否被修改。
     * @return true 表示有修改
     */
    @Override
    public boolean isModified() {
        if (component == null) {
            return false;
        }

        ZaFridaSettingsState copy = createSnapshotFromCurrentState();
        component.applyTo(copy);

        ZaFridaSettingsState current = settingsService.getState();
        if (!safeEq(copy.fridaExecutable, current.fridaExecutable)) {
            return true;
        }
        if (!safeEq(copy.fridaPsExecutable, current.fridaPsExecutable)) {
            return true;
        }
        if (!safeEq(copy.fridaLsDevicesExecutable, current.fridaLsDevicesExecutable)) {
            return true;
        }
        if (!safeEq(copy.fridaVersion, current.fridaVersion)) {
            return true;
        }
        if (!safeEq(copy.vscodeExecutable, current.vscodeExecutable)) {
            return true;
        }
        if (!safeEq(copy.editor010Executable, current.editor010Executable)) {
            return true;
        }
        if (!safeEq(copy.logsDirName, current.logsDirName)) {
            return true;
        }
        if (!safeEq(copy.defaultRemoteHost, current.defaultRemoteHost)) {
            return true;
        }
        if (copy.defaultRemotePort != current.defaultRemotePort) {
            return true;
        }
        if (copy.useIdeScriptChooser != current.useIdeScriptChooser) {
            return true;
        }
        if (!safeEq(copy.templatesRootMode, current.templatesRootMode)) {
            return true;
        }
        if (copy.enableSkillsHttpApi != current.enableSkillsHttpApi) {
            return true;
        }
        if (copy.skillsApiPort != current.skillsApiPort) {
            return true;
        }

        if (copy.remoteHosts == null && current.remoteHosts != null && !current.remoteHosts.isEmpty()) {
            return true;
        }
        if (copy.remoteHosts != null && current.remoteHosts == null && !copy.remoteHosts.isEmpty()) {
            return true;
        }
        if (copy.remoteHosts != null && current.remoteHosts != null && !copy.remoteHosts.equals(current.remoteHosts)) {
            return true;
        }

        return false;
    }

    /**
     * 应用设置改动。
     */
    @Override
    public void apply() {
        if (component == null) {
            return;
        }
        ZaFridaSettingsState current = settingsService.getState();
        boolean oldEnabled = current.enableSkillsHttpApi;
        int oldPort = current.skillsApiPort;

        ZaFridaSettingsState newState = createSnapshotFromCurrentState();
        component.applyTo(newState);
        settingsService.loadState(newState);

        applySkillsApiChangeAsync(oldEnabled, oldPort, newState.enableSkillsHttpApi, newState.skillsApiPort);
    }

    /**
     * 重置 UI 为当前持久化状态。
     */
    @Override
    public void reset() {
        if (component != null) {
            component.reset(settingsService.getState());
            refreshSkillsApiStatusAsync();
        }
    }

    /**
     * 释放 UI 资源。
     */
    @Override
    public void disposeUIResources() {
        component = null;
    }

    /**
     * 手动启动 Skills API（按钮/勾选触发）。
     */
    private void handleManualStartSkillsApi() {
        ZaFridaSettingsComponent currentComponent = component;
        if (currentComponent == null) {
            return;
        }
        currentComponent.setSkillsApiStatus("Starting...", false);

        ZaFridaSettingsState state = settingsService.getState();
        state.enableSkillsHttpApi = currentComponent.isSkillsApiEnabled();
        state.skillsApiPort = currentComponent.getSkillsApiPort();

        if (!state.enableSkillsHttpApi) {
            currentComponent.setSkillsApiStatus("Disabled", false);
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ApiControlResult result = startSkillsApiForOpenProjects(false);
            ApplicationManager.getApplication().invokeLater(() -> {
                ZaFridaSettingsComponent c = component;
                if (c == null) {
                    return;
                }
                c.setSkillsApiStatus(result.statusText, result.running);
                if (!result.errorMessage.isEmpty()) {
                    c.showSkillsApiTip(result.errorMessage, true);
                }
            });
        });
    }

    /**
     * 手动停止 Skills API（按钮/取消勾选触发）。
     */
    private void handleManualStopSkillsApi() {
        ZaFridaSettingsComponent currentComponent = component;
        if (currentComponent == null) {
            return;
        }
        currentComponent.setSkillsApiStatus("Stopping...", true);

        ZaFridaSettingsState state = settingsService.getState();
        state.enableSkillsHttpApi = currentComponent.isSkillsApiEnabled();
        state.skillsApiPort = currentComponent.getSkillsApiPort();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            stopSkillsApiForOpenProjects();
            ApplicationManager.getApplication().invokeLater(() -> {
                ZaFridaSettingsComponent c = component;
                if (c == null) {
                    return;
                }
                if (state.enableSkillsHttpApi) {
                    c.setSkillsApiStatus("Stopped", false);
                } else {
                    c.setSkillsApiStatus("Disabled", false);
                }
            });
        });
    }

    /**
     * 按开关和端口变更处理 Skills API 生命周期。
     */
    private void applySkillsApiChangeAsync(boolean oldEnabled, int oldPort, boolean newEnabled, int newPort) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (!newEnabled) {
                stopSkillsApiForOpenProjects();
                ApplicationManager.getApplication().invokeLater(() -> {
                    ZaFridaSettingsComponent c = component;
                    if (c != null) {
                        c.setSkillsApiStatus("Disabled", false);
                    }
                });
                return;
            }

            boolean shouldRestart = !oldEnabled || oldPort != newPort;
            ApiControlResult result = startSkillsApiForOpenProjects(shouldRestart);
            ApplicationManager.getApplication().invokeLater(() -> {
                ZaFridaSettingsComponent c = component;
                if (c == null) {
                    return;
                }
                c.setSkillsApiStatus(result.statusText, result.running);
                if (!result.errorMessage.isEmpty()) {
                    c.showSkillsApiTip(result.errorMessage, true);
                }
            });
        });
    }

    /**
     * 刷新设置页中的 Skills API 状态。
     */
    private void refreshSkillsApiStatusAsync() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ApiControlResult result = querySkillsApiStatus();
            ApplicationManager.getApplication().invokeLater(() -> {
                ZaFridaSettingsComponent c = component;
                if (c != null) {
                    c.setSkillsApiStatus(result.statusText, result.running);
                }
            });
        });
    }

    private @NotNull ApiControlResult querySkillsApiStatus() {
        List<Project> openProjects = listOpenProjects();
        if (openProjects.isEmpty()) {
            if (settingsService.getState().enableSkillsHttpApi) {
                return new ApiControlResult(false, "No open project", "");
            }
            return new ApiControlResult(false, "Disabled", "");
        }

        int runningCount = 0;
        int samplePort = -1;
        for (Project project : openProjects) {
            ZaFridaLocalHttpApiService service = project.getService(ZaFridaLocalHttpApiService.class);
            if (service.isServerRunning()) {
                runningCount++;
                if (samplePort <= 0) {
                    samplePort = service.getBoundPort();
                }
            }
        }

        if (runningCount <= 0) {
            if (settingsService.getState().enableSkillsHttpApi) {
                return new ApiControlResult(false, "Stopped", "");
            }
            return new ApiControlResult(false, "Disabled", "");
        }
        return new ApiControlResult(true, String.format("Running (%s, port=%s)", runningCount, samplePort), "");
    }

    private @NotNull ApiControlResult startSkillsApiForOpenProjects(boolean restart) {
        List<Project> openProjects = listOpenProjects();
        if (openProjects.isEmpty()) {
            return new ApiControlResult(false, "No open project", "");
        }

        int runningCount = 0;
        int samplePort = -1;
        List<String> errors = new ArrayList<>();
        for (Project project : openProjects) {
            ZaFridaLocalHttpApiService service = project.getService(ZaFridaLocalHttpApiService.class);
            boolean ok;
            if (restart) {
                ok = service.restartServerNow();
            } else {
                ok = service.startServerNow();
            }
            if (ok && service.isServerRunning()) {
                runningCount++;
                if (samplePort <= 0) {
                    samplePort = service.getBoundPort();
                }
                continue;
            }

            String error = service.getLastStartError();
            if (error == null || error.isEmpty()) {
                error = "Unknown start error";
            }
            errors.add(String.format("%s: %s", project.getName(), error));
        }

        if (runningCount <= 0) {
            String firstError;
            if (errors.isEmpty()) {
                firstError = "Skills HTTP API start failed";
            } else {
                firstError = errors.get(0);
            }
            return new ApiControlResult(false, "Start failed", firstError);
        }

        if (errors.isEmpty()) {
            return new ApiControlResult(true, String.format("Running (%s, port=%s)", runningCount, samplePort), "");
        }
        return new ApiControlResult(
                true,
                String.format("Running (%s, port=%s, partial)", runningCount, samplePort),
                errors.get(0)
        );
    }

    private void stopSkillsApiForOpenProjects() {
        List<Project> openProjects = listOpenProjects();
        for (Project project : openProjects) {
            ZaFridaLocalHttpApiService service = project.getService(ZaFridaLocalHttpApiService.class);
            service.stopServerNow();
        }
    }

    private @NotNull List<Project> listOpenProjects() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        List<Project> list = new ArrayList<>();
        for (Project project : projects) {
            if (project != null && !project.isDisposed()) {
                list.add(project);
            }
        }
        return list;
    }

    private @NotNull ZaFridaSettingsState createSnapshotFromCurrentState() {
        ZaFridaSettingsState current = settingsService.getState();
        ZaFridaSettingsState copy = new ZaFridaSettingsState();
        copy.fridaExecutable = current.fridaExecutable;
        copy.fridaPsExecutable = current.fridaPsExecutable;
        copy.fridaLsDevicesExecutable = current.fridaLsDevicesExecutable;
        copy.fridaVersion = current.fridaVersion;
        copy.vscodeExecutable = current.vscodeExecutable;
        copy.editor010Executable = current.editor010Executable;
        copy.logsDirName = current.logsDirName;
        copy.defaultRemoteHost = current.defaultRemoteHost;
        copy.defaultRemotePort = current.defaultRemotePort;
        copy.useIdeScriptChooser = current.useIdeScriptChooser;
        copy.templatesRootMode = current.templatesRootMode;
        copy.enableSkillsHttpApi = current.enableSkillsHttpApi;
        copy.skillsApiPort = current.skillsApiPort;
        copy.remoteHosts = settingsService.getRemoteHosts();
        return copy;
    }

    /**
     * 安全比较字符串相等性。
     * @param a 字符串 A
     * @param b 字符串 B
     * @return true 表示相等
     */
    private static boolean safeEq(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    /**
     * Skills API 批量控制结果。
     */
    private static final class ApiControlResult {
        private final boolean running;
        private final @NotNull String statusText;
        private final @NotNull String errorMessage;

        private ApiControlResult(boolean running, @NotNull String statusText, @NotNull String errorMessage) {
            this.running = running;
            this.statusText = statusText;
            this.errorMessage = errorMessage;
        }
    }
}

