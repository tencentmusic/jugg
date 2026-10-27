"""Edit shared global settings through the selected Jugg Runtime."""
from __future__ import annotations

import json
import sys
import jugglib


def _usage_error() -> None:
    print("Usage: jugg settings list|get <key>|set <key> <value> [--confirm true|false]|reset <key> [--confirm true|false]", file=sys.stderr)
    sys.exit(2)


def build_params(args: list[str]) -> dict:
    if not args:
        args = ["list"]
    action = args[0]
    counts = {"list": 1, "get": 2, "set": 3, "reset": 2}
    count = counts.get(action)
    if count is None or len(args) < count:
        _usage_error()
    params = {"action": action}
    if count >= 2:
        params["key"] = args[1]
    if action == "set":
        params["value"] = args[2]
    tail = args[count:]
    if tail:
        if action not in ("set", "reset") or len(tail) != 2 or tail[0] != "--confirm" or tail[1] not in ("true", "false"):
            _usage_error()
        params["confirm"] = tail[1] == "true"
    return params


def cmd_settings(args: list[str]) -> None:
    params = build_params(args)
    port = jugglib.resolve_global_port()
    result = jugglib.extract_structured(jugglib.raw_call(port, "settings", params))
    if jugglib.json_mode:
        print(json.dumps(result, ensure_ascii=False))
    elif result.get("status") == "OK":
        data = result.get("data", {})
        if params["action"] == "list":
            for item in data.get("items", []):
                print(f"{item['key']}: {json.dumps(item['value'], ensure_ascii=False)} — {item['description']}")
        else:
            print(json.dumps(data, ensure_ascii=False, indent=2))
    else:
        confirmation = result.get("data", {}).get("confirmation")
        if confirmation:
            print(f"{confirmation['title']}\n{confirmation['message']}\n"
                  f"{confirmation['confirmLabel']} / {confirmation['cancelLabel']}\nPass --confirm true to accept.", file=sys.stderr)
        else:
            print(result.get("message", "Settings request failed."), file=sys.stderr)
    if result.get("status") != "OK":
        sys.exit(1)
