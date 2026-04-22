# Guide: Install jugg CLI

Install the `jugg` CLI so it can be called directly from any terminal/cmd/PowerShell session.

**Source directory** (the path you copy *from*):  
`{SKILL_DIR}/scripts/`  
where `{SKILL_DIR}` is the absolute path of this skill on disk
(e.g. `.../docs/skills/jugg-android-dev-loop`).

---

## macOS / Linux

### Step 1 — Copy scripts to an install directory

```bash
SKILL_DIR="<absolute-path-to-jugg-android-dev-loop>"
INSTALL_DIR="$HOME/.jugg/bin"

mkdir -p "$INSTALL_DIR"
rm -rf "$INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
cp -r "$SKILL_DIR/scripts/." "$INSTALL_DIR/"
chmod +x "$INSTALL_DIR/jugg" "$INSTALL_DIR/jugg.py"
```

### Step 2 — Add a symlink (or alias) so `jugg` is on PATH

**Option A — create a symlink in `~/.local/bin` pointing to the real file in `~/.jugg/bin`** (recommended, no sudo needed):

> ⚠️ Do **not** copy files into `~/.local/bin`. The actual install directory is `~/.jugg/bin` (set in Step 1). This step only creates a symlink so `jugg` is on PATH.

```bash
INSTALL_DIR="$HOME/.jugg/bin"

mkdir -p "$HOME/.local/bin"
# avoid ~/.local/bin/jugg is a directory and symlink created inside directory
[ -d "$HOME/.local/bin/jugg" ] && rm -rf "$HOME/.local/bin/jugg"
ln -sf "$INSTALL_DIR/jugg" "$HOME/.local/bin/jugg"
```

Then check if `~/.local/bin` is on your PATH:

```bash
echo $PATH | grep -q "$HOME/.local/bin" && echo "✅ Already on PATH" || echo "⚠️ Not on PATH, running fallback..."
```

If not on PATH, run the fallback below to add the install directory directly:

```bash
# Fallback — add the install directory to PATH
# Detect the right rc file
if [ -n "$ZSH_VERSION" ]; then
    RC_FILE="$HOME/.zshrc"
elif [ "$(uname)" = "Darwin" ]; then
    RC_FILE="$HOME/.bash_profile"   # macOS bash uses .bash_profile
else
    RC_FILE="$HOME/.bashrc"         # Linux bash uses .bashrc
fi

# Only append if not already present (idempotent)
grep -qF 'HOME/.jugg/bin' "$RC_FILE" 2>/dev/null \
    || echo 'export PATH="$HOME/.jugg/bin:$PATH"' >> "$RC_FILE"

# Reload
source "$RC_FILE"
echo "✅ PATH updated in $RC_FILE"
```

### Step 3 — Verify

```bash
jugg --help
```

Expected: usage text listing all subcommands.

---

## Windows

### Step 1 — Copy scripts to an install directory

Open **PowerShell**:

```powershell
$SKILL_DIR = "<absolute-path-to-jugg-android-dev-loop>"
$INSTALL_DIR = "$env:USERPROFILE\.jugg\bin"

New-Item -ItemType Directory -Force -Path $INSTALL_DIR | Out-Null
Copy-Item -Recurse -Force "$SKILL_DIR\scripts\*" $INSTALL_DIR

# Verify jugg.cmd entry point exists
if (-not (Test-Path "$INSTALL_DIR\jugg.cmd")) {
    Write-Error "jugg.cmd not found in $INSTALL_DIR. Check that SKILL_DIR is correct."
    exit 1
}
```

### Step 2 — Add the install directory to the user PATH

```powershell
$current = [System.Environment]::GetEnvironmentVariable("PATH", "User")
if ($current -notlike "*$INSTALL_DIR*") {
    [System.Environment]::SetEnvironmentVariable(
        "PATH", "$INSTALL_DIR;$current", "User"
    )
    Write-Host "PATH updated. Restart your terminal to apply."
} else {
    Write-Host "Already on PATH."
}
```

### Step 3 — Verify

Open a **new** cmd or PowerShell window:

```cmd
jugg --help
```

Expected: usage text listing all subcommands.

> **Note**: On Windows `jugg.cmd` is the entry point.  
> Python 3 must be installed and available on PATH (`python --version`).

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `command not found: jugg` (macOS/Linux) | PATH not updated or symlink not created | Re-run Step 2; open a new terminal |
| `jugg: Permission denied` | Shell wrapper not executable | `chmod +x <install-dir>/jugg <install-dir>/jugg.py` |
| `python3: command not found` | Python 3 not installed | Install via `brew install python3` (macOS) or distro package manager |
| `jugg` not recognized (Windows) | PATH change not applied | Open a **new** terminal window; confirm with `echo %PATH%` |
| `python: command not found` (Windows) | Python not on PATH | Install Python 3 from python.org; check "Add to PATH" during install |

---

## Uninstall

**macOS/Linux**:
```bash
rm "$HOME/.local/bin/jugg"      # remove symlink (if Option A)
rm -rf "$HOME/.jugg"            # remove all jugg files
# Remove the PATH line from ~/.zshrc / ~/.bashrc if Option B was used
```

**Windows** (PowerShell):
```powershell
$INSTALL_DIR = "$env:USERPROFILE\.jugg\bin"
Remove-Item -Recurse -Force $INSTALL_DIR
# Remove $INSTALL_DIR from user PATH via System Properties or re-run SetEnvironmentVariable
```
