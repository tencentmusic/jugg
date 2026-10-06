# Compilation System: Custom Compilers and Host Interaction

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Purpose

This page locates the custom compiler SPI, its JAR and instance lifecycle, stage insertion, and the interaction boundary between shared compilation and IDE or CLI hosts. For built-in stage order, see `02_compile_core.md`.

## 2. Core Source Index

| Entry | Location | Responsibility |
|---|---|---|
| `ProjectCustomConfigManager` | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/ProjectCustomConfigManager.kt` | Applies the effective local/server configuration to runtime collaborators. |
| `CustomCompilerManager` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/CustomCompilerManager.kt` | Resolves JARs, loads SPI instances, and owns their `Disposable` scope and classloader. |
| `ICompilerCreator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/ICompilerCreator.kt` | ServiceLoader entry that creates an `ICompiler` for one compile context. |
| `BaseCompiler` / `CompileOrder` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/` | Insert custom compilers before or after ranges exposed by built-in stages. |
| `CompileUiHandler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileUiHandler.kt` | Carries confirmations, cancellation, status/output, and deploy-related choices across the Host boundary. |
| `JuggCompileUiHandler` / `StandaloneCompileUiHandler` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandler.kt`; `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneCompileUiHandler.kt` | Supply IDE or noninteractive standalone behavior. |

## 3. JAR and Instance Lifecycle

```text
ProjectCustomConfigManager applies effective configuration
  -> CustomCompilerManager resolves local/project-relative JARs or cached remote JARs
  -> missing remote JARs download asynchronously
  -> changed configuration/JAR list or completed download releases the old
     compiler scope, closes the child URLClassLoader, and invalidates instances
  -> BaseCompileContext.customCompilers requests lazy ServiceLoader instances
     through ICompilerCreator.create(context, disposableScope)
  -> Runtime disposal closes the manager and its loaded resources
```

`CustomCompilerInfo` supplies JAR filename, path, and MD5. Configured existing JARs are checked before entering the current list; a newly downloaded JAR is checked after download. The HTTP download reset scans cached `.jar` files, so when diagnosing an unexpected SPI provider, inspect the actual loaded JAR list rather than inferring it only from the config. A missing remote JAR can leave the current compilation without its compiler; it becomes available on a later run after download and reload.

`CustomCompilerManager.updateCustomCompilers(null)` itself retains prior state, but the normal `ProjectCustomConfigManager` caller passes `config.customCompilers.orEmpty()`. An absent list in the effective project config therefore clears configured compilers. The separate `BuildIncrementalApkCommand` CLI path supplies explicit `customCompilerJars` directly to the manager.

The loader uses Jugg's classloader as parent, so custom JARs should use the host Jugg API types. `ICompilerCreator` receives a manager-owned `Disposable` for SPI compatibility; the manager disposes that scope before closing the old loader. The IDEA server-update path applies configuration under the project write lock; the background download can invalidate loaded compilers separately.

## 4. Stage Insertion and Results

```text
BaseCompiler.compile(task)
  -> before hooks whose ICompiler.order falls in this stage's before range
     filter downstream inputs through consumeFiles() and add custom outputs
  -> built-in stage runs only if the accumulated result succeeded
  -> after hooks whose order falls in the after range receive accumulated
     outputs converted to CompileFile; their outputs join the result
```

The range belongs to the *built-in compiler instance*, not merely to the global names in `CompileOrder`. Check the target stage's `beforeCompileOrderRange` or `afterCompileOrderRange` when a hook does not run. Before hooks can change the files seen by the built-in stage. After hooks receive accumulated outputs, including successful before-hook outputs; each after hook receives the same converted task rather than a chain of previous after-hook outputs. A before-hook failure suppresses built-in and after work; an after-hook failure marks the result failed while the loop still visits other after hooks. Thrown exceptions produce developer diagnostics and user-visible warnings.

To provide a compiler, package an `ICompilerCreator` implementation and `META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator`, choose an order exposed by the intended stage, then provide the JAR path and MD5 in project custom configuration. The `custom_compilers` module contains example providers.

## 5. Host Interaction Boundary

Shared compilation calls `CompileUiHandler` for fallback and change confirmations, progress, cancellation, Gradle output parsing, and run/deploy policy. Its `testEventSinkFactory` connects androidTest instrumentation events to the Test Results UI; see `06_android_test.md` for that flow. `JuggCompileUiHandler` can display IDE dialogs and Run UI; RPC mode resolves confirmations without dialogs. `StandaloneCompileUiHandler` supplies noninteractive choices and cancellation/progress state. `CompileUiHandler.DEFAULT` is a no-UI fallback used by specific noninteractive and test paths; it is not the behavior to assume for an ordinary IDE Run.

| Symptom | First evidence to compare |
|---|---|
| Configured compiler absent | Effective config, actual JAR list and MD5, completed background download, then ServiceLoader registration. |
| Hook ran at the wrong point | Hook `order` and the chosen built-in stage's exposed range. |
| Custom compiler failed | `BaseCompiler` warning and underlying debug exception; check whether built-in work was suppressed. |
| Prompt or cancellation differed by entry point | Concrete Host `CompileUiHandler`, RPC mode, and its current process/status holder. |

## 6. Related Documents

- `02_compile_core.md` — built-in stage orchestration.
- `04_engineering_project.md` — project configuration and context.
- `04_engineering_ide.md` — IDE run and task lifecycle.
