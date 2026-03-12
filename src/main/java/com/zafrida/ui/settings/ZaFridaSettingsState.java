package com.zafrida.ui.settings;

import java.util.ArrayList;
import java.util.List;
/**
 * [DTO] 全局配置数据模型。
 * <p>
 * <strong>数据流：</strong>
 * 映射到 IDE 配置目录下的 {@code zafrida.xml} 文件。
 * 存储不随项目改变的通用设置，如 frida 工具链路径、VS Code/010 Editor 路径和常用远程主机列表。
 */
public final class ZaFridaSettingsState {

    /** 模板根目录模式：系统目录 */
    public static final String TEMPLATE_ROOT_MODE_SYSTEM = "SYSTEM";
    /** 模板根目录模式：IDE 根目录 */
    public static final String TEMPLATE_ROOT_MODE_IDE = "IDE";
    /** Skills HTTP API 默认端口 */
    public static final int DEFAULT_SKILLS_API_PORT = 17839;

    /** frida 可执行文件路径 */
    public String fridaExecutable = "frida";
    /** frida-ps 可执行文件路径 */
    public String fridaPsExecutable = "frida-ps";
    /** frida-ls-devices 可执行文件路径 */
    public String fridaLsDevicesExecutable = "frida-ls-devices";
    /** Frida 主版本号（默认 16，用于兼容脚本片段差异） */
    public String fridaVersion = "16";
    /** VS Code 可执行文件路径（可选：code / code.cmd / Code.exe / Visual Studio Code.app） */
    public String vscodeExecutable = "";
    /** 010 Editor 可执行文件路径（可选：010editor / 010Editor.exe / 010 Editor.app） */
    public String editor010Executable = "";
    /** 日志目录名 */
    public String logsDirName = "zafrida-logs";
    /** 远程主机历史列表 */
    public List<String> remoteHosts = new ArrayList<>();
    /** 默认远程主机 */
    public String defaultRemoteHost = "127.0.0.1";
    /** 默认远程端口 */
    public int defaultRemotePort = 14725;
    /** 是否使用 IDE 自带脚本选择器 */
    public boolean useIdeScriptChooser = true;
    /** 模板根目录模式（SYSTEM/IDE） */
    public String templatesRootMode = TEMPLATE_ROOT_MODE_SYSTEM;
    /** 是否开启 Skills 本地 HTTP API */
    public boolean enableSkillsHttpApi = false;
    /** Skills 本地 HTTP API 端口 */
    public int skillsApiPort = DEFAULT_SKILLS_API_PORT;
}
