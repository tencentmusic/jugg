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

## Recommended Closed-loop Invocation (MCP-only)

```bash
python3 skills/jugg-mcp-android-loop/scripts/jugg_mcp_loop.py \
  --project-dir /ABS/PROJECT/PATH \
  --fallback-clean-reinstall \
  --start-activity .MainActivity \
  --tap-x 540 --tap-y 530 \
  --pre-tap-delay-sec 2.0 --tap-repeat 2 --tap-interval-sec 1.5
```

## MCP-only Recommendation

- Strongly prefer MCP tools for runtime and verification: `app_start`, `tap`, `layout_dump`, `screenshot`, `record`.
- Avoid direct external adb commands in normal closed-loop flow.
