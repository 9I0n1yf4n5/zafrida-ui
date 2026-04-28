#!/usr/bin/env python3
"""
ZAFrida 本地 HTTP API 命令行工具（独立版本）。

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


def api_call(
    base_url: str,
    endpoint: str,
    method: str = "GET",
    params: Dict[str, str] | None = None,
    timeout: float = 30.0,
) -> Tuple[int, Dict]:
    """发送 HTTP 请求并返回 (状态码, JSON 响应)。"""
    payload = params or {}
    url = f"{normalize_base_url(base_url)}{endpoint}"
    data_bytes = None
    headers = {"Accept": "application/json"}

    if method.upper() == "GET":
        if payload:
            url = f"{url}?{urllib.parse.urlencode(payload)}"
    else:
        encoded = urllib.parse.urlencode(payload).encode("utf-8")
        data_bytes = encoded
        headers["Content-Type"] = "application/x-www-form-urlencoded"

    request = urllib.request.Request(url=url, method=method.upper(), data=data_bytes, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode("utf-8", errors="replace")
            if not text:
                return response.status, {}
            return response.status, json.loads(text)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        if body:
            try:
                return exc.code, json.loads(body)
            except json.JSONDecodeError:
                pass
        return exc.code, {"ok": False, "status": exc.code, "message": body or str(exc)}
    except urllib.error.URLError as exc:
        return 0, {"ok": False, "status": 0, "message": str(exc)}


def add_common_flags(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--base-url",
        default=os.environ.get("ZAFRIDA_API_BASE", DEFAULT_BASE_URL),
        help="API 基础地址，例如 http://127.0.0.1:17839/zafrida/api/v1",
    )
    parser.add_argument("--compact", action="store_true", help="输出紧凑 JSON（无缩进）")


def print_result(status: int, payload: Dict, compact: bool) -> int:
    """输出 JSON 并根据响应状态返回退出码。"""
    if compact:
        print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    else:
        print(json.dumps(payload, ensure_ascii=False, indent=2))

    ok = payload.get("ok")
    if status == 0 or ok is False:
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="ZAFrida 本地 API 命令行工具")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # ── 状态与健康 ──
    subparsers.add_parser("health", help="健康检查")
    subparsers.add_parser("state", help="完整状态汇总（含日志文件大小）")

    # ── 项目管理 ──
    subparsers.add_parser("project-current", help="当前活跃项目")

    # ── 日志读取 ──
    subparsers.add_parser("run-log-path", help="获取 Run 日志路径和文件大小")
    subparsers.add_parser("attach-log-path", help="获取 Attach 日志路径和文件大小")

    run_log_content = subparsers.add_parser("run-log-content", help="读取 Run 日志内容")
    run_log_content.add_argument("--path", help="覆盖日志文件路径")
    run_log_content.add_argument("--max-bytes", type=int, default=0, help="仅读取末尾 N 字节（0=全部）")

    attach_log_content = subparsers.add_parser("attach-log-content", help="读取 Attach 日志内容")
    attach_log_content.add_argument("--path", help="覆盖日志文件路径")
    attach_log_content.add_argument("--max-bytes", type=int, default=0, help="仅读取末尾 N 字节（0=全部）")

    run_log_lines = subparsers.add_parser("run-log-lines", help="按行读取 Run 日志")
    run_log_lines.add_argument("--path", help="覆盖日志文件路径")
    run_log_lines.add_argument("--start", type=int, default=1, help="起始行号（1-based，默认 1）")
    run_log_lines.add_argument("--count", type=int, default=100, help="读取行数（默认 100，上限 2000）")

    attach_log_lines = subparsers.add_parser("attach-log-lines", help="按行读取 Attach 日志")
    attach_log_lines.add_argument("--path", help="覆盖日志文件路径")
    attach_log_lines.add_argument("--start", type=int, default=1, help="起始行号（1-based，默认 1）")
    attach_log_lines.add_argument("--count", type=int, default=100, help="读取行数（默认 100，上限 2000）")

    # ── 项目管理（写操作） ──
    project_select = subparsers.add_parser("project-select", help="切换活跃项目")
    project_select.add_argument("--name", required=True, help="项目名称")

    project_create = subparsers.add_parser("project-create", help="新建项目")
    project_create.add_argument("--name", required=True, help="项目名称")
    project_create.add_argument("--platform", default="android", choices=["android", "ios"], help="目标平台")

    # ── 设备与连接 ──
    device_select = subparsers.add_parser("device-select", help="选择设备")
    group = device_select.add_mutually_exclusive_group(required=True)
    group.add_argument("--id", help="设备 ID")
    group.add_argument("--host", help="远程设备地址")

    mode_set = subparsers.add_parser("mode-set", help="设置连接模式")
    mode_set.add_argument("--mode", required=True, choices=["usb", "remote", "gadget"], help="连接模式")
    mode_set.add_argument("--host", help="远程主机地址")
    mode_set.add_argument("--port", type=int, help="远程端口")

    # ── 脚本与参数 ──
    target_set = subparsers.add_parser("target-set", help="设置目标应用包名")
    target_set.add_argument("--target", default="", help="目标包名")

    run_script_set = subparsers.add_parser("run-script-set", help="设置 Run 脚本路径")
    run_script_set.add_argument("--path", required=True, help="脚本绝对路径")

    attach_script_set = subparsers.add_parser("attach-script-set", help="设置 Attach 脚本路径")
    attach_script_set.add_argument("--path", required=True, help="脚本绝对路径")

    extra_set = subparsers.add_parser("extra-set", help="设置额外命令行参数")
    extra_set.add_argument("--value", default="", help="参数值")

    # ── 会话控制 ──
    subparsers.add_parser("run", help="启动 Run 会话")
    subparsers.add_parser("stop", help="停止 Run 会话")
    subparsers.add_parser("attach", help="启动 Attach 会话")
    subparsers.add_parser("stop-attach", help="停止 Attach 会话")

    add_common_flags(parser)
    args = parser.parse_args()

    command = args.command
    method = "GET"
    endpoint = ""
    params: Dict[str, str] = {}

    if command == "health":
        endpoint = "/health"
    elif command == "state":
        endpoint = "/state"
    elif command == "project-current":
        endpoint = "/project/current"
    elif command == "project-select":
        method = "POST"
        endpoint = "/project/select"
        params["name"] = args.name
    elif command == "project-create":
        method = "POST"
        endpoint = "/project/create"
        params["name"] = args.name
        params["platform"] = args.platform
    elif command == "device-select":
        method = "POST"
        endpoint = "/device/select"
        if args.id:
            params["id"] = args.id
        if args.host:
            params["host"] = args.host
    elif command == "mode-set":
        method = "POST"
        endpoint = "/connection-mode/set"
        params["mode"] = args.mode
        if args.host:
            params["host"] = args.host
        if args.port is not None:
            params["port"] = str(args.port)
    elif command == "target-set":
        method = "POST"
        endpoint = "/target/set"
        params["target"] = args.target
    elif command == "run-script-set":
        method = "POST"
        endpoint = "/run-script/set"
        params["path"] = args.path
    elif command == "attach-script-set":
        method = "POST"
        endpoint = "/attach-script/set"
        params["path"] = args.path
    elif command == "extra-set":
        method = "POST"
        endpoint = "/extra-args/set"
        params["value"] = args.value
    elif command == "run":
        method = "POST"
        endpoint = "/run"
    elif command == "stop":
        method = "POST"
        endpoint = "/stop"
    elif command == "attach":
        method = "POST"
        endpoint = "/attach"
    elif command == "stop-attach":
        method = "POST"
        endpoint = "/stop-attach"
    elif command == "run-log-path":
        endpoint = "/run-log/path"
    elif command == "attach-log-path":
        endpoint = "/attach-log/path"
    elif command == "run-log-content":
        endpoint = "/run-log/content"
        if args.path:
            params["path"] = args.path
        if args.max_bytes > 0:
            params["maxBytes"] = str(args.max_bytes)
    elif command == "attach-log-content":
        endpoint = "/attach-log/content"
        if args.path:
            params["path"] = args.path
        if args.max_bytes > 0:
            params["maxBytes"] = str(args.max_bytes)
    elif command == "run-log-lines":
        endpoint = "/run-log/lines"
        if args.path:
            params["path"] = args.path
        if args.start > 1:
            params["start"] = str(args.start)
        if args.count != 100:
            params["count"] = str(args.count)
    elif command == "attach-log-lines":
        endpoint = "/attach-log/lines"
        if args.path:
            params["path"] = args.path
        if args.start > 1:
            params["start"] = str(args.start)
        if args.count != 100:
            params["count"] = str(args.count)
    else:
        print(f"未知命令: {command}", file=sys.stderr)
        return 2

    status, payload = api_call(args.base_url, endpoint, method=method, params=params)
    return print_result(status, payload, args.compact)


if __name__ == "__main__":
    sys.exit(main())
