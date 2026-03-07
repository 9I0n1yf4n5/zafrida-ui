# CONTRIBUTORS

本文件用于记录社区贡献者及其贡献点（包括代码、需求、设计与关键思路）。

## 社区贡献者

- [Log](https://github.com/z2010643575)：
  提出 Frida 16/17 在部分 JS API 上存在差异的兼容需求，并给出了初始的替换思路。
  为了同时兼容Frida16/17, 本项目未直接合并其实现，
  而是基于该思路抽象出统一的插入期适配层 `com.zafrida.ui.util.FridaJsCompatibilityUtil`，
  用于在「插入 Snippet / 勾选 Template」时对 JS 片段做版本适配（保留模板与片段原文为 Frida16 风格，避免维护多份内容）。

- [214116734](https://github.com/214116734)：
  提交了“log 中文输出乱码”问题的修复思路：在 `FridaCliService.applyProjectPythonEnv(...)` 统一注入 Python 输出编码相关环境变量
  （`PYTHONIOENCODING=UTF-8`、`PYTHONUTF8=1`、`PYTHONUNBUFFERED=1`），从而覆盖 Run/Attach 等 Frida 命令路径并稳定中文日志输出。
