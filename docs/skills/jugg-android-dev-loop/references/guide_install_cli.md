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
INSTALL_DIR="$HOME/.local/bin/jugg-cli"

mkdir -p "$INSTALL_DIR"
cp -r "$SKILL_DIR/scripts/." "$INSTALL_DIR/"
chmod +x "$INSTALL_DIR/jugg" "$INSTALL_DIR/jugg.py"
```

### Step 2 — Add a symlink (or alias) so `jugg` is on PATH

**Option A — symlink `jugg.py` as `jugg` into a directory already on PATH** (recommended):

```bash
ln -sf "$INSTALL_DIR/jugg.py" /usr/local/bin/jugg
```

If `/usr/local/bin` requires `sudo`:

```bash
sudo ln -sf "$INSTALL_DIR/jugg.py" /usr/local/bin/jugg
```

**Option B — add the install directory to PATH** (no sudo needed):

```bash
# Append to ~/.zshrc (zsh) or ~/.bashrc (bash)
echo 'export PATH="$HOME/.local/bin/jugg-cli:$PATH"' >> ~/.zshrc
source ~/.zshrc
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
$INSTALL_DIR = "$env:USERPROFILE\.local\bin\jugg-cli"

New-Item -ItemType Directory -Force -Path $INSTALL_DIR | Out-Null
Copy-Item -Recurse -Force "$SKILL_DIR\scripts\*" $INSTALL_DIR
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
rm /usr/local/bin/jugg          # remove symlink (if Option A)
rm -rf "$HOME/.local/bin/jugg-cli"
# Remove the PATH line from ~/.zshrc / ~/.bashrc if Option B was used
```

**Windows** (PowerShell):
```powershell
$INSTALL_DIR = "$env:USERPROFILE\.local\bin\jugg-cli"
Remove-Item -Recurse -Force $INSTALL_DIR
# Remove $INSTALL_DIR from user PATH via System Properties or re-run SetEnvironmentVariable
```
