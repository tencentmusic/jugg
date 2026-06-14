# Jugg CLI and Agent Skills

This section covers the automation entry points that agents use to drive Jugg in Android projects. It is organized around agent skill workflows, the Jugg CLI task domains, and direct MCP access for agents.

## Directory

| Page | Use it for | Backing capability |
|---|---|---|
| [Agent Skills](./agent-skills.md) | Understanding the packaged agent workflow for edit, build, deploy, verify, and iterate loops | `jugg-android-dev-loop` skill and references |
| [Jugg CLI](./cli.md) | Choosing the command-line entry when an agent or terminal drives Jugg | CLI wrapper over public Jugg MCP tools |
| [Build and Deploy](./cli-build-deploy.md) | Compiling, deploying, reinstalling, restarting, and handling Gradle fallback | `compile`, `deploy`, `gradle-build`, `clean-reinstall`, `restart` |
| [Android Test](./cli-android-test.md) | Running androidTest from a source file, class, or method anchor | `instrument` |
| [Runtime and Device](./cli-runtime-device.md) | Reading status, connected devices, Activity stack, and runtime logs | `status`, `devices`, `activity-stack`, `wait-logs` |
| [UI Automation](./ui-automation.md) | Inspecting, locating, tapping, and reading runtime UI state | `layout-dump`, `view-locate`, `view-inspect`, `tap` |
| [UI Layout Evidence](./layout-verify.md) | Building layout evidence from UI dumps and view inspection without relying on an unregistered batch verifier | Public UI evidence chain |
| [Remote Diagnosis](./remote-diagnosis.md) | Requesting SSH diagnosis information for remote build or device issues | `ssh-info` and agent escalation flow |
| [MCP for Agents](./mcp.md) | Calling Jugg from an MCP client when the agent talks directly to the IDE plugin | Jugg MCP endpoint and registered actions |

For exact MCP tool names, parameters, and output fields, use [MCP Tools](../../reference/mcp-tools.md) in the Reference section.
