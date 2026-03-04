# Client Setup Guide (Human Manual)

## Codex

### 1) Skill config file

- Skill source: `docs/skills/jugg-android-dev-loop`
- Skill target root: `${CODEX_HOME:-$HOME/.codex}/skills`

### 2) Install skill bash

```bash
SKILL_NAME="jugg-android-dev-loop"
SKILL_SRC="docs/skills/${SKILL_NAME}"
SKILL_ROOT="${CODEX_HOME:-$HOME/.codex}/skills"

mkdir -p "${SKILL_ROOT}"
rm -rf "${SKILL_ROOT}/${SKILL_NAME}"
cp -R "${SKILL_SRC}" "${SKILL_ROOT}/${SKILL_NAME}"
```

### 3) MCP config file

- `~/.codex/config.toml`

### 4) Install MCP bash

```bash
codex mcp remove jugg-mcp >/dev/null 2>&1 || true
codex mcp add jugg-mcp --url http://localhost:12320/jugg-mcp
```

### 5) Verify

Skill verify:

```bash
test -f "${CODEX_HOME:-$HOME/.codex}/skills/jugg-android-dev-loop/SKILL.md" \
  && echo "codex skill ok" \
  || echo "codex skill missing"
```

MCP verify:

```bash
codex mcp list | rg "jugg-mcp"
```

## Claude Code

### 1) Skill config file

- Skill source: `docs/skills/jugg-android-dev-loop`
- Skill target root: `~/.claude/skills` (or `~/.config/claude/skills`)

### 2) Install skill bash

```bash
SKILL_NAME="jugg-android-dev-loop"
SKILL_SRC="docs/skills/${SKILL_NAME}"

if [ -d "$HOME/.claude" ]; then
  CLAUDE_HOME="$HOME/.claude"
else
  CLAUDE_HOME="$HOME/.config/claude"
fi

mkdir -p "${CLAUDE_HOME}/skills"
rm -rf "${CLAUDE_HOME}/skills/${SKILL_NAME}"
cp -R "${SKILL_SRC}" "${CLAUDE_HOME}/skills/${SKILL_NAME}"
```

### 3) MCP config file

- `~/.claude.json` (written by CLI command)

### 4) Install MCP bash

```bash
claude mcp remove --scope user jugg-mcp >/dev/null 2>&1 || true
claude mcp add --transport http --scope user jugg-mcp http://localhost:12320/jugg-mcp
```

### 5) Verify

Skill verify:

```bash
if [ -d "$HOME/.claude" ]; then
  CLAUDE_HOME="$HOME/.claude"
else
  CLAUDE_HOME="$HOME/.config/claude"
fi

test -f "${CLAUDE_HOME}/skills/jugg-android-dev-loop/SKILL.md" \
  && echo "claude skill ok" \
  || echo "claude skill missing"
```

MCP verify:

```bash
claude mcp list | rg "jugg-mcp"
```

## Gemini CLI

### 1) Skill config file

- Skill source: `docs/skills/jugg-android-dev-loop`
- Skill target root: `${GEMINI_HOME:-$HOME/.gemini}/skills`

### 2) Install skill bash

```bash
SKILL_NAME="jugg-android-dev-loop"
SKILL_SRC="docs/skills/${SKILL_NAME}"
GEMINI_HOME_DIR="${GEMINI_HOME:-$HOME/.gemini}"

mkdir -p "${GEMINI_HOME_DIR}/skills"
rm -rf "${GEMINI_HOME_DIR}/skills/${SKILL_NAME}"
cp -R "${SKILL_SRC}" "${GEMINI_HOME_DIR}/skills/${SKILL_NAME}"
```

### 3) MCP config file

- `${GEMINI_SETTINGS:-${GEMINI_HOME:-$HOME/.gemini}/settings.json}`

### 4) Install MCP bash

```bash
python3 - <<'PY'
import json
import os

settings = os.path.expanduser(
    os.environ.get("GEMINI_SETTINGS")
    or f"{os.environ.get('GEMINI_HOME', '~/.gemini')}/settings.json"
)
settings = os.path.expanduser(settings)
os.makedirs(os.path.dirname(settings), exist_ok=True)

data = {}
if os.path.exists(settings):
    with open(settings, "r", encoding="utf-8") as f:
        text = f.read().strip()
        if text:
            data = json.loads(text)

data.setdefault("mcpServers", {})["jugg-mcp"] = {
    "httpUrl": "http://localhost:12320/jugg-mcp"
}

with open(settings, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
```

### 5) Verify

Skill verify:

```bash
test -f "${GEMINI_HOME:-$HOME/.gemini}/skills/jugg-android-dev-loop/SKILL.md" \
  && echo "gemini skill ok" \
  || echo "gemini skill missing"
```

MCP verify:

```bash
python3 - <<'PY'
import json
import os

settings = os.path.expanduser(
    os.environ.get("GEMINI_SETTINGS")
    or f"{os.environ.get('GEMINI_HOME', '~/.gemini')}/settings.json"
)

ok = False
if os.path.exists(settings):
    with open(settings, "r", encoding="utf-8") as f:
        data = json.load(f)
        ok = "jugg-mcp" in data.get("mcpServers", {})

print("gemini mcp ok" if ok else "gemini mcp missing")
PY
```
