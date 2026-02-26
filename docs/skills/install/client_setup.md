# Client Setup Guide (Codex / Claude Code / Gemini)

## Install the Skill

```bash
bash docs/skills/install/install_mcp_and_skill.sh
```

## Install MCP Clients (Auto)

```bash
bash docs/skills/install/install_mcp_and_skill.sh \
  --with-mcp \
  --mcp-client auto \
  --mcp-endpoint http://localhost:12320/jugg-mcp \
  --mcp-server-name jugg-mcp
```

`auto` strategy detects client home directories and installs only detected clients:

- Codex: `~/.codex`
- Claude Code: `~/.claude` or `~/.config/claude`
- Gemini CLI: `~/.gemini`

If no known client directory is found, MCP installation is skipped with a warning.

## Install MCP Clients (Explicit)

```bash
bash docs/skills/install/install_mcp_and_skill.sh --mcp-client all
```

## Per-client Commands

### Codex

Preferred:

```bash
codex mcp add jugg-mcp --url http://localhost:12320/jugg-mcp
```

Fallback (manual file): `~/.codex/config.toml`

```toml
[mcp_servers."jugg-mcp"]
url = "http://localhost:12320/jugg-mcp"
```

### Claude Code

```bash
claude mcp add --transport http --scope user jugg-mcp http://localhost:12320/jugg-mcp
```

### Gemini CLI

Config file: `~/.gemini/settings.json`

```json
{
  "mcpServers": {
    "jugg-mcp": {
      "httpUrl": "http://localhost:12320/jugg-mcp"
    }
  }
}
```

## Verify

- Codex: `codex mcp list`
- Claude Code: `claude mcp list`
- Gemini: run a Gemini CLI MCP call and verify `jugg-mcp` server appears.
