package com.zafrida.ui.api;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zafrida.ui.adb.AdbService;
import com.zafrida.ui.diagnostics.ZaFridaDiagnosticItem;
import com.zafrida.ui.diagnostics.ZaFridaDiagnosticStatus;
import com.zafrida.ui.diagnostics.ZaFridaDiagnosticsListener;
import com.zafrida.ui.diagnostics.ZaFridaDiagnosticsService;
import com.zafrida.ui.frida.FridaCliService;
import com.zafrida.ui.frida.FridaConnectionMode;
import com.zafrida.ui.frida.FridaDevice;
import com.zafrida.ui.frida.FridaProcess;
import com.zafrida.ui.frida.FridaProcessScope;
import com.zafrida.ui.fridaproject.ZaFridaFridaProject;
import com.zafrida.ui.fridaproject.ZaFridaPlatform;
import com.zafrida.ui.fridaproject.ZaFridaProjectConfig;
import com.zafrida.ui.fridaproject.ZaFridaProjectManager;
import com.zafrida.ui.settings.ZaFridaSettingsService;
import com.zafrida.ui.settings.ZaFridaSettingsState;
import com.zafrida.ui.session.ZaFridaSessionService;
import com.zafrida.ui.session.ZaFridaSessionType;
import com.zafrida.ui.ui.ZaFridaConsolePanel;
import com.zafrida.ui.ui.ZaFridaRunPanel;
import com.zafrida.ui.util.ZaFridaNetUtil;
import com.zafrida.ui.util.ZaStrUtil;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [本地 API] 对外暴露 ZAFrida 可控能力，供 skills/CLI 调用。
 * <p>
 * 说明：
 * 1. 仅绑定到 127.0.0.1，避免暴露到公网。
 * 2. 25 个单独接口 + 1 个汇总接口（不包含日志内容）。
 * 3. 任何 UI 读取/写入都通过 EDT 调度，避免线程模型破坏。
 */
@Service(Service.Level.PROJECT)
public final class ZaFridaLocalHttpApiService implements Disposable {

    private static final Logger LOG = Logger.getInstance(ZaFridaLocalHttpApiService.class);

    private static final String BIND_HOST = "127.0.0.1";
    private static final long DEFAULT_TIMEOUT_SECONDS = 10L;

    private static final String API_BASE = "/zafrida/api/v1";
    private static final String API_HEALTH = API_BASE + "/health";
    private static final String API_STATE = API_BASE + "/state";

    private static final String API_PROJECT_CURRENT = API_BASE + "/project/current";
    private static final String API_PROJECT_SELECT = API_BASE + "/project/select";
    private static final String API_PROJECT_CREATE = API_BASE + "/project/create";

    private static final String API_DEVICE_SELECT = API_BASE + "/device/select";
    private static final String API_CONNECTION_MODE_SET = API_BASE + "/connection-mode/set";
    private static final String API_TARGET_SET = API_BASE + "/target/set";
    private static final String API_RUN_SCRIPT_SET = API_BASE + "/run-script/set";
    private static final String API_ATTACH_SCRIPT_SET = API_BASE + "/attach-script/set";
    private static final String API_EXTRA_ARGS_SET = API_BASE + "/extra-args/set";

    private static final String API_RUN = API_BASE + "/run";
    private static final String API_STOP = API_BASE + "/stop";
    private static final String API_ATTACH = API_BASE + "/attach";
    private static final String API_STOP_ATTACH = API_BASE + "/stop-attach";

    private static final String API_RUN_LOG_PATH = API_BASE + "/run-log/path";
    private static final String API_RUN_LOG_CONTENT = API_BASE + "/run-log/content";
    private static final String API_ATTACH_LOG_PATH = API_BASE + "/attach-log/path";
    private static final String API_ATTACH_LOG_CONTENT = API_BASE + "/attach-log/content";
    private static final String API_RUN_LOG_LINES = API_BASE + "/run-log/lines";
    private static final String API_ATTACH_LOG_LINES = API_BASE + "/attach-log/lines";

    private static final String API_DEVICES = API_BASE + "/devices";
    private static final String API_PROCESSES = API_BASE + "/processes";
    private static final String API_ADB_FORCE_STOP = API_BASE + "/adb/force-stop";
    private static final String API_ADB_OPEN_APP = API_BASE + "/adb/open-app";
    private static final String API_CONSOLE_CLEAR = API_BASE + "/console/clear";
    private static final String API_DIAGNOSTICS = API_BASE + "/diagnostics";

    /** 单次按行读取的上限行数 */
    private static final int MAX_LINES_PER_REQUEST = 2000;
    /** 诊断超时（诊断含多项检查，给予充足时间） */
    private static final long DIAGNOSTICS_TIMEOUT_SECONDS = 60L;

    private static final AtomicInteger SERVER_THREAD_ID = new AtomicInteger(1);

    private final @NotNull Project project;
    private final @NotNull ZaFridaProjectManager projectManager;
    private final @NotNull ZaFridaSessionService sessionService;
    private final @NotNull ZaFridaSettingsService settingsService;
    private final @NotNull FridaCliService fridaCliService;
    private final @NotNull AdbService adbService;
    private final @NotNull ZaFridaDiagnosticsService diagnosticsService;

    private final AtomicReference<ZaFridaRunPanel> runPanelRef = new AtomicReference<>();
    private final Object serverLock = new Object();

    private volatile @Nullable HttpServer server;
    private volatile @Nullable ExecutorService serverExecutor;
    private volatile int boundPort = -1;
    private volatile @Nullable String lastStartError;

    public ZaFridaLocalHttpApiService(@NotNull Project project) {
        this.project = project;
        this.projectManager = project.getService(ZaFridaProjectManager.class);
        this.sessionService = project.getService(ZaFridaSessionService.class);
        this.settingsService = ApplicationManager.getApplication().getService(ZaFridaSettingsService.class);
        this.fridaCliService = ApplicationManager.getApplication().getService(FridaCliService.class);
        this.adbService = ApplicationManager.getApplication().getService(AdbService.class);
        this.diagnosticsService = ApplicationManager.getApplication().getService(ZaFridaDiagnosticsService.class);
        maybeStartBySettingsAsync();
    }

    /**
     * 绑定当前可用的 RunPanel。
     * @param runPanel 面板实例
     */
    public void bindRunPanel(@NotNull ZaFridaRunPanel runPanel) {
        runPanelRef.set(runPanel);
    }

    /**
     * 解绑 RunPanel（dispose 时调用）。
     * @param runPanel 面板实例
     */
    public void unbindRunPanel(@NotNull ZaFridaRunPanel runPanel) {
        runPanelRef.compareAndSet(runPanel, null);
    }

