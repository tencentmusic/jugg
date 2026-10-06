# AI Guide (Task Routing)

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Goal

Help AI complete a task with minimal context:
- Locate paths and entry classes first.
- Read the smallest relevant topic for the task.
- Inspect code only as needed afterward.

---

## 2. Recommended Search Order

1. After the required `00_overview.md` and this index first read, use `98_code_map.md` to identify the module, directory, and entry class.
2. Read the relevant topic document using the task-routing table in §3.
3. If documentation and code differ, **use the code immediately** and record the documentation item to synchronize.

---

## 3. Task Type -> Minimum Required Reading

| Task type | Minimum required documents | Code entry points (examples) |
|-----------|----------------------------|------------------------------|
| **Testing and verification strategy / TDD / new testcase** | `06_testing.md` | `main/src/test/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGeneratorTest.kt`, `idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt`, `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/DeployCompatArchitectureTest.kt` |
| **App or library androidTest support / instrumentation run flow** | `06_android_test.md`, `06_testing.md` | `JuggAndroidTestRunConfiguration.kt`, `JuggAndroidTestLineMarkerContributor.kt`, `LibraryTestApkBackfillHelper.kt`, `InstrumentationSmRunnerBridge.kt`, `TestLauncher.kt` |
| Overall architecture / module boundaries | `98_code_map.md`, `01_architecture.md` | - |
| Compilation failure / fallback policy | `98_code_map.md`, `02_compile_core.md`, `02_compile_source.md` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt`, `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt` |
| Resource / Manifest / DataBinding issues | `98_code_map.md`, `02_compile_resource.md`, `02_compile_manifest.md`, `02_compile_databinding.md` | `compiler/overlay`, `compiler/manifest`, `compiler/databinding` |
| Custom compiler / compilation interaction protocol | `98_code_map.md`, `02_compile_custom_ui.md` | `compiler/custom/*`, `compiler/CompileUiHandler.kt`, `compiler/ui/*` |
| Deployment failure / hot-update strategy | `98_code_map.md`, `03_deploy_core.md`, `03_deploy_complete.md`, `06_testing.md` §7.1 | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`, `idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt` |
| **System app / priv-app / FLAG_SYSTEM / platform signing / custom APK install script / initial install to /system** | `03_deploy_system_app.md`, `03_deploy_core.md` | `CustomApkInstallScriptRunner`, `JuggDeployer.install()`, `DirectOverlayWriter` |
| **Custom APK signing script / server signing after APK rewrite / platform-certificate signing** | `03_deploy_system_app.md` §4, `03_deploy_core.md`, `05_utilities.md` §4 | `CustomApkSignScriptRunner`, `ApkFileModifier.insertAndResign()`, `IncrementalDeployHelper.updateApk()` |
| Constant-change recompilation issue (const ref) | `98_code_map.md`, `03_deploy_const_ref.md`, `02_compile_core.md` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/`, `deploy/DeployFileManager.kt`, `deploy/data/DeployDataGenerator.kt` |
| Effect analysis / propagation of class changes | `98_code_map.md`, `03_deploy_data_generator.md` | `deploy/data/DeployDataGenerator.kt` |
| **EffectedType kinds / merge precedence / minify removal detection** | `03_deploy_data_generator.md` §4–§5 | `EffectedClassNode.kt`, `DeployDataGenerator.kt`, `DeployDataDatabaseSqLiteHelper.kt`, `CompileEffectAnalyzer.kt` |
| Subclass recompilation after changing a base class | `03_deploy_data_generator.md` §4, `02_compile_core.md` | `DeployDataDatabaseSqLiteHelper.kt`, `DeployDataDatabase.kt`, `JuggCompilerHelper.kt` |
| JVMTI / runtime agent coordination | `98_code_map.md`, `03_runtime_jvmti.md` | `deploy/JuggJvmtiAgentManager.kt`, `jvmti_agent/src/main/cpp/` |
| IDE lifecycle / Run Configuration | `98_code_map.md`, `04_engineering_ide.md` | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`, `JuggRunConfiguration.kt` |
| Jugg Debug attach / unavailable breakpoints | `98_code_map.md`, `04_engineering_debug_attach.md`, `04_engineering_compat.md` | `JuggDebugProgramRunner.kt`, `JuggDebugSessionManager.kt`, `AndroidStudioDebuggerAttachStarter.kt`, `*AsDeployerCompat.kt` |
| Gradle project information and dependency reading | `98_code_map.md`, `04_engineering_project.md` | `project/info/ProjectModelSource.kt`, `gradle/script/GradleProjectInfoReader.kt`, `project/dependency/GradleProjectInfoLocalFetchManager.kt` |
| Standalone / IDEA shared Run Configuration | `98_code_map.md`, `04_engineering_ide.md`, `04_engineering_compat.md`, `05_utilities.md` §3 | `project/runtime/CliRunConfiguration.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/project/runtime/IdeaCliRunConfigurationManager.kt`, `JuggRunConfigurationOptions.kt` |
| Compatibility layer, Standalone host/locks, Stub artifact checks | `98_code_map.md`, `04_engineering_compat.md` | `deploy_compat/*/AsDeployerCompat.kt`, `StandaloneProjectRegistry.kt`, `ExecutionLockManager.kt`, `deploy_compat/verify_stub_api.sh` |
| MCP tool design and invocation | `98_code_map.md`, `08_mcp_tools_list.md`, `08_mcp_design.md` | `ai/mcp/McpToolInvoker.kt`, `ai/mcp/actions/*`, `ai/mcp/util/CrashDetector.kt`, `ai/mcp/util/LastDeployTimestampRegistry.kt` |
| `jugg` CLI subcommand use / **new or changed CLI parameters** / CLI script or skill version | `08_cli_tools_list.md` | `docs/skills/jugg-android-dev-loop/scripts/jugg.py`, `JuggCliAutoUpdater` |
| MCP UI layout verification design (public tool boundary / evidence chain) | `98_code_map.md`, `08_mcp_layout_verify_design.md` | `McpToolActionRegistry.kt`, `LayoutDumpHelper.kt`, `UiFindMcpToolAction.kt`, `EvalViewMcpToolAction.kt`, `TapMcpToolAction.kt` |
| MCP tool testing and regression | `06_testing.md`, `08_mcp_design.md`, `08_mcp_tools_list.md` | `ai/mcp/actions/McpToolActionRegistry.kt`, `ai/mcp/actions/*` |
| MCP UI verification blind test / view-inspect | `98_code_map.md`, `08_mcp_layout_verify_design.md`, `08_mcp_ui_verify_checklist.md` | `ai/mcp/actions/*` |
| figma-layout-verify internal algorithm (relationship extraction / IoU matching / tolerance) | `08_mcp_figma_layout_verify_internals.md` | `ai/mcp/layout/extractor/RelationExtractor.kt`, `ai/mcp/layout/matcher/ElementMatcher.kt`, `ai/mcp/layout/verifier/RelationVerifier.kt` |
| Utility capabilities (apk/git/logger/server) | `98_code_map.md`, `05_utilities.md` | `main/src/main/java/com/sickworm/intellij/jugg/` (see package directories in `98_code_map.md`) |
| **Crash involving annotation/reflection/class references after release incremental compilation** | `98_code_map.md`, `02_compile_obfuscation.md` | `DexObfuscator.kt`, `DexMinifyCompiler.kt` |
| **Plugin runtime investigation** (IDE stalls / startup hangs / compilation issues / DB problems) | `09_plugin_runtime_debug.md`, then route to a topic based on symptoms | `JuggPathManager`, `DeployFileManager`, `TaskRunnerManager`, `ConstRefEngine` |
| **Kotlin IR lowering / `copyValueParametersToStatic` / `Dispatch receiver type` / recovery after clean** | `09_plugin_runtime_debug.md` §4.7, `02_compile_source.md` | First distinguish an actual inheritance error from remote-sync input and Kotlin/Gradle incremental state |
| Knowledge-base maintenance / restructuring topic documents | `97_maintenance_manual.md`, `99_index.md`, `98_code_map.md` | `docs/ai/*` |
| Wiki architecture / local operation / publishing | `10_wiki_architecture.md`, `97_maintenance_manual.md` | `docs/wiki/package.json`, `docs/wiki/.vitepress/config.mts`, `.github/workflows/wiki-pages.yml` |
| Wiki user-page writing / English-to-Chinese mirror / links | `10_wiki_authoring.md`, `97_maintenance_manual.md` | `docs/wiki/**/*.md`, `.agents/skills/wiki-writer/scripts/validate_wiki.py` |

---

## 4. Topic Document Catalog

| Document | Focus |
|----------|-------|
| `01_architecture.md` | Layered architecture and core flows |
| `02_compile_core.md` | Main incremental compilation flow and stage orchestration |
| `02_compile_source.md` | Java/Kotlin/Dex compilation chain |
| `02_compile_resource.md` | Resource compilation and aapt2 link |
| `02_compile_databinding.md` | Incremental DataBinding/ViewBinding processing |
| `02_compile_manifest.md` | Incremental Manifest merge |
| `02_compile_obfuscation.md` | Release DEX remapping, `_jugg_fix`, and injected minified-build R8 rules |
| `02_compile_manifest_obfuscation.md` | Compatibility entry point for older Manifest and obfuscation-mapping references |
| `02_compile_custom_ui.md` | Custom compilers and the compilation interaction protocol |
| `03_deploy_core.md` | Core install/code swap/full swap mechanisms |
| `03_deploy_const_ref.md` | Constant-reference effect analysis and troubleshooting for constant recompilation |
| `03_deploy_data_generator.md` | Effect analysis and deployment-data generation |
| `03_deploy_complete.md` | End-to-end flow from Run to completed deployment |
| `03_deploy_system_app.md` | System/privileged apps: boundaries of the default installer, custom APK installation and signing scripts; paths, allowlists, platform certificates, and investigation entry points |
| `03_runtime_jvmti.md` | JVMTI agent and deployment coordination |
| `04_engineering_project.md` | Project model and Gradle information reading |
| `04_engineering_ide.md` | IDE lifecycle, Run Configuration, and task scheduling |
| `04_engineering_debug_attach.md` | Jugg Debug attach lifecycle, AS XDebugger integration, and unavailable-breakpoint investigation |
| `04_engineering_compat.md` | AS version adapters, Stub verification, Standalone host lifecycle and locks, IDE-free CI commands |
| `05_utilities.md` | Shared paths, settings/profiles, APK signing, backend, diagnostics, CLI safety, Git, and platform boundaries |
| `06_testing.md` | Testing and verification strategy: evidence, test-value gate, alternative verification, behavior owner, TDD, L0–L3 layers, and testcases |
| `06_android_test.md` | App and library androidTest: target/model state, APK identity and ownership, library Test APK backfill, instrumentation results and log attribution; verification policy is in `06_testing.md` |
| `08_mcp_design.md` | MCP routing, validation, response shapes, Host capability boundary, and change checklist |
| `08_mcp_layout_verify_design.md` | UI layout verification design: public tool boundary, evidence chain, unit conversion, and unregistered-action risks |
| `08_mcp_ui_verify_checklist.md` | MCP UI verification checklist: page boundaries, expected/actual evidence, selectors, unit conversion, and reporting rules |
| `08_mcp_tools_list.md` | Twenty registered MCP actions, Host capability limits, key call contracts and diagnostic errors; exact arguments come from the current Runtime's `tools/list` |
| `08_cli_tools_list.md` | `jugg` CLI (MCP wrapper) subcommand parameters and behavior differences |
| `08_mcp_figma_layout_verify_internals.md` | Internal figma-layout-verify algorithm: Figma JSON parsing, spacing/alignment relationship extraction, IoU element matching, and tolerance checks |
| `09_plugin_runtime_debug.md` | Runtime investigation entry: evidence boundaries, counter-evidence gate, scene preservation, and first-hop routing from symptoms to topics |
| `10_wiki_architecture.md` | Wiki route mirrors, dev-only gating, build checks, Pages publication, and release-asset links |
| `10_wiki_authoring.md` | User-facing page roles, mechanism explanation, Markdown and links, English source to Chinese mirror, and content verification |
| `97_maintenance_manual.md` | AI knowledge-base maintenance manual: topic-document standards, structural template, density control, and self-review checklist |

---

## 5. Search Strategy (Mandatory)

- Never load the entire documentation set at once.
- Read catalog documents (`99/98`) before topic documents.
- Expand only the sections directly relevant to the task.
- For API capabilities, inspect the implementation rather than relying only on documentation.

---

## 6. Maintenance Rules

- For class-name/path changes, synchronize at least `98_code_map.md` (see its §6 for path-synchronization rules).
- When maintaining or restructuring topic documents, follow the quality standard and self-review checklist in `97_maintenance_manual.md` first.
- Save new task plans directly in `docs/task/YYYY-MM/` for their creation month; do not add topic subdirectories beneath the month.
- **New topic documents**: add a task-type row in §3 and a document-description row in §4.
- When updating an existing topic, describe the current implementation directly; do not frame current behavior with a date boundary such as “since a certain date.”
- If documentation has not yet been synchronized, state “code takes precedence” in the conclusion.
- **MCP/CLI behavior changes**: synchronization rules are in `08_mcp_design.md` §8; CLI/skill version increments are in `08_cli_tools_list.md` §3.7.
