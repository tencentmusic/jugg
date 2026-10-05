# Jugg Project Overview (Quick Read for AI)

> Last verified: 2026-09-05
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page gives AI the shortest path to a general understanding of:
- What Jugg is
- Where its main modules are
- Where to begin a typical task

It does not cover implementation details; see the `02/03/04/05/08` topic documents for those.

---

## 2. Jugg in One Sentence

**Jugg** is an Android Studio / IntelliJ plugin whose main goal is to reduce the frequency of full Gradle builds by using a side-path incremental compilation and deployment flow where possible, while retaining Gradle build artifacts.

---

## 3. Module Overview (by Code Directory)

| Module | Directory | Responsibility |
|--------|-----------|----------------|
| IDE plugin layer | `idea/src/main` + `idea/src/ide_entry` | Run configurations, task orchestration, IDE events, UI, and MCP runtime |
| Core logic layer | `main/src/main/java/com/sickworm/intellij/jugg` | Compilation, deployment, project model, Gradle/remote compilation, MCP protocol and tools |
| Android Studio compatibility layer | `deploy_compat/*` | Deploy API adapters for multiple versions (Chipmunk/Giraffe/Hedgehog/Iguana/Meerkat/Narwhal, etc.) |
| Platform compatibility stubs | `platform_compat/base_api` | IntelliJ/Android API mocks that let `main` compile outside the IDE |
| Command-line entry point | `cmd_line/src/main/java` | Basic build and incremental-build commands without an IDE |
| Standalone bootstrap | `cmd_line/standalone_bootstrap/src/main/java` | Fixed Java 11 startup boundary; reads the standalone manifest, loads the Runtime in order, falls back on failure before ready, and supports manual rollback |
| Custom compiler examples | `custom_compilers/src/main/java` | Examples of the `ICompilerCreator` SPI extension |
| JVMTI agent | `jvmti_agent/src/main/cpp` | Agent capabilities for compatible deployment |
| AAPT2 incremental-link binaries | `aapt2-inclink/src/main/resources/tools` | Tool resources for Darwin, Linux, and Windows |

---

## 4. Core Runtime Flow

1. On the IDE side, `JuggManager` initializes the project context and runtime capabilities.
2. `JuggRunningTask` orchestrates “compile -> deploy.”
3. `JuggCompilerHelper` chooses incremental compilation or a Gradle fallback.
4. On the incremental path, `JuggCompiler` performs multi-stage compilation; on the Gradle path, `LocalGradleCompileClient` / `RemoteGradleCompileClient` runs the build.
5. `JuggDeployerHelper` invokes `JuggDeployer` through `JuggDeployTask` to perform install / code swap / full swap.

---

## 5. Operating Modes (Practical View)

- Incremental compilation + incremental deployment: the default preferred path.
- Compatible deployment: switches strategy when the device, JVMTI, or structural changes do not meet the conditions.
- Gradle fallback: performs a full Gradle build when fallback is forced or automatic.

---

## 6. Capability Boundaries (Avoid Misdiagnosis)

- Jugg's side-path compilation is not equivalent to the complete Gradle pipeline.
- Annotation processing, bytecode instrumentation, and complex build-script changes usually require verification through the Gradle fallback.
- The schema returned by `tools/list` and the `ai/mcp/actions` implementations define MCP tool capabilities.
- Jugg's default installer does not initially install an app into `/system/app` or `/system/priv-app`. A Run Configuration can enable a custom APK installation script to take over install/reinstall, but the project script remains responsible for remount, push, allowlisting, and restart. A separate custom APK signing script can replace the signing step after incremental APK rewriting to support platform certificates or server-side signing. These two script capabilities are independent. See `03_deploy_system_app.md` for system-app constraints.

---

## 7. Suggested AI Task Entry Points

For AI task routing (task type → minimum required documents → code entry point), see `99_index.md §3`.

---

## 8. Further Reading

- Architecture: `01_architecture.md`
- AI search entry point and topic catalog: `99_index.md`
- Code-path map: `98_code_map.md`
