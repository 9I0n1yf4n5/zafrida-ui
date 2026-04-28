# `com.zafrida.ui.api` — 本地 HTTP API（AGENTS）

本模块提供 ZAFrida 的本地可编排接口，主要供 skills/CLI 自动化调用。

## 设计目标
- 仅开放本地环回地址（`127.0.0.1`），避免暴露到公网。
- 把外部调用映射到现有 `ui/`、`fridaproject/`、`session/` 能力，禁止平行实现。
- 接口返回结构化 JSON，便于脚本与工具链消费。

## 接口清单（20 个接口 + 1 个汇总）
- **状态与健康**：`/health`、`/state`（汇总，不含日志内容）。
- **项目管理**：`/project/current`、`/project/select`、`/project/create`。
- **设备与连接**：`/device/select`、`/connection-mode/set`、`/target/set`。
- **脚本与参数**：`/run-script/set`、`/attach-script/set`、`/extra-args/set`。
- **会话控制**：`/run`、`/stop`、`/attach`、`/stop-attach`。
- **日志读取**：
  - `/run-log/path`、`/attach-log/path`：返回路径 + 文件大小（`fileSize`/`sizeHuman`）。
  - `/run-log/content`、`/attach-log/content`：读取内容（支持 `maxBytes` 截断，截断信息在元数据中传递，不污染 content 字段）。
  - `/run-log/lines`、`/attach-log/lines`：按行读取（支持 `start`/`count` 参数，单次上限 2000 行）。

## 线程与边界（强约束）
- **UI 读写必须切回 EDT**：涉及 `ZaFridaRunPanel` / Swing 组件的操作，必须使用 `invokeLater` + 同步等待。
- **I/O 在后台线程执行**：日志文件读取、HTTP 请求处理不得阻塞 EDT。
- **只做编排，不做业务复制**：
  - 项目切换/创建：`ZaFridaProjectManager`
  - 会话状态：`ZaFridaSessionService`
  - Run/Attach 触发与字段读取：`ZaFridaRunPanel`

## 接口演进规则
- 新增字段优先”向后兼容追加”，避免删除/重命名既有键名。
- 变更路径、参数名、返回键名时，必须在注释和文档中给出迁移说明。
- 汇总接口禁止默认返回大体积日志内容；日志内容应通过独立接口按需读取。
