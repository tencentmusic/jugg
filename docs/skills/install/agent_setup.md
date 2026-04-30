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
2. Edit your client hook config file(s) and add hook entries.
3. Use absolute script paths in `command` (do not use relative paths).

Event mapping by client:

| Client | Start event | Stop event | Hook style |
|--------|-------------|------------|------------|
| Codex / Claude Code / CodeBuddy | `UserPromptSubmit` | `Stop` | nested event hooks |
| Gemini CLI | `BeforeAgent` | `AfterAgent` | nested event hooks |
| Cursor | `beforeSubmitPrompt` | `stop` | flat event commands |

Use the matching client value in hook commands: `claude`, `cursor`, `codebuddy` or others.

Example:

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
