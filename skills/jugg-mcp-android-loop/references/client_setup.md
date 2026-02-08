# Client Setup Guide (Codex / Claude Code / Gemini)

## Install the Skill

```bash
bash skills/jugg-mcp-android-loop/scripts/install_skill.sh --force
```

## Configure MCP Clients

```bash
python3 skills/jugg-mcp-android-loop/scripts/setup_mcp_clients.py --all --endpoint http://localhost:12320/mcp --server-name jugg
```

## Per-client Commands

### Codex

Preferred:

```bash
codex mcp add jugg --url http://localhost:12320/mcp
```

Fallback (manual file): `~/.codex/config.toml`

```toml
[mcp_servers.jugg]
url = "http://localhost:12320/mcp"
```

### Claude Code

```bash
claude mcp add --transport http jugg http://localhost:12320/mcp
```

### Gemini CLI

Config file: `~/.gemini/settings.json`

```json
{
  "mcpServers": {
    "jugg": {
      "httpUrl": "http://localhost:12320/mcp"
    }
  }
}
```

## Verify

- Codex: `codex mcp list`
- Claude Code: `claude mcp list`
- Gemini: run a Gemini CLI MCP call and verify `jugg` server appears.

## Recommended Closed-loop Invocation

```bash
python3 skills/jugg-mcp-android-loop/scripts/jugg_mcp_loop.py --project-dir /ABS/PROJECT/PATH --fallback-clean-reinstall
```
