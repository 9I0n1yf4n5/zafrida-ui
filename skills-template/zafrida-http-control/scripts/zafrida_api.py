#!/usr/bin/env python3
"""
ZAFrida 本地 HTTP API 命令行助手（项目模板内置版本）。

默认地址：http://127.0.0.1:17839/zafrida/api/v1
可通过 --base-url 或环境变量 ZAFRIDA_API_BASE 覆盖。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Dict, Tuple


DEFAULT_BASE_URL = "http://127.0.0.1:17839/zafrida/api/v1"


def normalize_base_url(raw: str) -> str:
    text = raw.strip()
    if text.endswith("/"):
        text = text[:-1]
    return text


def call_api(base_url: str, endpoint: str, method: str, params: Dict[str, str]) -> Tuple[int, Dict]:
    """发送 HTTP 请求并返回 (状态码, JSON 响应)。"""
    url = f"{normalize_base_url(base_url)}{endpoint}"
    data = None
    headers = {"Accept": "application/json"}

    if method == "GET":
        if params:
            url = f"{url}?{urllib.parse.urlencode(params)}"
    else:
        data = urllib.parse.urlencode(params).encode("utf-8")
        headers["Content-Type"] = "application/x-www-form-urlencoded"

    request = urllib.request.Request(url=url, method=method, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode("utf-8", errors="replace")
            if not text:
                return response.status, {}
            return response.status, json.loads(text)
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        if raw:
            try:
                return exc.code, json.loads(raw)
            except json.JSONDecodeError:
                pass
        return exc.code, {"ok": False, "status": exc.code, "message": raw or str(exc)}
    except urllib.error.URLError as exc:
        return 0, {"ok": False, "status": 0, "message": str(exc)}


def add_common_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--base-url",
        default=os.environ.get("ZAFRIDA_API_BASE", DEFAULT_BASE_URL),
        help="API 基础地址",
    )
    parser.add_argument("--compact", action="store_true", help="输出紧凑 JSON（无缩进）")


def print_json(payload: Dict, compact: bool) -> None:
    if compact:
        print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    else:
        print(json.dumps(payload, ensure_ascii=False, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser(description="ZAFrida API 命令行助手")
    sub = parser.add_subparsers(dest="cmd", required=True)

    # ── 状态与健康 ──
    sub.add_parser("health", help="健康检查")
    sub.add_parser("state", help="完整状态汇总（含日志文件大小）")
    sub.add_parser("diagnostics", help="运行环境诊断（6 项检查）")

    # ── 项目管理 ──
    sub.add_parser("project-current", help="当前活跃项目")

    project_select = sub.add_parser("project-select", help="切换活跃项目")
    project_select.add_argument("--name", required=True, help="项目名称")

    project_create = sub.add_parser("project-create", help="新建项目")
    project_create.add_argument("--name", required=True, help="项目名称")
    project_create.add_argument("--platform", default="android", choices=["android", "ios"], help="目标平台")

    # ── 设备与进程 ──
    sub.add_parser("devices", help="列出所有已连接设备")

    processes_cmd = sub.add_parser("processes", help="列出当前设备的进程/应用")
    processes_cmd.add_argument("--scope", default="running", choices=["running", "apps", "installed"], help="列表范围（默认 running）")

    device_select = sub.add_parser("device-select", help="选择设备")
    device_group = device_select.add_mutually_exclusive_group(required=True)
    device_group.add_argument("--id", help="设备 ID")
    device_group.add_argument("--host", help="远程设备地址")

    mode_set = sub.add_parser("mode-set", help="设置连接模式")
    mode_set.add_argument("--mode", required=True, choices=["usb", "remote", "gadget"], help="连接模式")
    mode_set.add_argument("--host", help="远程主机地址")
    mode_set.add_argument("--port", type=int, help="远程端口")

    # ── 脚本与参数 ──
    target_set = sub.add_parser("target-set", help="设置目标应用包名")
    target_set.add_argument("--target", default="", help="目标包名")

    run_script_set = sub.add_parser("run-script-set", help="设置 Run 脚本路径")
    run_script_set.add_argument("--path", required=True, help="脚本绝对路径")

    attach_script_set = sub.add_parser("attach-script-set", help="设置 Attach 脚本路径")
    attach_script_set.add_argument("--path", required=True, help="脚本绝对路径")

    extra_set = sub.add_parser("extra-set", help="设置额外命令行参数")
    extra_set.add_argument("--value", default="", help="参数值")

    # ── ADB 操作 ──
    adb_fs = sub.add_parser("adb-force-stop", help="ADB 强制停止应用")
    adb_fs.add_argument("--target", help="目标包名（默认使用当前 target）")

    adb_oa = sub.add_parser("adb-open-app", help="ADB 启动应用")
    adb_oa.add_argument("--target", help="目标包名（默认使用当前 target）")

    # ── 会话控制 ──
    sub.add_parser("run", help="启动 Run 会话")
    sub.add_parser("stop", help="停止 Run 会话")
    sub.add_parser("attach", help="启动 Attach 会话")
    sub.add_parser("stop-attach", help="停止 Attach 会话")

    # ── 控制台 ──
    console_clear = sub.add_parser("console-clear", help="清空控制台")
    console_clear.add_argument("--type", default="run", choices=["run", "attach"], help="控制台类型（默认 run）")

    # ── 日志读取 ──
    sub.add_parser("run-log-path", help="获取 Run 日志路径和文件大小")
    sub.add_parser("attach-log-path", help="获取 Attach 日志路径和文件大小")

    run_log_content = sub.add_parser("run-log-content", help="读取 Run 日志内容")
    run_log_content.add_argument("--path", help="覆盖日志文件路径")
    run_log_content.add_argument("--max-bytes", type=int, default=0, help="仅读取末尾 N 字节（0=全部）")

    attach_log_content = sub.add_parser("attach-log-content", help="读取 Attach 日志内容")
    attach_log_content.add_argument("--path", help="覆盖日志文件路径")
    attach_log_content.add_argument("--max-bytes", type=int, default=0, help="仅读取末尾 N 字节（0=全部）")

    run_log_lines = sub.add_parser("run-log-lines", help="按行读取 Run 日志")
    run_log_lines.add_argument("--path", help="覆盖日志文件路径")
    run_log_lines.add_argument("--start", type=int, default=1, help="起始行号（1-based，默认 1）")
    run_log_lines.add_argument("--count", type=int, default=100, help="读取行数（默认 100，上限 2000）")

    attach_log_lines = sub.add_parser("attach-log-lines", help="按行读取 Attach 日志")
    attach_log_lines.add_argument("--path", help="覆盖日志文件路径")
    attach_log_lines.add_argument("--start", type=int, default=1, help="起始行号（1-based，默认 1）")
    attach_log_lines.add_argument("--count", type=int, default=100, help="读取行数（默认 100，上限 2000）")

    add_common_args(parser)
    args = parser.parse_args()

    cmd = args.cmd
    method = "GET"
    endpoint = ""
    params: Dict[str, str] = {}

    if cmd == "health":
        endpoint = "/health"
    elif cmd == "state":
        endpoint = "/state"
    elif cmd == "diagnostics":
        endpoint = "/diagnostics"
    elif cmd == "devices":
        endpoint = "/devices"
    elif cmd == "processes":
        endpoint = "/processes"
        if args.scope != "running":
            params["scope"] = args.scope
    elif cmd == "project-current":
        endpoint = "/project/current"
    elif cmd == "project-select":
        method = "POST"
        endpoint = "/project/select"
        params["name"] = args.name
    elif cmd == "project-create":
        method = "POST"
        endpoint = "/project/create"
        params["name"] = args.name
        params["platform"] = args.platform
    elif cmd == "device-select":
        method = "POST"
        endpoint = "/device/select"
        if args.id:
            params["id"] = args.id
        if args.host:
            params["host"] = args.host
    elif cmd == "mode-set":
        method = "POST"
        endpoint = "/connection-mode/set"
        params["mode"] = args.mode
        if args.host:
            params["host"] = args.host
        if args.port is not None:
            params["port"] = str(args.port)
    elif cmd == "target-set":
        method = "POST"
        endpoint = "/target/set"
        params["target"] = args.target
    elif cmd == "run-script-set":
        method = "POST"
        endpoint = "/run-script/set"
        params["path"] = args.path
    elif cmd == "attach-script-set":
        method = "POST"
        endpoint = "/attach-script/set"
        params["path"] = args.path
    elif cmd == "extra-set":
        method = "POST"
        endpoint = "/extra-args/set"
        params["value"] = args.value
    elif cmd == "adb-force-stop":
        method = "POST"
        endpoint = "/adb/force-stop"
        if args.target:
            params["target"] = args.target
    elif cmd == "adb-open-app":
        method = "POST"
        endpoint = "/adb/open-app"
        if args.target:
            params["target"] = args.target
    elif cmd == "console-clear":
        method = "POST"
        endpoint = "/console/clear"
        if args.type != "run":
            params["type"] = args.type
    elif cmd == "run":
        method = "POST"
        endpoint = "/run"
    elif cmd == "stop":
        method = "POST"
        endpoint = "/stop"
    elif cmd == "attach":
        method = "POST"
        endpoint = "/attach"
    elif cmd == "stop-attach":
        method = "POST"
        endpoint = "/stop-attach"
    elif cmd == "run-log-path":
        endpoint = "/run-log/path"
    elif cmd == "attach-log-path":
        endpoint = "/attach-log/path"
    elif cmd == "run-log-content":
        endpoint = "/run-log/content"
        if args.path:
            params["path"] = args.path
        if args.max_bytes > 0:
            params["maxBytes"] = str(args.max_bytes)
    elif cmd == "attach-log-content":
        endpoint = "/attach-log/content"
        if args.path:
            params["path"] = args.path
        if args.max_bytes > 0:
            params["maxBytes"] = str(args.max_bytes)
    elif cmd == "run-log-lines":
        endpoint = "/run-log/lines"
        if args.path:
            params["path"] = args.path
        if args.start > 1:
            params["start"] = str(args.start)
        if args.count != 100:
            params["count"] = str(args.count)
    elif cmd == "attach-log-lines":
        endpoint = "/attach-log/lines"
        if args.path:
            params["path"] = args.path
        if args.start > 1:
            params["start"] = str(args.start)
        if args.count != 100:
            params["count"] = str(args.count)
    else:
        print(f"未知命令: {cmd}", file=sys.stderr)
        return 2

    status, payload = call_api(args.base_url, endpoint, method, params)
    print_json(payload, args.compact)

    ok = payload.get("ok")
    if status == 0 or ok is False:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
