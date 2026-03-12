# `com.zafrida.ui.api` — 本地 HTTP API（AGENTS）

本模块提供 ZAFrida 的本地可编排接口，主要供 skills/CLI 自动化调用。

## 设计目标
- 仅开放本地环回地址（`127.0.0.1`），避免暴露到公网。
- 把外部调用映射到现有 `ui/`、`fridaproject/`、`session/` 能力，禁止平行实现。
- 接口返回结构化 JSON，便于脚本与工具链消费。

## 线程与边界（强约束）
- **UI 读写必须切回 EDT**：涉及 `ZaFridaRunPanel` / Swing 组件的操作，必须使用 `invokeLater` + 同步等待。
- **I/O 在后台线程执行**：日志文件读取、HTTP 请求处理不得阻塞 EDT。
- **只做编排，不做业务复制**：
  - 项目切换/创建：`ZaFridaProjectManager`
  - 会话状态：`ZaFridaSessionService`
  - Run/Attach 触发与字段读取：`ZaFridaRunPanel`

## 接口演进规则
- 新增字段优先“向后兼容追加”，避免删除/重命名既有键名。
- 变更路径、参数名、返回键名时，必须在注释和文档中给出迁移说明。
- 汇总接口禁止默认返回大体积日志内容；日志内容应通过独立接口按需读取。
