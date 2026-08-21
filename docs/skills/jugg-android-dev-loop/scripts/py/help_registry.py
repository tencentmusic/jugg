"""Shared help text for the Jugg CLI."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class OptionHelp:
    """Describes one CLI option and its direct MCP parameter mapping."""

    flags: tuple[str, ...]
    description: str
    value: str = ""
    mcp_param: str = ""
    required: bool = False


@dataclass(frozen=True)
class CommandHelp:
    """Describes user-facing help for one Jugg CLI subcommand."""

    name: str
    summary: str
    usage: str
    options: tuple[OptionHelp, ...] = ()
    examples: tuple[str, ...] = ()


COMMAND_HELP: dict[str, CommandHelp] = {
    "version": CommandHelp(
        "version",
        "Show CLI version and plugin version from all initialized projects.",
        "jugg version",
    ),
    "compile": CommandHelp(
        "compile",
        "Compile modified sources without deploying.",
        "jugg [--if-compiling wait|interrupt] compile",
        (
            OptionHelp(
                ("--if-compiling", "--ifCompiling"),
                "When another compile is running: wait (default) or interrupt. CLI-only global flag.",
                "wait|interrupt",
            ),
        ),
    ),
    "deploy": CommandHelp(
        "deploy",
        "Compile and deploy to device, then wait for completion.",
        "jugg [--if-compiling wait|interrupt] deploy [--always-restart-app <true|false>]",
        (
            OptionHelp(
                ("--if-compiling", "--ifCompiling"),
                "When another compile is running: wait (default) or interrupt. CLI-only global flag.",
                "wait|interrupt",
            ),
            OptionHelp(
                ("--always-restart-app", "--alwaysRestartApp"),
                "Restart the app after deploy; pass false to allow hot reload.",
                "<true|false>",
                "alwaysRestartApp",
            ),
        ),
        ("jugg deploy --always-restart-app false",),
    ),
    "gradle-build": CommandHelp(
        "gradle-build",
        "Force a Gradle build and wait for completion.",
        "jugg [--if-compiling wait|interrupt] gradle-build",
        (
            OptionHelp(
                ("--if-compiling", "--ifCompiling"),
                "When another compile is running: wait (default) or interrupt. CLI-only global flag.",
                "wait|interrupt",
            ),
        ),
    ),
    "clean-reinstall": CommandHelp(
        "clean-reinstall",
        "Clear app data and reinstall APK.",
        "jugg clean-reinstall",
    ),
    "restart": CommandHelp(
        "restart",
        "Restart the app.",
        "jugg restart",
    ),
    "instrument": CommandHelp(
        "instrument",
        "Run androidTest from a source file anchor.",
        "jugg [--if-compiling wait|interrupt] instrument --source-path <path> [--class <fqcn>] [--method <name>] "
        "[--runner <fqcn>] [--extras <k=v;k2=v2>]",
        (
            OptionHelp(
                ("--if-compiling", "--ifCompiling"),
                "When another compile is running: wait (default) or interrupt. CLI-only global flag.",
                "wait|interrupt",
            ),
            OptionHelp(
                ("--source-path", "--sourcePath"),
                "androidTest source file path.",
                "<path>",
                "sourcePath",
                True,
            ),
            OptionHelp(
                ("--class",),
                "Test class name when it cannot be inferred uniquely.",
                "<fqcn>",
                "class",
            ),
            OptionHelp(("--method",), "Test method name.", "<name>", "method"),
            OptionHelp(("--runner",), "Instrumentation runner override.", "<fqcn>", "runner"),
            OptionHelp(("--extras",), "Semicolon-separated instrumentation extras.", "<k=v;k2=v2>", "extras"),
        ),
        (
            "jugg instrument --source-path app/src/androidTest/java/com/example/FooTest.kt",
            "jugg instrument --source-path app/src/androidTest/java/com/example/FooTest.kt --method testLogin",
        ),
    ),
    "status": CommandHelp(
        "status",
        "Show current deploy state and uncompiled file summary.",
        "jugg status [--refresh-changes <true|false>] [--full-info <true|false>]",
        (
            OptionHelp(
                ("--refresh-changes", "--refreshChanges"),
                "Refresh git-tracked changed files before reading status. Default is true.",
                "<true|false>",
                "refreshChanges",
            ),
            OptionHelp(
                ("--full-info", "--fullInfo"),
                "Return full status information, including all pending file paths. Default is false.",
                "<true|false>",
                "fullInfo",
            ),
        ),
    ),
    "layout-dump": CommandHelp(
        "layout-dump",
        "Export UI hierarchy to an HTML file.",
        "jugg layout-dump [--root-layout <id>] [--include-gone] [--all-windows]",
        (
            OptionHelp(
                ("--root-layout", "--rootLayout"),
                "Only dump the subtree under this node.",
                "<id>",
                "rootLayout",
            ),
            OptionHelp(("--include-gone", "--includeGone"), "Include GONE nodes.", mcp_param="includeGone"),
            OptionHelp(("--all-windows", "--allWindows"), "Dump all windows.", mcp_param="allWindows"),
        ),
    ),
    "view-locate": CommandHelp(
        "view-locate",
        "Find live UI element positions, bounds, and source locations.",
        "jugg view-locate (--text <text> | --resource-id <id> | --content-desc <desc> | "
        "--class-name <class>) [--visible-only <true|false>] [--max-results <1..100>]",
        (
            OptionHelp(("--text",), "Exact visible-text selector.", "<text>", "target.text"),
            OptionHelp(("--resource-id", "--resourceId"), "Exact full or short resource ID.", "<id>", "target.resourceId"),
            OptionHelp(
                ("--content-desc", "--contentDesc"),
                "Exact content-description selector.",
                "<desc>",
                "target.contentDesc",
            ),
            OptionHelp(
                ("--class-name", "--className"),
                "Exact full or simple class name.",
                "<class>",
                "target.className",
            ),
            OptionHelp(
                ("--visible-only", "--visibleOnly"),
                "Only return visible nodes (default: true).",
                "<true|false>",
                "visibleOnly",
            ),
            OptionHelp(
                ("--max-results", "--maxResults"),
                "Maximum returned matches (default: 10).",
                "<1..100>",
                "maxResults",
            ),
        ),
    ),
    "view-inspect": CommandHelp(
        "view-inspect",
        "Evaluate read-only View expressions (getters, Kotlin properties, or public fields).",
        "jugg view-inspect (--text <text> | --resource-id <id> | --content-desc <desc>) "
        "[--class-name <class>] <expr1> [<expr2> ...]",
        (
            OptionHelp(("--text",), "Match by visible text.", "<text>", "target.text"),
            OptionHelp(("--resource-id", "--resourceId"), "Match by resource id.", "<id>", "target.resourceId"),
            OptionHelp(
                ("--content-desc", "--contentDesc"),
                "Match by content description.",
                "<desc>",
                "target.contentDesc",
            ),
            OptionHelp(("--class-name", "--className"), "Additional class-name filter.", "<class>", "target.className"),
        ),
    ),
    "tap": CommandHelp(
        "tap",
        "Perform tap, long-press, or swipe on device.",
        "jugg tap [--action tap|long-press|swipe] "
        "(--x <n> --y <n> | --x-percent <n> --y-percent <n> | --text <text> | "
        "--resource-id <id> | --content-desc <desc>) [--duration <ms>]",
        (
            OptionHelp(("--action",), "Gesture action; default is tap.", "<action>", "action"),
            OptionHelp(("--x",), "Start x coordinate.", "<n>", "x"),
            OptionHelp(("--y",), "Start y coordinate.", "<n>", "y"),
            OptionHelp(("--end-x", "--endX"), "Swipe end x coordinate.", "<n>", "endX"),
            OptionHelp(("--end-y", "--endY"), "Swipe end y coordinate.", "<n>", "endY"),
            OptionHelp(("--x-percent", "--xPercent"), "Start x percent.", "<n>", "xPercent"),
            OptionHelp(("--y-percent", "--yPercent"), "Start y percent.", "<n>", "yPercent"),
            OptionHelp(("--end-x-percent", "--endXPercent"), "Swipe end x percent.", "<n>", "endXPercent"),
            OptionHelp(("--end-y-percent", "--endYPercent"), "Swipe end y percent.", "<n>", "endYPercent"),
            OptionHelp(("--text",), "Match target element by text.", "<text>", "text"),
            OptionHelp(("--resource-id", "--resourceId"), "Match target element by resource id.", "<id>", "resourceId"),
            OptionHelp(
                ("--content-desc", "--contentDesc"),
                "Match target element by content description.",
                "<desc>",
                "contentDesc",
            ),
            OptionHelp(("--class-name", "--className"), "Additional element class-name filter.", "<class>", "className"),
            OptionHelp(("--duration",), "Gesture duration in milliseconds.", "<ms>", "duration"),
        ),
    ),
    "devices": CommandHelp(
        "devices",
        "List connected devices.",
        "jugg devices",
    ),
    "activity-stack": CommandHelp(
        "activity-stack",
        "Show current Activity stack.",
        "jugg activity-stack",
    ),
    "ssh-info": CommandHelp(
        "ssh-info",
        "Request remote SSH troubleshooting info when remote compile is enabled.",
        "jugg ssh-info --reason <reason>",
        (OptionHelp(("--reason",), "Reason for requesting SSH info.", "<reason>", "reason", True),),
    ),
    "wait-logs": CommandHelp(
        "wait-logs",
        "Block until app log marker, crash, or timeout.",
        "jugg wait-logs --marker <regex> [--tags <tag1,tag2>] [--timeout-ms <ms>]",
        (
            OptionHelp(("--marker",), "Java Pattern regex marker to wait for.", "<regex>", "marker", True),
            OptionHelp(("--tags",), "Comma-separated log tag allowlist.", "<tag1,tag2>", "tags"),
            OptionHelp(("--timeout-ms", "--timeoutMs"), "Hard timeout in milliseconds.", "<ms>", "timeoutMs"),
        ),
    ),
}


def format_command_help(command: CommandHelp) -> str:
    lines = [
        f"{command.name}: {command.summary}",
        "",
        f"Usage: {command.usage}",
    ]
    if command.options:
        lines.extend(["", "Options:"])
        for option in command.options:
            required = " (required)" if option.required else ""
            value = f" {option.value}" if option.value else ""
            flags = ", ".join(f"{flag}{value}" for flag in option.flags)
            mapping = f" MCP: {option.mcp_param}." if option.mcp_param else ""
            lines.append(f"  {flags:<42} {option.description}{required}{mapping}")
    if command.examples:
        lines.extend(["", "Examples:"])
        lines.extend(f"  {example}" for example in command.examples)
    return "\n".join(lines) + "\n"
