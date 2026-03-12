---
name: zafrida-http-control
description: Control ZAFrida in PyCharm through local HTTP APIs. Supports project query/switch/create, device and connection mode setup, target/script/extra args setup, run/attach/stop actions, run/attach log path, log content reading, and a summary state endpoint.
---

# ZAFrida HTTP Control

Use this skill when the user wants to automate ZAFrida from Codex/scripts without clicking the UI.

## Preconditions

- ZAFrida plugin is installed and the ToolWindow has been opened at least once.
- Local API is reachable (default: `http://127.0.0.1:17839/zafrida/api/v1`).
- If needed, set `ZAFRIDA_API_BASE` to a custom base URL.

## Command Runner

Use the bundled script:

- `python3 scripts/zafrida_api.py health`
- `python3 scripts/zafrida_api.py state`
- `python3 scripts/zafrida_api.py project-current`
- `python3 scripts/zafrida_api.py project-select --name demo`
- `python3 scripts/zafrida_api.py project-create --name app1 --platform android`
- `python3 scripts/zafrida_api.py mode-set --mode remote --host 127.0.0.1 --port 14725`
- `python3 scripts/zafrida_api.py device-select --id usb`
- `python3 scripts/zafrida_api.py target-set --target com.demo.app`
- `python3 scripts/zafrida_api.py run-script-set --path /abs/path/run.js`
- `python3 scripts/zafrida_api.py attach-script-set --path /abs/path/attach.js`
- `python3 scripts/zafrida_api.py extra-set --value \"--realm=emulated\"`
- `python3 scripts/zafrida_api.py run`
- `python3 scripts/zafrida_api.py stop`
- `python3 scripts/zafrida_api.py attach`
- `python3 scripts/zafrida_api.py stop-attach`
- `python3 scripts/zafrida_api.py run-log-path`
- `python3 scripts/zafrida_api.py run-log-content --max-bytes 200000`
- `python3 scripts/zafrida_api.py attach-log-path`
- `python3 scripts/zafrida_api.py attach-log-content --max-bytes 200000`

## Recommended Workflow

1. Call `state` first to capture current context (project/device/paths/status).
2. Configure only required fields (`project-select`, `mode-set`, scripts, target, extra args).
3. Trigger `run` or `attach`.
4. Read `run-log-path` or `attach-log-path` for large log processing by external tools.
5. Use `run-log-content` / `attach-log-content` only for targeted slices (`--max-bytes`).

## Notes

- The summary endpoint intentionally excludes large log content.
- For large logs, prefer using returned path with file tools/search workflows.
- All responses are JSON with `ok/status/message/data`.

