---
name: zafrida-http-control
description: 通过本地 HTTP API 控制 ZAFrida（IntelliJ/PyCharm 插件）。支持项目查询/切换/创建、设备与连接模式配置、目标/脚本/额外参数设置、Run/Attach/Stop 操作、日志路径/内容/按行读取，以及汇总状态查询。
---

# ZAFrida HTTP 控制

当用户需要通过脚本/自动化方式操控 ZAFrida 而不依赖 UI 点击时，使用此技能。

## 前置条件

- ZAFrida 插件已安装，且 ToolWindow 至少打开过一次。
- 本地 API 可达（默认地址：`http://127.0.0.1:17839/zafrida/api/v1`）。
- 如需自定义地址，设置环境变量 `ZAFRIDA_API_BASE` 即可。

## 接口概览

共 20 个接口，分为以下几组：

### 状态与健康

| 命令 | 方法 | 说明 |
|------|------|------|
| `health` | GET | 服务健康检查（端口、就绪状态） |
| `state` | GET | 完整状态汇总（项目/设备/脚本/会话/日志大小），不含日志内容 |

### 项目管理

| 命令 | 方法 | 参数 | 说明 |
|------|------|------|------|
| `project-current` | GET | — | 当前活跃项目及项目列表 |
| `project-select` | POST | `--name` | 切换活跃项目 |
| `project-create` | POST | `--name --platform` | 新建项目（android/ios） |

### 设备与连接

| 命令 | 方法 | 参数 | 说明 |
|------|------|------|------|
| `device-select` | POST | `--id` 或 `--host` | 选择设备（二选一） |
| `mode-set` | POST | `--mode [--host --port]` | 设置连接模式（usb/remote/gadget） |
| `target-set` | POST | `--target` | 设置目标应用包名 |

### 脚本与参数

| 命令 | 方法 | 参数 | 说明 |
|------|------|------|------|
| `run-script-set` | POST | `--path` | 设置 Run 脚本路径 |
| `attach-script-set` | POST | `--path` | 设置 Attach 脚本路径 |
| `extra-set` | POST | `--value` | 设置额外命令行参数 |

### 会话控制

| 命令 | 方法 | 说明 |
|------|------|------|
| `run` | POST | 启动 Run 会话 |
| `stop` | POST | 停止 Run 会话 |
| `attach` | POST | 启动 Attach 会话 |
| `stop-attach` | POST | 停止 Attach 会话 |

### 日志读取

| 命令 | 方法 | 参数 | 说明 |
|------|------|------|------|
| `run-log-path` | GET | — | 获取 Run 日志路径、文件大小 |
| `run-log-content` | GET | `[--path] [--max-bytes]` | 读取 Run 日志内容（可截断） |
| `run-log-lines` | GET | `[--path] [--start] [--count]` | 按行读取 Run 日志（最多 2000 行/次） |
| `attach-log-path` | GET | — | 获取 Attach 日志路径、文件大小 |
| `attach-log-content` | GET | `[--path] [--max-bytes]` | 读取 Attach 日志内容（可截断） |
| `attach-log-lines` | GET | `[--path] [--start] [--count]` | 按行读取 Attach 日志（最多 2000 行/次） |

## 命令行调用

使用内置脚本 `zafrida_skill_cli.py`（全局路径 `~/.claude/tools/zafrida_skill_cli.py`）：

```bash
# 状态与健康
python3 ~/.claude/tools/zafrida_skill_cli.py health
python3 ~/.claude/tools/zafrida_skill_cli.py state

# 项目
python3 ~/.claude/tools/zafrida_skill_cli.py project-current
python3 ~/.claude/tools/zafrida_skill_cli.py project-select --name demo
python3 ~/.claude/tools/zafrida_skill_cli.py project-create --name app1 --platform android

# 设备与连接
python3 ~/.claude/tools/zafrida_skill_cli.py device-select --id usb
python3 ~/.claude/tools/zafrida_skill_cli.py mode-set --mode remote --host 127.0.0.1 --port 14725

# 脚本与参数
python3 ~/.claude/tools/zafrida_skill_cli.py target-set --target com.demo.app
python3 ~/.claude/tools/zafrida_skill_cli.py run-script-set --path /abs/path/run.js
python3 ~/.claude/tools/zafrida_skill_cli.py attach-script-set --path /abs/path/attach.js
python3 ~/.claude/tools/zafrida_skill_cli.py extra-set --value "--realm=emulated"

# 会话控制
python3 ~/.claude/tools/zafrida_skill_cli.py run
python3 ~/.claude/tools/zafrida_skill_cli.py stop
python3 ~/.claude/tools/zafrida_skill_cli.py attach
python3 ~/.claude/tools/zafrida_skill_cli.py stop-attach

# 日志（路径 + 大小）
python3 ~/.claude/tools/zafrida_skill_cli.py run-log-path
python3 ~/.claude/tools/zafrida_skill_cli.py attach-log-path

# 日志（内容，可限制字节数）
python3 ~/.claude/tools/zafrida_skill_cli.py run-log-content --max-bytes 200000
python3 ~/.claude/tools/zafrida_skill_cli.py attach-log-content --max-bytes 200000

# 日志（按行读取，适合大文件分页）
python3 ~/.claude/tools/zafrida_skill_cli.py run-log-lines --start 1 --count 100
python3 ~/.claude/tools/zafrida_skill_cli.py attach-log-lines --start 1000 --count 200
```

## 推荐工作流

1. 先调用 `state` 获取完整上下文（项目、设备、脚本路径、会话状态、日志文件大小）。
2. 按需配置必要字段（`project-select`、`mode-set`、脚本、目标、额外参数）。
3. 触发 `run` 或 `attach`。
4. 根据日志文件大小选择读取策略：
   - **小日志**（< 200KB）：直接用 `run-log-content` 读取全部内容。
   - **中等日志**：用 `run-log-lines --start N --count M` 按行分页读取。
   - **大日志**（> 1MB）：用返回的 `path` 配合 `large-text-viewer` 进行高效行提取和搜索（参见 `/use-text-viewer` 全局技能）。

## 响应格式

所有接口返回统一 JSON 结构：

```json
// 成功
{"ok": true, "status": 200, "data": { ... }}

// 失败
{"ok": false, "status": 400, "message": "错误描述"}
```

## 注意事项

- 汇总接口 `state` 不包含日志内容，仅返回路径和文件大小（避免大体积响应）。
- `run-log-path` / `attach-log-path` 返回 `fileSize`（字节数）和 `sizeHuman`（可读格式如 "14.1 MB"）。
- `run-log-content` 的 `content` 字段为纯日志内容，截断信息通过 `truncated`/`fileSize`/`maxBytes` 元数据传递（不会注入标记文本）。
- `run-log-lines` 的 `lines` 为字符串数组，响应包含 `startLine`/`endLine`/`linesRead`/`hasMore` 元数据。
- 单次按行读取上限 2000 行；超过此限制请分多次请求或使用 `large-text-viewer`。
