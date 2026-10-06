# Jugg at a Glance

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

Jugg is an Android Studio plugin and standalone runtime that reuses a Gradle-built baseline for incremental compilation and deployment where supported. Full Gradle builds establish or replace that baseline. After this overview, read `99_index.md`; then use `98_code_map.md` to locate the owner before reading the smallest relevant topic and verifying behavior in code.

| Boundary | Directory | Responsibility |
|---|---|---|
| IDE Host | `idea/src/ide_entry/`, `idea/src/main/` | Stable plugin entry, project lifecycle, Run/Debug/UI, Android Studio adapters, and MCP Host. |
| Shared runtime | `main/src/main/` | Project model, compile/deploy state, Gradle clients, MCP protocol/actions, and utilities. |
| Studio deploy adapters | `deploy_compat/` | Version-specific Apply Changes and debugger compatibility; includes standalone deployer. |
| IDE-free Host | `cmd_line/`, `platform_compat/base_api/` | Daemon/CLI, Bundle bootstrap, and minimal API surface for shared code without IDEA. |
| App runtime | `jvmti_agent/` | Startup agent, compat loader, ViewHierarchy service, and runtime update support. |
| Compiler extension and tools | `custom_compilers/`, `aapt2-inclink/` | SPI examples and incremental resource-link binaries. |

```text
IDE Run or Standalone request
  -> shared JuggCompilerHelper chooses incremental work or a full Gradle build
  -> JuggCompiler stages changed classes, resources, assets, and APK outputs
  -> JuggDeployerHelper selects install or incremental transport per target
  -> successful deployment advances cache, overlay, and history state
```

The IDEA `JuggRunningTask` owns the user Run across compilation, per-device deployment, and fallback. Standalone exposes only the capabilities in its current MCP registry. For either Host, a source action's existence does not make it callable: use that process's `tools/list`. See `01_architecture.md`, `02_compile_core.md`, `03_deploy_core.md`, `04_engineering_compat.md`, and `08_mcp_design.md` for the cross-file boundaries.

Jugg's incremental path covers selected changes, not every Gradle transformation. A missing baseline, unsupported input, or explicit Force Gradle may require a full build; an ordinary incremental source error can instead fail the current Run. Deleting a source or resource does not by itself remove old installed content through an incremental overlay. For system apps, the default installer does not place an APK in `/system`; a project install script can own that placement and a separate signing script can sign rewritten APKs. See `02_compile_core.md` and `03_deploy_system_app.md` before attributing those outcomes to compilation.
