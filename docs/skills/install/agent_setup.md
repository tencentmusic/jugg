# Agent Setup Guide (Human Manual)

The `jugg-android-dev-loop` skill directory is located next to this file.

## Install Skill

Copy `jugg-android-dev-loop` to your AI client's skills directory:

| Client      | Skills directory                                    |
|-------------|-----------------------------------------------------|
| Codex       | `~/.codex/skills/`                                  |
| Claude Code | `~/.claude/skills/` (or `~/.config/claude/skills/`) |
| Gemini CLI  | `~/.gemini/skills/`                                 |

Example (Claude Code):

```bash
cp -R ./jugg-android-dev-loop ~/.claude/skills/
```

The skill already bundles the `jugg` CLI (`scripts/jugg` / `scripts/jugg.cmd`).
You can ask the AI agent to install it to your PATH — see `jugg-android-dev-loop/references/guide_install_cli.md`.

---

## Hook Setup (Manual)

If you do not use the in-app installer, you can configure hooks manually.

1. Ensure the hook scripts exist at:
   - `~/.jugg/skills/hooks/start.py`
   - `~/.jugg/skills/hooks/stop.py`
   - `~/.jugg/skills/hooks/edit.py`
   - `~/.jugg/skills/hooks/command.py`
   - `~/.jugg/skills/hooks/hook_common.py`
2. Edit your client hook config file(s) and add hook entries.
3. Use absolute script paths in `command` (do not use relative paths).

Event mapping by client:

| Client | Start event | Stop event | Edit event | Command event | Hook style |
|--------|-------------|------------|------------|---------------|------------|
| Codex / Claude Code / CodeBuddy | `UserPromptSubmit` | `Stop` | `PostToolUse` | `PreToolUse` | nested event hooks |
| Gemini CLI | `BeforeAgent` | `AfterAgent` | `AfterTool` | `BeforeTool` | nested event hooks |
| Cursor | `beforeSubmitPrompt` | `stop` | `afterFileEdit` | `beforeShellExecution` | flat event commands |

Use the matching client value in hook commands: `claude`, `cursor`, `codebuddy` or others.

Tool matcher recommendations:

- Codex / Claude Code / CodeBuddy: tool hooks should use matcher `Edit|Write|MultiEdit|apply_patch` for edit events and `Bash` for command events.
- Gemini CLI: tool hooks should use matcher `write_file|replace` for `AfterTool`, and `run_shell_command` for `BeforeTool`.
- Cursor: keep `afterFileEdit` and `beforeShellExecution` with matcher `*`.

Command hook behavior:

- `command.py` logs each received shell command as one debug-log line with newlines escaped.
- Edit hooks record a session write timestamp; raw Gradle and stop hooks only block when that timestamp is later than Jugg `status.lastCompileTime` and pending changes still exist.
- Legacy hook state without a write timestamp is treated conservatively as unverified.
- Raw Gradle commands are blocked once per pending source fingerprint; a different pending file set is treated as a new first attempt.
- Shell commands only mark the session as source-writing when they contain a low-risk write pattern targeting `app/src/main/java/com/example/myapplication`, such as redirection, `tee`, `sed -i`, `perl -i`, `cp`, or `mv`.
- VCS commands such as `git pull`, `git checkout`, `git merge`, `git rebase`, and `git reset` are not treated as agent source writes.
- For Codex, repeated raw Gradle and repeated pending stop warnings use JSON `systemMessage` instead of stderr-only success output.

Example (Claude Code):

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "python3 /absolute/path/to/home/.jugg/skills/hooks/start.py --client claude"
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "python3 /absolute/path/to/home/.jugg/skills/hooks/stop.py --client claude"
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit|Write|MultiEdit|apply_patch",
        "hooks": [
          {
            "type": "command",
            "command": "python3 /absolute/path/to/home/.jugg/skills/hooks/edit.py --client claude"
          }
        ]
      }
    ],
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "python3 /absolute/path/to/home/.jugg/skills/hooks/command.py --client claude"
          }
        ]
      }
    ]
  }
}
```

## MCP Setup (Not Recommended)

> **Not recommended.** The `jugg-android-dev-loop` skill already bundles the `jugg` CLI, which
> covers all build and deploy operations without requiring MCP.

MCP server config (add to your client's MCP config):

```json
{
  "type": "streamable-http",
  "url": "http://127.0.0.1:12320/jugg-mcp"
}
```