    /**
     * 获取实际监听端口（调试/汇总接口使用）。
     * @return 端口号；未启动时为 -1
     */
    public int getBoundPort() {
        return boundPort;
    }

    /**
     * 服务是否已在监听。
     * @return true 表示运行中
     */
    public boolean isServerRunning() {
        return server != null;
    }

    /**
     * 获取最近一次启动失败原因（若无则返回 null）。
     * @return 失败原因
     */
    public @Nullable String getLastStartError() {
        return lastStartError;
    }

    /**
     * 立即启动服务（手动控制入口）。
     */
    public boolean startServerNow() {
        startServerSafely(false);
        return server != null;
    }

    /**
     * 立即停止服务（手动控制入口）。
     */
    public void stopServerNow() {
        stopServerSafely();
    }

    /**
     * 按当前设置重启服务。
     */
    public boolean restartServerNow() {
        stopServerSafely();
        startServerSafely(false);
        return server != null;
    }

    private void maybeStartBySettingsAsync() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> startServerSafely(true));
    }

    private void startServerSafely(boolean requireEnabledInSettings) {
        synchronized (serverLock) {
            if (project.isDisposed()) {
                return;
            }
            if (server != null) {
                return;
            }

            if (requireEnabledInSettings && !isEnabledInSettings()) {
                boundPort = -1;
                lastStartError = null;
                return;
            }

            int configuredPort = resolveConfiguredPort();
            InetSocketAddress address = new InetSocketAddress(BIND_HOST, configuredPort);

            HttpServer createdServer;
            try {
                createdServer = HttpServer.create(address, 0);
            } catch (IOException e) {
                lastStartError = String.format("Bind failed on %s:%s (%s)", BIND_HOST, configuredPort, e.getMessage());
                LOG.warn(String.format("[ZAFrida API] Failed to bind %s:%s", BIND_HOST, configuredPort), e);
                return;
            }

            registerContexts(createdServer);

            ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
                @Override
                public Thread newThread(@NotNull Runnable runnable) {
                    Thread thread = new Thread(runnable,
                            String.format("ZAFrida-LocalApi-%s", SERVER_THREAD_ID.getAndIncrement()));
                    thread.setDaemon(true);
                    return thread;
                }
            });
            createdServer.setExecutor(executor);

            createdServer.start();
            server = createdServer;
            serverExecutor = executor;
            boundPort = createdServer.getAddress().getPort();
            lastStartError = null;

            LOG.info(String.format(
                    "[ZAFrida API] Started on http://%s:%s%s",
                    BIND_HOST,
                    boundPort,
                    API_BASE
            ));
        }
    }

    private void stopServerSafely() {
        synchronized (serverLock) {
            HttpServer current = server;
            if (current != null) {
                current.stop(0);
                server = null;
            }
            ExecutorService executor = serverExecutor;
            if (executor != null) {
                executor.shutdownNow();
                serverExecutor = null;
            }
            boundPort = -1;
            lastStartError = null;
        }
    }

    private boolean isEnabledInSettings() {
        ZaFridaSettingsState state = settingsService.getState();
        return state.enableSkillsHttpApi;
    }

    private int resolveConfiguredPort() {
        ZaFridaSettingsState state = settingsService.getState();
        int configuredPort = state.skillsApiPort;
        if (configuredPort > 0 && configuredPort <= 65535) {
            return configuredPort;
        }
        return ZaFridaSettingsState.DEFAULT_SKILLS_API_PORT;
    }

    private void registerContexts(@NotNull HttpServer createdServer) {
        createdServer.createContext(API_HEALTH, exchange -> dispatch(exchange, "GET", this::handleHealth));
        createdServer.createContext(API_STATE, exchange -> dispatch(exchange, "GET", this::handleState));

        createdServer.createContext(API_PROJECT_CURRENT, exchange -> dispatch(exchange, "GET", this::handleProjectCurrent));
        createdServer.createContext(API_PROJECT_SELECT, exchange -> dispatch(exchange, "POST", this::handleProjectSelect));
        createdServer.createContext(API_PROJECT_CREATE, exchange -> dispatch(exchange, "POST", this::handleProjectCreate));

        createdServer.createContext(API_DEVICE_SELECT, exchange -> dispatch(exchange, "POST", this::handleDeviceSelect));
        createdServer.createContext(API_CONNECTION_MODE_SET, exchange -> dispatch(exchange, "POST", this::handleConnectionModeSet));
        createdServer.createContext(API_TARGET_SET, exchange -> dispatch(exchange, "POST", this::handleTargetSet));
        createdServer.createContext(API_RUN_SCRIPT_SET, exchange -> dispatch(exchange, "POST", this::handleRunScriptSet));
        createdServer.createContext(API_ATTACH_SCRIPT_SET, exchange -> dispatch(exchange, "POST", this::handleAttachScriptSet));
        createdServer.createContext(API_EXTRA_ARGS_SET, exchange -> dispatch(exchange, "POST", this::handleExtraArgsSet));

        createdServer.createContext(API_RUN, exchange -> dispatch(exchange, "POST", this::handleRun));
        createdServer.createContext(API_STOP, exchange -> dispatch(exchange, "POST", this::handleStop));
        createdServer.createContext(API_ATTACH, exchange -> dispatch(exchange, "POST", this::handleAttach));
        createdServer.createContext(API_STOP_ATTACH, exchange -> dispatch(exchange, "POST", this::handleStopAttach));

        createdServer.createContext(API_RUN_LOG_PATH, exchange -> dispatch(exchange, "GET", this::handleRunLogPath));
        createdServer.createContext(API_RUN_LOG_CONTENT, exchange -> dispatch(exchange, "GET", this::handleRunLogContent));
        createdServer.createContext(API_ATTACH_LOG_PATH, exchange -> dispatch(exchange, "GET", this::handleAttachLogPath));
        createdServer.createContext(API_ATTACH_LOG_CONTENT, exchange -> dispatch(exchange, "GET", this::handleAttachLogContent));
        createdServer.createContext(API_RUN_LOG_LINES, exchange -> dispatch(exchange, "GET", this::handleRunLogLines));
        createdServer.createContext(API_ATTACH_LOG_LINES, exchange -> dispatch(exchange, "GET", this::handleAttachLogLines));

        createdServer.createContext(API_DEVICES, exchange -> dispatch(exchange, "GET", this::handleDevices));
        createdServer.createContext(API_PROCESSES, exchange -> dispatch(exchange, "GET", this::handleProcesses));
        createdServer.createContext(API_ADB_FORCE_STOP, exchange -> dispatch(exchange, "POST", this::handleAdbForceStop));
        createdServer.createContext(API_ADB_OPEN_APP, exchange -> dispatch(exchange, "POST", this::handleAdbOpenApp));
        createdServer.createContext(API_CONSOLE_CLEAR, exchange -> dispatch(exchange, "POST", this::handleConsoleClear));
        createdServer.createContext(API_DIAGNOSTICS, exchange -> dispatch(exchange, "GET", this::handleDiagnostics));
    }

    private void dispatch(@NotNull HttpExchange exchange,
                          @NotNull String requiredMethod,
                          @NotNull ApiHandler handler) throws IOException {
        try {
            String requestMethod = exchange.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(requestMethod)) {
                writeNoContent(exchange);
                return;
            }

            if (!requiredMethod.equalsIgnoreCase(requestMethod)) {
                throw new ApiException(405, String.format("Method not allowed: %s", requestMethod));
            }

            RequestContext request = RequestContext.from(exchange);
            Map<String, Object> data = handler.handle(request);
            writeSuccess(exchange, data);
        } catch (ApiException e) {
            writeError(exchange, e.statusCode, e.getMessage());
        } catch (Throwable t) {
            LOG.warn("[ZAFrida API] Request handling failed", t);
            String message = t.getMessage();
            if (ZaStrUtil.isBlank(message)) {
                message = t.getClass().getSimpleName();
            }
            writeError(exchange, 500, String.format("Internal error: %s", message));
        } finally {
            exchange.close();
        }
    }

    private @NotNull Map<String, Object> handleHealth(@NotNull RequestContext request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("host", BIND_HOST);
        data.put("port", boundPort);
        data.put("basePath", API_BASE);
        data.put("runPanelReady", runPanelRef.get() != null);
        return data;
    }

    private @NotNull Map<String, Object> handleState(@NotNull RequestContext request) throws Exception {
        return buildStateSummary();
    }

    private @NotNull Map<String, Object> handleProjectCurrent(@NotNull RequestContext request) throws Exception {
        ZaFridaFridaProject active = projectManager.getActiveProject();
        ZaFridaProjectConfig cfg = loadProjectConfigBlocking(active);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeProject", projectToMap(active));
        data.put("projects", projectListToMap(projectManager.listProjects()));
        data.put("config", configToMap(cfg));
        return data;
    }

    private @NotNull Map<String, Object> handleProjectSelect(@NotNull RequestContext request) throws Exception {
        String projectName = request.require("name");
        ZaFridaFridaProject targetProject = findProjectByName(projectName);
        if (targetProject == null) {
            throw new ApiException(404, String.format("Project not found: %s", projectName));
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        projectManager.setActiveProjectAsync(targetProject, () -> future.complete(null));
        awaitVoidFuture(future, "Switch project timeout");

        ZaFridaRunPanel panel = runPanelRef.get();
        if (panel != null) {
            runOnUiThreadAndWait(panel::refreshActiveProjectUiForApi);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeProject", projectToMap(targetProject));
        return data;
    }

    private @NotNull Map<String, Object> handleProjectCreate(@NotNull RequestContext request) throws Exception {
        String name = request.require("name");
        String platformRaw = request.getOrDefault("platform", "android");
        ZaFridaPlatform platform = parsePlatform(platformRaw);

        CompletableFuture<ZaFridaFridaProject> future = new CompletableFuture<>();
        projectManager.createAndActivateAsync(name, platform, future::complete, future::completeExceptionally);
        ZaFridaFridaProject created = waitFuture(future, "Create project timeout");

        ZaFridaRunPanel panel = runPanelRef.get();
        if (panel != null) {
            runOnUiThreadAndWait(panel::refreshActiveProjectUiForApi);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeProject", projectToMap(created));
        return data;
    }

    private @NotNull Map<String, Object> handleDeviceSelect(@NotNull RequestContext request) throws Exception {
        String id = request.get("id");
        String host = request.get("host");
        if (ZaStrUtil.isBlank(id) && ZaStrUtil.isBlank(host)) {
            throw new ApiException(400, "Missing parameter: id or host");
        }

        ZaFridaRunPanel panel = requireRunPanel();
        boolean selected = callOnUiThreadAndWait(() -> {
            boolean matched = false;
            if (ZaStrUtil.isNotBlank(id)) {
                matched = panel.selectDeviceByIdForApi(id);
            }
            if (!matched && ZaStrUtil.isNotBlank(host)) {
                matched = panel.selectDeviceByHostForApi(host);
            }
            return matched;
        });
        if (!selected) {
            throw new ApiException(404, "Device not found in current list");
        }

        FridaDevice selectedDevice = callOnUiThreadAndWait(panel::getSelectedDeviceForApi);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("device", deviceToMap(selectedDevice));
        return data;
    }

    private @NotNull Map<String, Object> handleConnectionModeSet(@NotNull RequestContext request) throws Exception {
        String modeText = request.require("mode");
        FridaConnectionMode mode = parseConnectionMode(modeText);

        ZaFridaFridaProject activeProject = requireActiveProject();
        String host = request.get("host");
        Integer port = parseOptionalPort(request.get("port"));

        CompletableFuture<Void> future = new CompletableFuture<>();
        projectManager.updateProjectConfigAsync(activeProject, cfg -> {
            cfg.connectionMode = mode;
            if (mode == FridaConnectionMode.USB) {
                cfg.lastDeviceHost = null;
                return;
            }

            if (ZaStrUtil.isNotBlank(host)) {
                cfg.remoteHost = host.trim();
            }
            if (port != null) {
                cfg.remotePort = port;
            }

            cfg.remoteHost = ZaFridaNetUtil.defaultHost(cfg.remoteHost);
            cfg.remotePort = ZaFridaNetUtil.defaultPort(cfg.remotePort);
            cfg.lastDeviceHost = String.format("%s:%s", cfg.remoteHost, cfg.remotePort);
            cfg.lastDeviceId = null;
        }, () -> future.complete(null));
        awaitVoidFuture(future, "Update connection mode timeout");

        ZaFridaRunPanel panel = runPanelRef.get();
        if (panel != null) {
            runOnUiThreadAndWait(panel::refreshActiveProjectUiForApi);
        }

        ZaFridaProjectConfig cfg = loadProjectConfigBlocking(activeProject);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", mode.name().toLowerCase(Locale.ROOT));
        data.put("config", configToMap(cfg));
        return data;
    }

    private @NotNull Map<String, Object> handleTargetSet(@NotNull RequestContext request) throws Exception {
        String target = request.getOrDefault("target", "");
        ZaFridaRunPanel panel = requireRunPanel();
        runOnUiThreadAndWait(() -> panel.setTargetTextForApi(target));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("target", target.trim());
        return data;
    }

    private @NotNull Map<String, Object> handleRunScriptSet(@NotNull RequestContext request) throws Exception {
        String path = request.require("path");
        ZaFridaRunPanel panel = requireRunPanel();
        boolean ok = callOnUiThreadAndWait(() -> panel.setRunScriptPathForApi(path));
        if (!ok) {
            throw new ApiException(400, String.format("Invalid run script: %s", path));
        }

        String runScript = callOnUiThreadAndWait(panel::getRunScriptPathForApi);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runScript", runScript);
        return data;
    }

    private @NotNull Map<String, Object> handleAttachScriptSet(@NotNull RequestContext request) throws Exception {
        String path = request.require("path");
        ZaFridaRunPanel panel = requireRunPanel();
        boolean ok = callOnUiThreadAndWait(() -> panel.setAttachScriptPathForApi(path));
        if (!ok) {
            throw new ApiException(400, String.format("Invalid attach script: %s", path));
        }

        String attachScript = callOnUiThreadAndWait(panel::getAttachScriptPathForApi);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attachScript", attachScript);
        return data;
    }

    private @NotNull Map<String, Object> handleExtraArgsSet(@NotNull RequestContext request) throws Exception {
        String value = request.get("value");
        if (value == null) {
            value = request.getOrDefault("args", "");
        }

        ZaFridaRunPanel panel = requireRunPanel();
        String finalValue = value;
        runOnUiThreadAndWait(() -> panel.setExtraArgsForApi(finalValue));

        String extra = callOnUiThreadAndWait(panel::getExtraArgsForApi);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("extraArgs", extra);
        return data;
    }

    private @NotNull Map<String, Object> handleRun(@NotNull RequestContext request) throws Exception {
        ZaFridaRunPanel panel = requireRunPanel();
        runOnUiThreadAndWait(panel::triggerRun);
        return actionResult("run");
    }

    private @NotNull Map<String, Object> handleStop(@NotNull RequestContext request) throws Exception {
        ZaFridaRunPanel panel = requireRunPanel();
        runOnUiThreadAndWait(panel::triggerStop);
        return actionResult("stop");
    }

    private @NotNull Map<String, Object> handleAttach(@NotNull RequestContext request) throws Exception {
        ZaFridaRunPanel panel = requireRunPanel();
        runOnUiThreadAndWait(panel::triggerAttach);
        return actionResult("attach");
    }

    private @NotNull Map<String, Object> handleStopAttach(@NotNull RequestContext request) throws Exception {
        ZaFridaRunPanel panel = requireRunPanel();
        runOnUiThreadAndWait(panel::triggerStopAttach);
        return actionResult("stop-attach");
    }

    private @NotNull Map<String, Object> handleRunLogPath(@NotNull RequestContext request) throws Exception {
        LogState state = captureLogState(true);
        long fileSize = computeFileSize(state.path, state.existsOnDisk);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", state.path);
        data.put("exists", state.existsOnDisk);
        data.put("fileSize", fileSize);
        data.put("sizeHuman", formatFileSize(fileSize));
        return data;
    }

    private @NotNull Map<String, Object> handleRunLogContent(@NotNull RequestContext request) throws Exception {
        return readLogContent(true, request);
    }

    private @NotNull Map<String, Object> handleAttachLogPath(@NotNull RequestContext request) throws Exception {
        LogState state = captureLogState(false);
        long fileSize = computeFileSize(state.path, state.existsOnDisk);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", state.path);
        data.put("exists", state.existsOnDisk);
        data.put("fileSize", fileSize);
        data.put("sizeHuman", formatFileSize(fileSize));
        return data;
    }

    private @NotNull Map<String, Object> handleAttachLogContent(@NotNull RequestContext request) throws Exception {
        return readLogContent(false, request);
    }

    private @NotNull Map<String, Object> handleRunLogLines(@NotNull RequestContext request) throws Exception {
        return readLogLines(true, request);
    }

    private @NotNull Map<String, Object> handleAttachLogLines(@NotNull RequestContext request) throws Exception {
        return readLogLines(false, request);
    }

    private @NotNull Map<String, Object> readLogContent(boolean runLog, @NotNull RequestContext request) throws Exception {
        LogState state = captureLogState(runLog);
        String pathFromParam = request.get("path");
        String normalizedPath = pathFromParam;
        if (ZaStrUtil.isBlank(normalizedPath)) {
            normalizedPath = state.path;
        }
        if (normalizedPath == null) {
            normalizedPath = "";
        }
        normalizedPath = normalizedPath.trim();

        int maxBytes = parseNonNegativeInt(request.get("maxBytes"), 0, "maxBytes");

        if (normalizedPath.isEmpty() || normalizedPath.startsWith("(")) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", normalizedPath);
            data.put("source", "console");
            data.put("content", state.consoleText);
            data.put("truncated", false);
            return data;
        }

        return readLogFileContent(normalizedPath, maxBytes);
    }

    private @NotNull LogState captureLogState(boolean runLog) throws Exception {
        ZaFridaRunPanel panel = requireRunPanel();
        return callOnUiThreadAndWait(() -> {
            ZaFridaConsolePanel console;
            if (runLog) {
                console = panel.getRunConsolePanelForApi();
            } else {
                console = panel.getAttachConsolePanelForApi();
            }

            String path = console.getLogFilePath();
            if (path == null) {
                path = "";
            }
            String trimmedPath = path.trim();
            boolean exists = false;
            if (!trimmedPath.isEmpty() && !trimmedPath.startsWith("(")) {
                Path filePath = Paths.get(trimmedPath);
                exists = Files.exists(filePath);
            }
            return new LogState(trimmedPath, console.getConsoleTextSnapshot(), exists);
        });
    }

    private @NotNull Map<String, Object> readLogFileContent(@NotNull String path, int maxBytes) throws IOException {
        Path logPath = Paths.get(path);
        if (!Files.exists(logPath) || !Files.isRegularFile(logPath)) {
            throw new ApiException(404, String.format("Log file not found: %s", path));
        }

        long fileSize = Files.size(logPath);
        boolean truncated = false;
        byte[] bytes;

        if (maxBytes > 0 && fileSize > maxBytes) {
            truncated = true;
            bytes = readTailBytes(logPath, maxBytes);
        } else {
            bytes = Files.readAllBytes(logPath);
        }

        String content = new String(bytes, StandardCharsets.UTF_8);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", logPath.toAbsolutePath().toString());
        data.put("source", "file");
        data.put("content", content);
        data.put("truncated", truncated);
        data.put("fileSize", fileSize);
        data.put("sizeHuman", formatFileSize(fileSize));
        if (maxBytes > 0) {
            data.put("maxBytes", maxBytes);
        }
        return data;
    }

    private byte @NotNull [] readTailBytes(@NotNull Path path, int maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long length = raf.length();
            if (length <= 0) {
                return new byte[0];
            }
            int bytesToRead = (int) Math.min(length, maxBytes);
            long start = length - bytesToRead;
            raf.seek(start);
            byte[] buffer = new byte[bytesToRead];
            raf.readFully(buffer);
            return buffer;
        }
    }

    /**
     * 按行读取日志文件的指定范围。
     * <p>
     * 参数：start（1-based 起始行号，默认 1）、count（读取行数，默认 100，上限 {@link #MAX_LINES_PER_REQUEST}）。
     */
    private @NotNull Map<String, Object> readLogLines(boolean runLog, @NotNull RequestContext request) throws Exception {
        LogState state = captureLogState(runLog);
        String path = request.get("path");
        if (ZaStrUtil.isBlank(path)) {
            path = state.path;
        }
        if (path == null) {
            path = "";
        }
        path = path.trim();

        if (path.isEmpty() || path.startsWith("(")) {
            throw new ApiException(400, "日志文件路径无效，当前会话可能仅使用 Console 输出");
        }

        Path logPath = Paths.get(path);
        if (!Files.exists(logPath) || !Files.isRegularFile(logPath)) {
            throw new ApiException(404, String.format("Log file not found: %s", path));
        }

        int startLine = parseNonNegativeInt(request.get("start"), 1, "start");
        if (startLine < 1) {
            startLine = 1;
        }
        int count = parseNonNegativeInt(request.get("count"), 100, "count");
        if (count <= 0) {
            count = 100;
        }
        if (count > MAX_LINES_PER_REQUEST) {
            count = MAX_LINES_PER_REQUEST;
        }

        long fileSize = Files.size(logPath);
        List<String> lines = new ArrayList<>();
        int currentLine = 0;
        int endLine = startLine + count - 1;
        boolean hasMore = false;

        try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                currentLine++;
                if (currentLine < startLine) {
                    continue;
                }
                if (currentLine > endLine) {
                    hasMore = true;
                    break;
                }
                lines.add(line);
            }
        }

        int actualEndLine = startLine + lines.size() - 1;
        if (lines.isEmpty()) {
            actualEndLine = startLine;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", logPath.toAbsolutePath().toString());
        data.put("fileSize", fileSize);
        data.put("sizeHuman", formatFileSize(fileSize));
        data.put("startLine", startLine);
        data.put("endLine", actualEndLine);
        data.put("linesRead", lines.size());
        data.put("hasMore", hasMore);
        data.put("lines", lines);
        return data;
    }

    // ── 设备列表 ──

    /**
     * 列出所有已连接设备（调用 frida-ls-devices，在后台线程执行）。
     */
    private @NotNull Map<String, Object> handleDevices(@NotNull RequestContext request) throws Exception {
        List<FridaDevice> devices;
        try {
            devices = fridaCliService.listDevices(project);
        } catch (Exception e) {
            throw new ApiException(500, String.format("枚举设备失败: %s", e.getMessage()));
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (FridaDevice device : devices) {
            Map<String, Object> item = deviceToMap(device);
            if (item != null) {
                list.add(item);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", list.size());
        data.put("devices", list);
        return data;
    }

    // ── 进程/应用列表 ──

    /**
     * 列出当前选中设备上的进程或应用。
     * <p>
     * 参数：scope（running/apps/installed，默认 running）。
     */
    private @NotNull Map<String, Object> handleProcesses(@NotNull RequestContext request) throws Exception {
        FridaDevice device = callOnUiThreadAndWait(() -> {
            ZaFridaRunPanel panel = runPanelRef.get();
            if (panel == null) {
                return null;
            }
            return panel.getSelectedDeviceForApi();
        });

        if (device == null) {
            throw new ApiException(409, "未选中设备，请先通过 /device/select 选择设备");
        }

        String scopeParam = request.get("scope");
        FridaProcessScope scope = parseProcessScope(scopeParam);

        List<FridaProcess> processes;
        try {
            processes = fridaCliService.listProcesses(project, device, scope);
        } catch (Exception e) {
            throw new ApiException(500, String.format("列出进程失败: %s", e.getMessage()));
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (FridaProcess proc : processes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pid", proc.getPid());
            item.put("name", proc.getName());
            item.put("identifier", proc.getIdentifier());
            list.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("device", deviceToMap(device));
        data.put("scope", scope.name().toLowerCase(Locale.ROOT));
        data.put("count", list.size());
        data.put("processes", list);
        return data;
    }

    private static @NotNull FridaProcessScope parseProcessScope(@Nullable String raw) {
        if (ZaStrUtil.isBlank(raw)) {
            return FridaProcessScope.RUNNING_PROCESSES;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("apps".equals(normalized) || "running_apps".equals(normalized)) {
            return FridaProcessScope.RUNNING_APPS;
        }
        if ("installed".equals(normalized) || "installed_apps".equals(normalized)) {
            return FridaProcessScope.INSTALLED_APPS;
        }
        return FridaProcessScope.RUNNING_PROCESSES;
    }

    // ── ADB 操作 ──

    /**
     * 通过 ADB 强制停止应用。
     * <p>
     * 参数：target（可选，默认使用当前 UI 中的 target）。
     */
    private @NotNull Map<String, Object> handleAdbForceStop(@NotNull RequestContext request) throws Exception {
        String target = resolveAdbTarget(request);
        String deviceId = resolveCurrentDeviceId();

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        List<String> logs = new ArrayList<>();

        adbService.forceStop(
                target,
                deviceId,
                logs::add,
                logs::add
        );

        return buildAdbResult("force-stop", target, logs);
    }

    /**
     * 通过 ADB 启动应用。
     * <p>
     * 参数：target（可选，默认使用当前 UI 中的 target）。
     */
    private @NotNull Map<String, Object> handleAdbOpenApp(@NotNull RequestContext request) throws Exception {
        String target = resolveAdbTarget(request);
        String deviceId = resolveCurrentDeviceId();

        List<String> logs = new ArrayList<>();

        adbService.openApp(
                target,
                deviceId,
                logs::add,
                logs::add
        );

        return buildAdbResult("open-app", target, logs);
    }

    private @NotNull String resolveAdbTarget(@NotNull RequestContext request) throws Exception {
        String target = request.get("target");
        if (ZaStrUtil.isNotBlank(target)) {
            return target.trim();
        }
        String uiTarget = callOnUiThreadAndWait(() -> {
            ZaFridaRunPanel panel = runPanelRef.get();
            if (panel == null) {
                return "";
            }
            return panel.getTargetTextForApi();
        });
        if (ZaStrUtil.isBlank(uiTarget)) {
            throw new ApiException(400, "未指定 target 且当前 UI 中无目标应用");
        }
        return uiTarget.trim();
    }

    private @Nullable String resolveCurrentDeviceId() throws Exception {
        FridaDevice device = callOnUiThreadAndWait(() -> {
            ZaFridaRunPanel panel = runPanelRef.get();
            if (panel == null) {
                return null;
            }
            return panel.getSelectedDeviceForApi();
        });
        if (device == null) {
            return null;
        }
        return device.getId();
    }

    private @NotNull Map<String, Object> buildAdbResult(@NotNull String action,
                                                         @NotNull String target,
                                                         @NotNull List<String> logs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", action);
        data.put("target", target);
        data.put("accepted", true);
        data.put("logs", logs);
        return data;
    }

    // ── Console 清空 ──

    /**
     * 清空指定控制台。
     * <p>
     * 参数：type（run/attach，默认 run）。
     */
    private @NotNull Map<String, Object> handleConsoleClear(@NotNull RequestContext request) throws Exception {
        String typeParam = request.get("type");
        boolean isRun = !"attach".equalsIgnoreCase(typeParam == null ? "" : typeParam.trim());

        runOnUiThreadAndWait(() -> {
            ZaFridaRunPanel panel = runPanelRef.get();
            if (panel == null) {
                throw new ApiException(409, "RunPanel 尚未就绪");
            }
            ZaFridaConsolePanel console;
            if (isRun) {
                console = panel.getRunConsolePanelForApi();
            } else {
                console = panel.getAttachConsolePanelForApi();
            }
            console.clear();
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", "console-clear");
        data.put("type", isRun ? "run" : "attach");
        data.put("accepted", true);
        return data;
    }

    // ── 环境诊断 ──

    /**
     * 运行环境诊断（6 项检查：Python SDK / Frida 路径 / 版本 / 设备枚举 / 设备连通 / ADB）。
     * <p>
     * 诊断在后台线程执行，本接口同步等待全部完成后返回结果。
     */
    private @NotNull Map<String, Object> handleDiagnostics(@NotNull RequestContext request) throws Exception {
        FridaDevice device = callOnUiThreadAndWait(() -> {
            ZaFridaRunPanel panel = runPanelRef.get();
            if (panel == null) {
                return null;
            }
            return panel.getSelectedDeviceForApi();
        });

        List<ZaFridaDiagnosticItem> items = diagnosticsService.createDefaultItems();
        CompletableFuture<List<ZaFridaDiagnosticItem>> future = new CompletableFuture<>();

        diagnosticsService.runDiagnostics(project, device, items, new ZaFridaDiagnosticsListener() {
            @Override
            public void onItemUpdated(@NotNull ZaFridaDiagnosticItem item) {
                // 无需逐项回调
            }

            @Override
            public void onAllCompleted(@NotNull List<ZaFridaDiagnosticItem> completedItems) {
                future.complete(completedItems);
            }
        });

        List<ZaFridaDiagnosticItem> results;
        try {
            results = future.get(DIAGNOSTICS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(504, "诊断超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "诊断被中断");
        } catch (ExecutionException e) {
            throw new ApiException(500, String.format("诊断执行失败: %s", e.getMessage()));
        }

        List<Map<String, Object>> itemList = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        for (ZaFridaDiagnosticItem item : results) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", item.getId());
            entry.put("title", item.getTitle());
            entry.put("status", item.getStatus().name().toLowerCase(Locale.ROOT));
            entry.put("message", item.getMessage());
            entry.put("tip", item.getTip());
            itemList.add(entry);

            if (item.getStatus() == ZaFridaDiagnosticStatus.SUCCESS) {
                successCount++;
            } else if (item.getStatus() == ZaFridaDiagnosticStatus.FAILED) {
                failedCount++;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", results.size());
        data.put("success", successCount);
        data.put("failed", failedCount);
        data.put("items", itemList);
        return data;
    }

    private @NotNull Map<String, Object> buildStateSummary() throws Exception {
        ZaFridaFridaProject active = projectManager.getActiveProject();
        ZaFridaProjectConfig cfg = loadProjectConfigBlocking(active);
        UiSnapshot uiSnapshot = captureUiSnapshot();

        Map<String, Object> api = new LinkedHashMap<>();
        api.put("host", BIND_HOST);
        api.put("port", boundPort);
        api.put("basePath", API_BASE);
        api.put("healthPath", API_HEALTH);

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("runRunning", sessionService.isRunning(ZaFridaSessionType.RUN));
        session.put("attachRunning", sessionService.isRunning(ZaFridaSessionType.ATTACH));

        long runFileSize = computeFileSize(uiSnapshot.runLogPath);
        long attachFileSize = computeFileSize(uiSnapshot.attachLogPath);

        Map<String, Object> logs = new LinkedHashMap<>();
        logs.put("runPath", uiSnapshot.runLogPath);
        logs.put("attachPath", uiSnapshot.attachLogPath);
        logs.put("runFileSize", runFileSize);
        logs.put("attachFileSize", attachFileSize);
        logs.put("runSizeHuman", formatFileSize(runFileSize));
        logs.put("attachSizeHuman", formatFileSize(attachFileSize));

        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("target", uiSnapshot.target);
        ui.put("extraArgs", uiSnapshot.extraArgs);
        ui.put("runScript", uiSnapshot.runScript);
        ui.put("attachScript", uiSnapshot.attachScript);
        ui.put("selectedDevice", deviceToMap(uiSnapshot.selectedDevice));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("api", api);
        data.put("activeProject", projectToMap(active));
        data.put("projects", projectListToMap(projectManager.listProjects()));
        data.put("config", configToMap(cfg));
        data.put("ui", ui);
        data.put("session", session);
        data.put("logs", logs);
        return data;
    }

    private @NotNull UiSnapshot captureUiSnapshot() throws Exception {
        ZaFridaRunPanel panel = runPanelRef.get();
        if (panel == null) {
            return UiSnapshot.empty();
        }
        return callOnUiThreadAndWait(() -> {
            ZaFridaRunPanel current = runPanelRef.get();
            if (current == null) {
                return UiSnapshot.empty();
            }
            FridaDevice selected = current.getSelectedDeviceForApi();
            String target = current.getTargetTextForApi();
            String extra = current.getExtraArgsForApi();
            String runScript = current.getRunScriptPathForApi();
            String attachScript = current.getAttachScriptPathForApi();

            ZaFridaConsolePanel runConsole = current.getRunConsolePanelForApi();
            ZaFridaConsolePanel attachConsole = current.getAttachConsolePanelForApi();

            String runPath = runConsole.getLogFilePath();
            String attachPath = attachConsole.getLogFilePath();
            return new UiSnapshot(target, extra, runScript, attachScript, selected, runPath, attachPath);
        });
    }

    private @Nullable ZaFridaProjectConfig loadProjectConfigBlocking(@Nullable ZaFridaFridaProject projectRef) throws Exception {
        if (projectRef == null) {
            return null;
        }
        CompletableFuture<ZaFridaProjectConfig> future = new CompletableFuture<>();
        projectManager.loadProjectConfigAsync(projectRef, future::complete);
        return waitFuture(future, "Load project config timeout");
    }

    private @Nullable ZaFridaFridaProject findProjectByName(@NotNull String name) {
        List<ZaFridaFridaProject> projects = projectManager.listProjects();
        for (ZaFridaFridaProject projectItem : projects) {
            if (name.equals(projectItem.getName())) {
                return projectItem;
            }
        }
        return null;
    }

    private @NotNull ZaFridaFridaProject requireActiveProject() {
        ZaFridaFridaProject active = projectManager.getActiveProject();
        if (active == null) {
            throw new ApiException(409, "No active project");
        }
        return active;
    }

    private @NotNull ZaFridaRunPanel requireRunPanel() {
        ZaFridaRunPanel panel = runPanelRef.get();
        if (panel == null) {
            throw new ApiException(409, "ZAFrida ToolWindow is not ready");
        }
        return panel;
    }

    private @NotNull ZaFridaPlatform parsePlatform(@NotNull String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("android".equals(normalized)) {
            return ZaFridaPlatform.ANDROID;
        }
        if ("ios".equals(normalized)) {
            return ZaFridaPlatform.IOS;
        }
        throw new ApiException(400, String.format("Unsupported platform: %s", raw));
    }

    private @NotNull FridaConnectionMode parseConnectionMode(@NotNull String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("usb".equals(normalized)) {
            return FridaConnectionMode.USB;
        }
        if ("remote".equals(normalized)) {
            return FridaConnectionMode.REMOTE;
        }
        if ("gadget".equals(normalized)) {
            return FridaConnectionMode.GADGET;
        }
        throw new ApiException(400, String.format("Unsupported connection mode: %s", raw));
    }

    private @Nullable Integer parseOptionalPort(@Nullable String text) {
        if (ZaStrUtil.isBlank(text)) {
            return null;
        }
        int value = parseNonNegativeInt(text, -1, "port");
        if (value <= 0 || value > 65535) {
            throw new ApiException(400, String.format("Invalid port: %s", text));
        }
        return value;
    }

    private int parseNonNegativeInt(@Nullable String text, int defaultValue, @NotNull String fieldName) {
        if (ZaStrUtil.isBlank(text)) {
            return defaultValue;
        }
        String normalized = text.trim();
        try {
            int value = Integer.parseInt(normalized);
            if (value < 0) {
                throw new ApiException(400, String.format("Invalid %s: %s", fieldName, text));
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(400, String.format("Invalid %s: %s", fieldName, text));
        }
    }

    /**
     * 将字节数格式化为可读字符串（如 "14.1 MB"）。
     */
    private static @NotNull String formatFileSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * 安全获取文件大小；路径无效或文件不存在时返回 0。
     */
    private long computeFileSize(@Nullable String path) {
        return computeFileSize(path, true);
    }

    /**
     * 安全获取文件大小。若 existsHint 为 false 且路径格式为控制台标记则跳过磁盘检查。
     */
    private long computeFileSize(@Nullable String path, boolean existsHint) {
        if (ZaStrUtil.isBlank(path)) {
            return 0L;
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("(")) {
            return 0L;
        }
        if (!existsHint) {
            return 0L;
        }
        try {
            Path filePath = Paths.get(trimmed);
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                return Files.size(filePath);
            }
        } catch (Exception e) {
            LOG.debug(String.format("[ZAFrida API] 获取文件大小失败: %s", trimmed), e);
        }
        return 0L;
    }

    private @NotNull Map<String, Object> actionResult(@NotNull String action) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", action);
        data.put("accepted", true);
        return data;
    }

    private @Nullable Map<String, Object> projectToMap(@Nullable ZaFridaFridaProject projectItem) {
        if (projectItem == null) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", projectItem.getName());
        data.put("platform", projectItem.getPlatform().name().toLowerCase(Locale.ROOT));
        data.put("relativeDir", projectItem.getRelativeDir());
        return data;
    }

    private @NotNull List<Map<String, Object>> projectListToMap(@NotNull List<ZaFridaFridaProject> projects) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ZaFridaFridaProject projectItem : projects) {
            Map<String, Object> map = projectToMap(projectItem);
            if (map != null) {
                list.add(map);
            }
        }
        return list;
    }

    private @Nullable Map<String, Object> configToMap(@Nullable ZaFridaProjectConfig cfg) {
        if (cfg == null) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("connectionMode", cfg.connectionMode.name().toLowerCase(Locale.ROOT));
        data.put("remoteHost", cfg.remoteHost);
        data.put("remotePort", cfg.remotePort);
        data.put("lastTarget", cfg.lastTarget);
        data.put("extraArgs", cfg.extraArgs);
        data.put("mainScript", cfg.mainScript);
        data.put("attachScript", cfg.attachScript);
        data.put("lastDeviceId", cfg.lastDeviceId);
        data.put("lastDeviceHost", cfg.lastDeviceHost);
        return data;
    }

    private @Nullable Map<String, Object> deviceToMap(@Nullable FridaDevice device) {
        if (device == null) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", device.getId());
        data.put("name", device.getName());
        data.put("type", device.getType());
        data.put("host", device.getHost());
        data.put("mode", device.getMode().name().toLowerCase(Locale.ROOT));
        return data;
    }

    /**
     * 在 EDT 上执行有返回值的操作并同步等待结果。
     * <p>
     * 注意：Callable 的返回值不能为 null，否则 @NotNull 契约会在运行时崩溃。
     * 对于无返回值的操作（如 triggerRun），请使用 {@link #runOnUiThreadAndWait(Runnable)}。
     */
    private <T> @NotNull T callOnUiThreadAndWait(@NotNull Callable<T> callable) throws Exception {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return callable.call();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                future.completeExceptionally(new ApiException(410, "Project disposed"));
                return;
            }
            try {
                T value = callable.call();
                future.complete(value);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, ModalityState.NON_MODAL);
        return waitFuture(future, "UI operation timeout");
    }

    /**
     * 在 EDT 上执行无返回值的操作并同步等待完成。
     * <p>
     * 与 {@link #callOnUiThreadAndWait(Callable)} 不同，此方法不经过 @NotNull 约束的 waitFuture，
     * 避免 return null 导致运行时 @NotNull 违规崩溃。
     */
    private void runOnUiThreadAndWait(@NotNull Runnable action) throws Exception {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            action.run();
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                future.completeExceptionally(new ApiException(410, "Project disposed"));
                return;
            }
            try {
                action.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, ModalityState.NON_MODAL);

        try {
            future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "Interrupted");
        } catch (TimeoutException e) {
            throw new ApiException(504, "UI operation timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException apiException) {
                throw apiException;
            }
            String message = null;
            if (cause != null) {
                message = cause.getMessage();
            }
            if (ZaStrUtil.isBlank(message)) {
                message = "Execution failed";
            }
            throw new ApiException(500, message);
        }
    }

    /**
     * 等待 Void 类型的 Future 完成（不经过 @NotNull 返回值约束）。
     */
    private void awaitVoidFuture(@NotNull CompletableFuture<Void> future, @NotNull String timeoutMessage) {
        try {
            future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "Interrupted");
        } catch (TimeoutException e) {
            throw new ApiException(504, timeoutMessage);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException apiException) {
                throw apiException;
            }
            String message = null;
            if (cause != null) {
                message = cause.getMessage();
            }
            if (ZaStrUtil.isBlank(message)) {
                message = "Execution failed";
            }
            throw new ApiException(500, message);
        }
    }

    private <T> @NotNull T waitFuture(@NotNull CompletableFuture<T> future, @NotNull String timeoutMessage) {
        try {
            return future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "Interrupted");
        } catch (TimeoutException e) {
            throw new ApiException(504, timeoutMessage);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException apiException) {
                throw apiException;
            }
            String message = null;
            if (cause != null) {
                message = cause.getMessage();
            }
            if (ZaStrUtil.isBlank(message)) {
                message = "Execution failed";
            }
            throw new ApiException(500, message);
        }
    }

    private void writeNoContent(@NotNull HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        fillCommonHeaders(headers);
        exchange.sendResponseHeaders(204, -1);
    }

    private void writeSuccess(@NotNull HttpExchange exchange, @NotNull Map<String, Object> data) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("status", 200);
        payload.put("data", data);
        writeJson(exchange, 200, payload);
    }

    private void writeError(@NotNull HttpExchange exchange, int statusCode, @NotNull String message) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("status", statusCode);
        payload.put("message", message);
        writeJson(exchange, statusCode, payload);
    }

    private void writeJson(@NotNull HttpExchange exchange,
                           int statusCode,
                           @NotNull Map<String, Object> payload) throws IOException {
        byte[] bytes = toJson(payload).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        fillCommonHeaders(headers);
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void fillCommonHeaders(@NotNull Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.set("Cache-Control", "no-store");
    }

    private @NotNull String toJson(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + escapeJson(text) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder();
            builder.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(",");
                }
                first = false;
                builder.append("\"");
                builder.append(escapeJson(String.valueOf(entry.getKey())));
                builder.append("\":");
                builder.append(toJson(entry.getValue()));
            }
            builder.append("}");
            return builder.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder();
            builder.append("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(",");
                }
                first = false;
                builder.append(toJson(item));
            }
            builder.append("]");
            return builder.toString();
        }
        if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            List<Object> list = new ArrayList<>();
            for (Object item : array) {
                list.add(item);
            }
            return toJson(list);
        }
        return "\"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private @NotNull String escapeJson(@NotNull String text) {
        StringBuilder builder = new StringBuilder();
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }

    @Override
    public void dispose() {
        stopServerSafely();
    }

    private interface ApiHandler {
        @NotNull Map<String, Object> handle(@NotNull RequestContext request) throws Exception;
    }

    private static final class ApiException extends RuntimeException {
        private final int statusCode;

        private ApiException(int statusCode, @NotNull String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    private static final class RequestContext {
        private final @NotNull Map<String, String> params;

        private RequestContext(@NotNull Map<String, String> params) {
            this.params = params;
        }

        private static @NotNull RequestContext from(@NotNull HttpExchange exchange) throws IOException {
            Map<String, String> params = new LinkedHashMap<>();
            URI uri = exchange.getRequestURI();
            if (uri != null) {
                parseParamString(uri.getRawQuery(), params);
            }

            String method = exchange.getRequestMethod();
            if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
                byte[] bodyBytes = readAll(exchange.getRequestBody());
                if (bodyBytes.length > 0) {
                    String body = new String(bodyBytes, StandardCharsets.UTF_8);
                    parseParamString(body, params);
                }
            }

            return new RequestContext(params);
        }

        private static void parseParamString(@Nullable String raw, @NotNull Map<String, String> out) {
            if (ZaStrUtil.isBlank(raw)) {
                return;
            }
            String[] pairs = raw.split("&");
            for (String pair : pairs) {
                if (pair.isEmpty()) {
                    continue;
                }
                String key;
                String value;
                int index = pair.indexOf('=');
                if (index < 0) {
                    key = decode(pair);
                    value = "";
                } else {
                    key = decode(pair.substring(0, index));
                    value = decode(pair.substring(index + 1));
                }
                if (ZaStrUtil.isBlank(key)) {
                    continue;
                }
                out.put(key, value);
            }
        }

        private static byte @NotNull [] readAll(@NotNull InputStream inputStream) throws IOException {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }

        private static @NotNull String decode(@NotNull String text) {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        }

        private @Nullable String get(@NotNull String key) {
            return params.get(key);
        }

        private @NotNull String getOrDefault(@NotNull String key, @NotNull String defaultValue) {
            String value = params.get(key);
            if (value == null) {
                return defaultValue;
            }
            return value;
        }

        private @NotNull String require(@NotNull String key) {
            String value = params.get(key);
            if (ZaStrUtil.isBlank(value)) {
                throw new ApiException(400, String.format("Missing parameter: %s", key));
            }
            return Objects.requireNonNull(value);
        }
    }

    private static final class UiSnapshot {
        private final @NotNull String target;
        private final @NotNull String extraArgs;
        private final @NotNull String runScript;
        private final @NotNull String attachScript;
        private final @Nullable FridaDevice selectedDevice;
        private final @Nullable String runLogPath;
        private final @Nullable String attachLogPath;

        private UiSnapshot(@NotNull String target,
                           @NotNull String extraArgs,
                           @NotNull String runScript,
                           @NotNull String attachScript,
                           @Nullable FridaDevice selectedDevice,
                           @Nullable String runLogPath,
                           @Nullable String attachLogPath) {
            this.target = target;
            this.extraArgs = extraArgs;
            this.runScript = runScript;
            this.attachScript = attachScript;
            this.selectedDevice = selectedDevice;
            this.runLogPath = runLogPath;
            this.attachLogPath = attachLogPath;
        }

        private static @NotNull UiSnapshot empty() {
            return new UiSnapshot("", "", "", "", null, null, null);
        }
    }

    private static final class LogState {
        private final @NotNull String path;
        private final @NotNull String consoleText;
        private final boolean existsOnDisk;

        private LogState(@NotNull String path, @NotNull String consoleText, boolean existsOnDisk) {
            this.path = path;
            this.consoleText = consoleText;
            this.existsOnDisk = existsOnDisk;
        }
    }
}
