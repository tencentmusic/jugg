---
title: 'CLI guide: incremental builds and deployment'
description: 'Practical guide to Jugg CLI: second-level terminal incremental builds, everyday deployment hot reload, parameter mapping, and AI Agent automation.'
status: active
tags:
  - guide
  - cli
  - build-tools
---

# CLI

The Jugg CLI provides a unified command-line interface to invoke Jugg's bypass incremental compilation and instant hot reload capabilities from your terminal, CI scripts, or AI Coding Agents. It communicates directly with the background daemon hosted in Android Studio, enabling second-level build and deployment workflows without switching to the IDE window.

## 1. Install Jugg CLI and Agent Skills

Installing directly through Android Studio's UI is recommended:

1. Press `Shift` twice in Android Studio to open **Search Everywhere**.
2. Type and select `Install Jugg Skills`.
3. In the setup modal, check the components you need:
   - **Install CLI to `$PATH`**: Symlinks the executable `jugg` command into your system PATH (e.g., `~/.jugg/bin`).
   - **Currently installed agents**: Automatically registers the `jugg-android-dev-loop` skill with AI assistants like Claude Code and Antigravity.
   - **Install agent hooks**: Prompts agents to run incremental verification whenever Android source files are modified.
4. Click **Install** to complete the environment setup.

Open any terminal and run `jugg version` to confirm that the installation succeeded.

## 2. Output modes: Interactive terminal vs. script parsing (--console)

The CLI supports three output modes tailored for human developers and machine consumers:

```bash
# Human-operated terminal (color spinner and dynamic progress animation)
jugg --console=rich status

# AI Agent and plain log output (clean line-by-line output without escape sequences)
jugg --console=plain compile

# Automated script consumption (stdout outputs structured JSON only)
jugg --console=json status
```

| Mode | Target User | Key Characteristics |
|---|---|---|
| `rich` | Human interactive terminal | Features spinners, color highlights, and formatted status tables |
| `plain` | AI Agents and CI runners | Clean text stream without terminal escape characters, context-friendly |
| `json` | Scripts and automation tools | Strict structured JSON on stdout, perfect for `jq` or script assertions |

> [!TIP]
> When writing shell scripts or setting up automation pipelines, always use `--console=json` to reliably extract `isCompileSuccess` and `isDeploySuccess`.

## 3. Practical recipes for everyday workflows (Quick Recipes)

Here are the four most common everyday development workflows and their CLI commands:

### Recipe A: Hot reload code changes in seconds (jugg deploy)
After modifying Java, Kotlin source files or XML layouts, trigger a bypass deploy directly from your terminal:

```bash
# Incrementally compile and hot swap changes to connected device (typically 1–3s)
jugg deploy

# Force an app restart when modifying Activity declarations or class signatures
jugg deploy --always-restart-app true
```

### Recipe B: Rapid syntax and incremental compile check (jugg compile)
Verify whether your changes compile without packaging APKs or pushing to a device:

```bash
jugg compile
```

This returns compilation diagnostics and error line numbers in about 1 second—an ideal syntax check while coding.

### Recipe C: Inspect build status and pending files (jugg status)
Check incremental baseline readiness, pending uncompiled files, and connected devices:

```bash
jugg status
```

### Recipe D: Safe rebuild and data reset
When modifying `build.gradle` dependencies or when you want to verify against a fresh install:

```bash
# Trigger a full Gradle build, reinstall, and launch
jugg gradle-build

# Clear app internal sandbox data and reinstall APK
jugg clean-reinstall
```

## 4. Multi-project and cross-directory calls (--project-dir)

When run from the project root or any subfolder, the CLI automatically matches the active project in Android Studio.

To execute from outside the project directory, specify the absolute path explicitly:

```bash
jugg --project-dir /path/to/android/project deploy
```

> [!NOTE]
> Flag names support both kebab-case and camelCase (e.g., `--project-dir` and `--projectDir` are equivalent).

## 5. Concurrency and interrupt strategies (--if-compiling)

If a previously triggered compile task is still running, control the queue behavior with:

```bash
# Default behavior: wait for the previous task to finish before starting
jugg --if-compiling wait deploy

# Interrupt strategy: immediately cancel the running compile and start fresh
jugg --if-compiling interrupt deploy
```

## 6. AI Agent integration best practices

When pairing with AI coding agents (such as Claude Code, Cursor, or Antigravity):

1. **Default to compile checks**: Instruct the agent to run `jugg --console=plain compile` first to catch compiler and type errors.
2. **Restrict automatic deployment**: Require explicit user intent before letting the agent call `jugg deploy` or device-level UI inspection.
3. **Verify both compile and deploy**: In script validations, check both `isCompileSuccess` and `isDeploySuccess` to prevent offline device errors from masking deployment failures.

## 7. Troubleshooting common CLI issues

| Symptom | Probable Cause | Recommended Resolution |
|---|---|---|
| **`CLI cannot find project`** | Android Studio hasn't opened the project or finished Jugg init | Confirm project is open in IDE with Sync complete, or pass `--project-dir` |
| **`Port connection refused`** | Daemon port not listening (default scan range `12320–12329`) | Verify Android Studio is running and Jugg plugin is initialized |
| **Command hangs in waiting state** | A previous background build task is stalled | Run `jugg status` to inspect state; use `--if-compiling interrupt` if needed |
| **Windows: Python not found** | Python 3.7+ is not present in system PATH | Verify `python3 --version` and ensure Python is added to environment PATH |

---

## Next steps

- 📖 **[Jugg CLI Command Reference](../reference/cli-commands.md)**: Explore all 16 subcommands and advanced parameters
- 🚀 **[Everyday Run and Hot Reload Guide](../guide/run.md)**: Understand hot swap and compatibility deployment
- 🤖 **[Agent Skills Integration](../capabilities/tools/agent-skills.md)**: Master automated agent-driven workflows with Jugg
