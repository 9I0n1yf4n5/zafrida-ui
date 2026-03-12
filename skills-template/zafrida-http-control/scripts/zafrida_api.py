#!/usr/bin/env python3
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
        help="API base URL",
    )
    parser.add_argument("--compact", action="store_true", help="Print compact JSON")


def print_json(payload: Dict, compact: bool) -> None:
    if compact:
        print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    else:
        print(json.dumps(payload, ensure_ascii=False, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser(description="ZAFrida API helper")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("health")
    sub.add_parser("state")
    sub.add_parser("project-current")

    project_select = sub.add_parser("project-select")
    project_select.add_argument("--name", required=True)

    project_create = sub.add_parser("project-create")
    project_create.add_argument("--name", required=True)
    project_create.add_argument("--platform", default="android", choices=["android", "ios"])

    device_select = sub.add_parser("device-select")
    device_group = device_select.add_mutually_exclusive_group(required=True)
    device_group.add_argument("--id")
    device_group.add_argument("--host")

    mode_set = sub.add_parser("mode-set")
    mode_set.add_argument("--mode", required=True, choices=["usb", "remote", "gadget"])
    mode_set.add_argument("--host")
    mode_set.add_argument("--port", type=int)

    target_set = sub.add_parser("target-set")
    target_set.add_argument("--target", default="")

    run_script_set = sub.add_parser("run-script-set")
    run_script_set.add_argument("--path", required=True)

    attach_script_set = sub.add_parser("attach-script-set")
    attach_script_set.add_argument("--path", required=True)

    extra_set = sub.add_parser("extra-set")
    extra_set.add_argument("--value", default="")

    sub.add_parser("run")
    sub.add_parser("stop")
    sub.add_parser("attach")
    sub.add_parser("stop-attach")
    sub.add_parser("run-log-path")
    sub.add_parser("attach-log-path")

    run_log_content = sub.add_parser("run-log-content")
    run_log_content.add_argument("--path")
    run_log_content.add_argument("--max-bytes", type=int, default=0)

    attach_log_content = sub.add_parser("attach-log-content")
    attach_log_content.add_argument("--path")
    attach_log_content.add_argument("--max-bytes", type=int, default=0)

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
    else:
        print(f"Unknown command: {cmd}", file=sys.stderr)
        return 2

    status, payload = call_api(args.base_url, endpoint, method, params)
    print_json(payload, args.compact)

    ok = payload.get("ok")
    if status == 0 or ok is False:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

