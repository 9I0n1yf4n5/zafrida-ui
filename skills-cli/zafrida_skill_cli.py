#!/usr/bin/env python3
"""
ZAFrida local HTTP API CLI.

Default base URL:
    http://127.0.0.1:17839/zafrida/api/v1

You can override with:
    --base-url ...
or env:
    ZAFRIDA_API_BASE=...
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
        help="API base URL, e.g. http://127.0.0.1:17839/zafrida/api/v1",
    )
    parser.add_argument("--compact", action="store_true", help="Print compact JSON")


def print_result(status: int, payload: Dict, compact: bool) -> int:
    if compact:
        print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    else:
        print(json.dumps(payload, ensure_ascii=False, indent=2))

    ok = payload.get("ok")
    if status == 0 or ok is False:
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="ZAFrida local API CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # Read endpoints
    subparsers.add_parser("health")
    subparsers.add_parser("state")
    subparsers.add_parser("project-current")
    subparsers.add_parser("run-log-path")
    subparsers.add_parser("attach-log-path")

    run_log_content = subparsers.add_parser("run-log-content")
    run_log_content.add_argument("--path", help="Override log file path")
    run_log_content.add_argument("--max-bytes", type=int, default=0, help="Read only last N bytes (0=full)")

    attach_log_content = subparsers.add_parser("attach-log-content")
    attach_log_content.add_argument("--path", help="Override log file path")
    attach_log_content.add_argument("--max-bytes", type=int, default=0, help="Read only last N bytes (0=full)")

    # Mutating endpoints
    project_select = subparsers.add_parser("project-select")
    project_select.add_argument("--name", required=True)

    project_create = subparsers.add_parser("project-create")
    project_create.add_argument("--name", required=True)
    project_create.add_argument("--platform", default="android", choices=["android", "ios"])

    device_select = subparsers.add_parser("device-select")
    group = device_select.add_mutually_exclusive_group(required=True)
    group.add_argument("--id")
    group.add_argument("--host")

    mode_set = subparsers.add_parser("mode-set")
    mode_set.add_argument("--mode", required=True, choices=["usb", "remote", "gadget"])
    mode_set.add_argument("--host")
    mode_set.add_argument("--port", type=int)

    target_set = subparsers.add_parser("target-set")
    target_set.add_argument("--target", default="")

    run_script_set = subparsers.add_parser("run-script-set")
    run_script_set.add_argument("--path", required=True)

    attach_script_set = subparsers.add_parser("attach-script-set")
    attach_script_set.add_argument("--path", required=True)

    extra_set = subparsers.add_parser("extra-set")
    extra_set.add_argument("--value", default="")

    subparsers.add_parser("run")
    subparsers.add_parser("stop")
    subparsers.add_parser("attach")
    subparsers.add_parser("stop-attach")

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
    else:
        print(f"Unknown command: {command}", file=sys.stderr)
        return 2

    status, payload = api_call(args.base_url, endpoint, method=method, params=params)
    return print_result(status, payload, args.compact)


if __name__ == "__main__":
    sys.exit(main())

