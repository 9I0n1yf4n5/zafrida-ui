package com.zafrida.ui.frida;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.CommandLineTokenizer;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfoRt;
import com.zafrida.ui.python.ProjectPythonEnvResolver;
import com.zafrida.ui.python.PythonEnvInfo;
import com.zafrida.ui.settings.ZaFridaSettingsService;
import com.zafrida.ui.settings.ZaFridaSettingsState;
import com.zafrida.ui.util.ZaStrUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * [核心服务] Frida 命令行工具执行网关。
 * <p>
 * <strong>核心职责：</strong>
 * 1. 封装 `frida`, `frida-ps`, `frida-ls-devices` 的调用逻辑。
 * 2. 负责将 {@link com.zafrida.ui.frida.FridaRunConfig} 转换为实际的 {@link GeneralCommandLine}。
 * 3. <strong>关键逻辑：</strong> 必须调用 {@link ProjectPythonEnvResolver} 注入 Python 环境，否则会报 "command not found"。
 * <p>
 * 注意：所有命令输出强制使用 UTF-8 编码以支持中文应用名。
 */
public final class FridaCliService {

    /** 日志记录器 */
    private static final Logger LOG = Logger.getInstance(FridaCliService.class);

    /** 默认的设备枚举超时（毫秒）。部分 Windows / 远程环境可能较慢，因此这里相对保守。 */
    private static final int LIST_DEVICES_TIMEOUT_MS = 30_000;

    /** Python 输出编码环境变量 key。 */
    private static final String ENV_PYTHONIOENCODING = "PYTHONIOENCODING";
    /** Python UTF-8 模式环境变量 key。 */
    private static final String ENV_PYTHONUTF8 = "PYTHONUTF8";
    /** Python 无缓冲输出环境变量 key。 */
    private static final String ENV_PYTHONUNBUFFERED = "PYTHONUNBUFFERED";

    /** 通过 python -c 直接调用 frida 模块枚举设备，避免 frida-ls-devices 依赖 prompt_toolkit/Console。 */
    private static final String PY_ENUM_DEVICES_SCRIPT =
            "import frida\n"
                    + "def _type_str(v):\n"
                    + "    try:\n"
                    + "        if hasattr(v, 'value'):\n"
                    + "            return str(v.value)\n"
                    + "        if hasattr(v, 'name'):\n"
                    + "            return str(v.name)\n"
                    + "    except Exception:\n"
                    + "        pass\n"
                    + "    return str(v)\n"
                    + "print('Id  Type  Name')\n"
                    + "for d in frida.enumerate_devices():\n"
                    + "    t = _type_str(getattr(d, 'type', ''))\n"
                    + "    if t:\n"
                    + "        t = t.lower()\n"
                    + "    print(f\"{getattr(d, 'id', '')}  {t}  {getattr(d, 'name', '')}\")\n";

    /** 设置服务 */
    private final ZaFridaSettingsService settings;

    /**
     * 构造函数。
     */
    public FridaCliService() {
        this.settings = ApplicationManager.getApplication().getService(ZaFridaSettingsService.class);
    }

    /**
     * 列出当前可用设备。
     * @param project 当前 IDE 项目
     * @return 设备列表
     */
    public @NotNull List<FridaDevice> listDevices(@NotNull Project project) {
        GeneralCommandLine cmd = buildLsDevicesCommandLine(project);
        try {
            CapturedOut out = runCapturing(cmd, LIST_DEVICES_TIMEOUT_MS);
            return FridaOutputParsers.parseDevices(out.stdout);
        } catch (FridaCliException e) {
            // Best-effort: some environments may produce valid table output but still exit non-zero.
            // 尽力而为：某些环境下即便 exit code 非 0，也可能已经输出了可解析的表格内容。
            List<FridaDevice> parsed = FridaOutputParsers.parseDevices(e.getStdout());
            if (!parsed.isEmpty()) {
                LOG.warn(String.format("frida-ls-devices returned non-zero but produced %s devices, use stdout anyway. exit=%s cmd=%s",
                        parsed.size(), e.getExitCode(), e.getCommandLine()));
                return parsed;
            }

            if (shouldFallbackToPythonForNoConsole(e)) {
                LOG.warn(String.format("frida-ls-devices failed due to missing Windows console, fallback to python frida enumeration. cmd=%s",
                        e.getCommandLine()));
                try {
                    return listDevicesViaPython(project);
                } catch (Throwable t) {
                    // Keep the original error for UI, but retain fallback failure for logs.
                    // UI 侧保留原始报错信息，但日志中保留 fallback 失败原因。
                    e.addSuppressed(t);
                    throw e;
                }
            }
            throw e;
        }
    }

