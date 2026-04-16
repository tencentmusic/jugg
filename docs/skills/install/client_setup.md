# Client Setup Guide (Human Manual)

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

## MCP Setup (Not Recommended)

> **Not recommended.** The `jugg-android-dev-loop` skill already bundles the `jugg` CLI, which
> covers all build and deploy operations without requiring MCP. MCP is only useful if you want
> to call Jugg tools directly from the client's tool-call interface, bypassing the skill workflow.

MCP server config (add to your client's MCP config):

```json
{
  "type": "streamable-http",
  "url": "http://127.0.0.1:12320/jugg-mcp"
}
```

| Client      | Config location                                                    | Key name    |
|-------------|--------------------------------------------------------------------|-------------|
| Codex       | `~/.codex/config.toml` — `[mcp_servers."jugg-mcp"]`, `url = ...`  | `jugg-mcp`  |
| Claude Code | `claude mcp add --transport http --scope user jugg-mcp <url>`      | `jugg-mcp`  |
| Gemini CLI  | `~/.gemini/settings.json` — `mcpServers.jugg-mcp.httpUrl`          | `jugg-mcp`  |
