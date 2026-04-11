"""cmd_tap — perform tap/long-press/swipe on device."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def build_params(args: list[str]) -> dict:
    """Parse tap subcommand arguments into MCP params."""
    action = "tap"
    x = y = end_x = end_y = None
    xp = yp = end_xp = end_yp = None
    text = resource_id = content_desc = class_name = None
    duration = None

    i = 0
    while i < len(args):
        a = args[i]
        if a == "--action":    action = args[i + 1];       i += 2
        elif a == "--x":       x = float(args[i + 1]);     i += 2
        elif a == "--y":       y = float(args[i + 1]);     i += 2
        elif a == "--end-x":   end_x = float(args[i + 1]); i += 2
        elif a == "--end-y":   end_y = float(args[i + 1]); i += 2
        elif a == "--xp":      xp = float(args[i + 1]);    i += 2
        elif a == "--yp":      yp = float(args[i + 1]);    i += 2
        elif a == "--end-xp":  end_xp = float(args[i + 1]); i += 2
        elif a == "--end-yp":  end_yp = float(args[i + 1]); i += 2
        elif a == "--text":    text = args[i + 1];          i += 2
        elif a == "--id":      resource_id = args[i + 1];   i += 2
        elif a == "--desc":    content_desc = args[i + 1];  i += 2
        elif a == "--class":   class_name = args[i + 1];    i += 2
        elif a == "--duration": duration = float(args[i + 1]); i += 2
        else:
            print(f"Unknown option: {a}", file=sys.stderr)
            sys.exit(1)

    action_map = {"tap": "tap", "long-press": "longPress", "swipe": "swipe"}
    mcp_action = action_map.get(action)
    if not mcp_action:
        print(f"Unknown action: {action}", file=sys.stderr)
        sys.exit(1)

    # Validate swipe end coords
    if mcp_action == "swipe":
        if x is not None and (end_x is None or end_y is None):
            print("swipe requires --end-x and --end-y", file=sys.stderr)
            sys.exit(1)
        if xp is not None and (end_xp is None or end_yp is None):
            print("swipe requires --end-xp and --end-yp", file=sys.stderr)
            sys.exit(1)

    # Coordinate mode
    if x is not None and y is not None:
        result: dict = {"action": mcp_action, "x": x, "y": y}
        if end_x is not None:
            result["endX"] = end_x
            result["endY"] = end_y
        if duration is not None:
            result["duration"] = duration
        return result

    # Percent mode
    if xp is not None and yp is not None:
        result = {"action": mcp_action, "xPercent": xp, "yPercent": yp}
        if end_xp is not None:
            result["endXPercent"] = end_xp
            result["endYPercent"] = end_yp
        if duration is not None:
            result["duration"] = duration
        return result

    # Element mode
    if text or resource_id or content_desc:
        result = {"action": mcp_action}
        if text:
            result["text"] = text
        if resource_id:
            result["resourceId"] = resource_id
        if content_desc:
            result["contentDesc"] = content_desc
        if class_name:
            result["className"] = class_name
        return result

    print("tap requires a selector (--text/--id/--desc), coordinates (--x/--y), "
          "or percent coords (--xp/--yp)", file=sys.stderr)
    sys.exit(1)


def cmd_tap(args: list[str]) -> None:
    json_mode, remaining = jugglib.has_json_flag(args)
    extra = build_params(remaining)
    jugglib.simple_call("tap", json_mode=json_mode, extra_params=extra)