    /**
     * 列出设备上的进程或应用。
     * @param project 当前 IDE 项目
     * @param device 目标设备
     * @param scope 列表作用域
     * @return 进程/应用列表
     */
    public @NotNull List<FridaProcess> listProcesses(@NotNull Project project,
                                                     @NotNull FridaDevice device,
                                                     @NotNull FridaProcessScope scope) {
        GeneralCommandLine cmd = buildPsCommandLine(project, device, scope);
        CapturedOut out = runCapturing(cmd, 20_000);
        return FridaOutputParsers.parseProcesses(out.stdout);
    }

    /**
     * 构建执行 Frida 的命令行对象。
     * @param project 当前 IDE 项目
     * @param config 运行配置
     * @return GeneralCommandLine
     */
    public @NotNull GeneralCommandLine buildRunCommandLine(@NotNull Project project, @NotNull FridaRunConfig config) {
        ZaFridaSettingsState s = settings.getState();
        GeneralCommandLine cmd = new GeneralCommandLine(s.fridaExecutable)
                .withCharset(StandardCharsets.UTF_8);

        applyProjectPythonEnv(project, cmd);

        addDeviceArgs(cmd, config.getDevice());

        FridaRunMode mode = config.getMode();
        if (mode instanceof FrontmostRunMode) {
            cmd.addParameter("-F");
        } else if (mode instanceof SpawnRunMode) {
            cmd.addParameters("-f", ((SpawnRunMode) mode).getIdentifier());
        } else if (mode instanceof AttachPidRunMode) {
            cmd.addParameters("-p", String.valueOf(((AttachPidRunMode) mode).getPid()));
        } else if (mode instanceof AttachNameRunMode) {
            cmd.addParameters("-N", ((AttachNameRunMode) mode).getName());
        } else {
            throw new IllegalArgumentException(String.format("Unknown run mode: %s", mode));
        }

        cmd.addParameters("-l", config.getScriptPath());

        String extra = config.getExtraArgs();
        if (ZaStrUtil.isNotBlank(extra)) {
            CommandLineTokenizer tok = new CommandLineTokenizer(extra);
            while (tok.hasMoreTokens()) {
                cmd.addParameter(tok.nextToken());
            }
        }

        return cmd;
    }

    /**
     * 创建并返回运行进程处理器。
     * @param project 当前 IDE 项目
     * @param config 运行配置
     * @return OSProcessHandler
     */
    public @NotNull OSProcessHandler createRunProcessHandler(@NotNull Project project, @NotNull FridaRunConfig config) {
        try {
            return new OSProcessHandler(buildRunCommandLine(project, config));
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 构建 frida --version 命令行。
     * @param project 当前 IDE 项目
     * @return GeneralCommandLine
     */
    public @NotNull GeneralCommandLine buildFridaVersionCommandLine(@NotNull Project project) {
        ZaFridaSettingsState s = settings.getState();
        GeneralCommandLine cmd = new GeneralCommandLine(s.fridaExecutable)
                .withCharset(StandardCharsets.UTF_8);
        applyProjectPythonEnv(project, cmd);
        cmd.addParameter("--version");
        return cmd;
    }

    /**
     * 构建 frida-ls-devices 命令行（用于诊断）。
     * @param project 当前 IDE 项目
     * @return GeneralCommandLine
     */
    public @NotNull GeneralCommandLine buildLsDevicesCommandLineForDiagnostics(@NotNull Project project) {
        return buildLsDevicesCommandLine(project);
    }

    /**
     * 构建 frida-ps 命令行（用于诊断）。
     * @param project 当前 IDE 项目
     * @param device 目标设备
     * @param scope 查询范围
     * @return GeneralCommandLine
     */
    public @NotNull GeneralCommandLine buildPsCommandLineForDiagnostics(@NotNull Project project,
                                                                        @NotNull FridaDevice device,
                                                                        @NotNull FridaProcessScope scope) {
        return buildPsCommandLine(project, device, scope);
    }

    /**
     * 构建 frida-ls-devices 命令行。
     * @param project 当前 IDE 项目
     * @return GeneralCommandLine
     */
    private @NotNull GeneralCommandLine buildLsDevicesCommandLine(@NotNull Project project) {
        ZaFridaSettingsState s = settings.getState();
        GeneralCommandLine cmd = new GeneralCommandLine(s.fridaLsDevicesExecutable)
                .withCharset(StandardCharsets.UTF_8);
        applyProjectPythonEnv(project, cmd);
        return cmd;
    }

    private boolean shouldFallbackToPythonForNoConsole(@NotNull FridaCliException e) {
        if (!SystemInfoRt.isWindows) {
            return false;
        }

        String detail = e.getStderr();
        if (ZaStrUtil.isBlank(detail)) {
            detail = e.getMessage();
        }
        if (ZaStrUtil.isBlank(detail)) {
            return false;
        }

        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("noconsolescreenbuffererror")) {
            return true;
        }
        if (lower.contains("no windows console found")) {
            return true;
        }
        // defensive: prompt_toolkit on Windows console initialization
        // 防御性判断：prompt_toolkit 在 Windows 下初始化 Console 失败
        if (lower.contains("prompt_toolkit") && lower.contains("no console")) {
            return true;
        }
        return false;
    }

    private @NotNull List<FridaDevice> listDevicesViaPython(@NotNull Project project) {
        List<String> candidates = resolvePythonCandidates(project);
        FridaCliException lastCliError = null;

        for (String pythonExe : candidates) {
            try {
                GeneralCommandLine cmd = buildPythonEnumerateDevicesCommandLine(project, pythonExe);
                CapturedOut out = runCapturing(cmd, LIST_DEVICES_TIMEOUT_MS);
                return FridaOutputParsers.parseDevices(out.stdout);
            } catch (FridaCliException e) {
                lastCliError = e;
                LOG.warn(String.format("List devices via python failed: python=%s exit=%s cmd=%s", pythonExe, e.getExitCode(), e.getCommandLine()));
            } catch (Throwable t) {
                LOG.warn(String.format("List devices via python failed: python=%s", pythonExe), t);
            }
        }

        if (lastCliError != null) {
            throw lastCliError;
        }
        return new ArrayList<>();
    }

    private @NotNull List<String> resolvePythonCandidates(@NotNull Project project) {
        Set<String> out = new LinkedHashSet<>();

        PythonEnvInfo env = ProjectPythonEnvResolver.resolve(project);
        if (env != null) {
            String home = env.getPythonHome();
            if (ZaStrUtil.isNotBlank(home)) {
                out.add(home);
            }
        }

        // Fallback to common python executable names in PATH.
        // 回退到 PATH 中常见的 python 可执行名称。
        out.add("python");
        if (SystemInfoRt.isWindows) {
            out.add("py");
        }

        return new ArrayList<>(out);
    }

    private @NotNull GeneralCommandLine buildPythonEnumerateDevicesCommandLine(@NotNull Project project,
                                                                              @NotNull String pythonExe) {
        GeneralCommandLine cmd = new GeneralCommandLine(pythonExe)
                .withCharset(StandardCharsets.UTF_8);

        applyProjectPythonEnv(project, cmd);
        cmd.addParameters("-c", PY_ENUM_DEVICES_SCRIPT);
        return cmd;
    }

    /**
     * 构建 frida-ps 命令行。
     * @param project 当前 IDE 项目
     * @param device 目标设备
     * @param scope 查询范围
     * @return GeneralCommandLine
     */
    private @NotNull GeneralCommandLine buildPsCommandLine(@NotNull Project project,
                                                           @NotNull FridaDevice device,
                                                           @NotNull FridaProcessScope scope) {
        ZaFridaSettingsState s = settings.getState();
        GeneralCommandLine cmd = new GeneralCommandLine(s.fridaPsExecutable)
                .withCharset(StandardCharsets.UTF_8);

        applyProjectPythonEnv(project, cmd);

        addDeviceArgs(cmd, device);

        switch (scope) {
            case RUNNING_PROCESSES -> {
                // default
                // 默认行为
            }
            case RUNNING_APPS -> cmd.addParameter("-a");
            case INSTALLED_APPS -> cmd.addParameters("-a", "-i");
        }

        return cmd;
    }

    /**
     * 注入项目 Python 环境到命令行。
     * @param project 当前 IDE 项目
     * @param cmd 命令行对象
     */
    private void applyProjectPythonEnv(@NotNull Project project, @NotNull GeneralCommandLine cmd) {
        // Make sure we inherit the parent environment, then prepend the project interpreter's PATH.
        // 确保继承父环境变量，并将项目解释器路径追加到 PATH 前面。
        cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        applyPythonOutputEncodingEnv(cmd);
        PythonEnvInfo env = ProjectPythonEnvResolver.resolve(project);
        if (env != null) {
            // Prefer an absolute executable path when possible to avoid Windows CreateProcess PATH quirks.
            // 尽量将可执行文件解析为绝对路径，避免 Windows 下 CreateProcess 对 PATH 搜索的兼容性差异。
            String exe = cmd.getExePath();
            if (ZaStrUtil.isNotBlank(exe) && !looksLikePath(exe)) {
                String resolved = ProjectPythonEnvResolver.findTool(env, exe);
                if (ZaStrUtil.isNotBlank(resolved)) {
                    cmd.setExePath(resolved);
                }
            }
            ProjectPythonEnvResolver.applyToCommandLine(cmd, env);
        }
    }

    /**
     * 统一设置 Python 输出编码，避免不同系统默认编码差异导致日志乱码。
     * <p>
     * 对非 Python 程序这些变量通常无副作用；对 Python 入口（frida-tools）可强制 UTF-8 输出并关闭缓冲。
     * </p>
     * @param cmd 命令行对象
     */
    private void applyPythonOutputEncodingEnv(@NotNull GeneralCommandLine cmd) {
        cmd.getEnvironment().put(ENV_PYTHONIOENCODING, "UTF-8");
        cmd.getEnvironment().put(ENV_PYTHONUTF8, "1");
        cmd.getEnvironment().put(ENV_PYTHONUNBUFFERED, "1");
    }

    private static boolean looksLikePath(@NotNull String value) {
        // Anything containing path separators is treated as a path (absolute or relative).
        // 只要包含路径分隔符，就认为是路径（绝对或相对）。
        return value.contains("/") || value.contains("\\");
    }

    /**
     * 根据设备信息添加连接参数。
     * @param cmd 命令行对象
     * @param device 目标设备
     */
    private void addDeviceArgs(@NotNull GeneralCommandLine cmd, @NotNull FridaDevice device) {
        if (device.getMode() == FridaDeviceMode.HOST) {
            String host = device.getHost();
            if (ZaStrUtil.isBlank(host)) {
                throw new IllegalArgumentException("Device host is null/blank");
            }
            cmd.addParameters("-H", host);
            return;
        }
        String id = device.getId();
        if ("usb".equalsIgnoreCase(id)) {
            cmd.addParameter("-U");
        } else {
            cmd.addParameters("-D", id);
        }
    }

    /**
     * 执行命令并捕获输出。
     * @param cmd 命令行对象
     * @param timeoutMs 超时时间（毫秒）
     * @return 捕获结果
     */
    private CapturedOut runCapturing(@NotNull GeneralCommandLine cmd, int timeoutMs) {
        CapturingProcessHandler handler = null;
        try {
            handler = new CapturingProcessHandler(cmd);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        var out = handler.runProcess(timeoutMs);

        String stdout = out.getStdout() != null ? out.getStdout() : "";
        String stderr = out.getStderr() != null ? out.getStderr() : "";
        int exitCode = out.getExitCode();

        if (exitCode != 0) {
            String cmdLine = cmd.getCommandLineString();
            LOG.warn(String.format("Frida tool failed: exit=%s cmd=%s stderr=%s stdout=%s", exitCode, cmdLine, stderr, stdout));
            throw new FridaCliException(
                    String.format("Command failed (exit=%s): %s\n%s", exitCode, cmdLine, stderr),
                    cmdLine,
                    exitCode,
                    stdout,
                    stderr
            );
        }

        return new CapturedOut(stdout, stderr, exitCode);
    }
}
