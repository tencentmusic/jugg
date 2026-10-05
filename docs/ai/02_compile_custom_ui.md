# Compilation System: Custom Compilers and Compilation Interaction

> Last verified: 2026-05-23
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page describes two extension surfaces:

- How a custom compiler becomes an `ICompiler` from a configured JAR and where it enters incremental compilation stages.
- How compilation interacts with IDE/CLI through `CompileUiHandler`.

For the built-in compilation flow, see `02_compile_core.md`; for IDE Run Configuration and task scheduling, see `04_engineering_ide.md`.

---

## 2. Core Source Index

| Class | File | Role |
|-------|------|------|
| `CustomCompilerManager` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/CustomCompilerManager.kt` | Receives server configuration, resolves local/remote JARs, checks MD5, and lazily loads SPI compilers |
| `ICompilerCreator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/ICompilerCreator.kt` | SPI entry point creating an `ICompiler` for the current `ICompileContext` and `Disposable` |
| `BaseCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt` | Runs custom compilers before or after built-in stages according to `CompileOrder` |
| `CompileOrder` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileOrder.kt` | Defines `before/after asset/res/source/minify/dex` and `atFirst/atLast` insertion ranges |
| `CompileUiHandler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileUiHandler.kt` | Compilation-side interaction abstraction hiding IDE UI, CLI defaults, and androidTest event sink |
| `CustomCompilerInfo` | `main/src/main/java/com/sickworm/intellij/jugg/server/protocols/Protocols.kt` | Server-provided JAR name, path, and MD5 configuration model |
| `Example*CustomCompiler` | `custom_compilers/src/main/java/com/sickworm/intellij/jugg/compiler/demo/` | Example SPI implementations for common insertion forms such as assemble, delay, and hook initialization |

---

## 3. Core Data Model

| Object | Origin | Key meaning |
|--------|--------|-------------|
| `CustomCompilerInfo.jarFileName` | Server config | Filename for downloading a remote JAR into `customCompilerDir` |
| `CustomCompilerInfo.path` | Server config | May be an absolute path, path relative to `projectDir`, or `http(s)` URL |
| `CustomCompilerInfo.md5` | Server config | Existing local JARs must match; remote downloads must also match or be deleted |
| `customCompilerJars` | `CustomCompilerManager` memory state | Current valid JAR list; obsolete JARs are removed from `customCompilerDir` |
| `customCompilers` | Lazy `CustomCompilerManager` cache | Created through `ServiceLoader` on first `getCustomCompilers()`. Each compiler batch registers in a manager-owned `Disposable` compatibility scope. On configuration/JAR-list changes, completed download, or manager `close()`, old instances are disposed before the old classloader closes |
| `ICompiler.order` | Custom compiler implementation | Determines which `BaseCompiler` before/after hook runs it |

---

## 4. Loading and Execution Flow

### 4.1 From Configuration to JAR State

`ProjectCustomConfigManager` passes local/server custom config to `CustomCompilerManager` for consistent JAR-state management. The method order within a file is less important than these rules:

- A `null` config does not clear old state; only a non-null list recomputes valid JARs.
- A local JAR enters `customCompilerJars` only if it exists and its MD5 matches.
- A remote JAR first reuses a cached copy; if missing, it downloads in the background and is checked by MD5 afterward.
- Successful download, configuration changes, or explicit JAR-list changes clear instantiated `customCompilers` and close the old `URLClassLoader`; the next `getCustomCompilers()` lazily reloads the SPI. Manager `close()` also releases the loader.
- `CustomCompilerManager` publicly implements `AutoCloseable` and accepts only `ICompileContext` during initialization; `Disposable` remains an internal compatibility scope for the `ICompilerCreator` SPI.
- Applying runtime custom config enters the project write lock to avoid releasing the old compiler scope or classloader during an active compilation.

### 4.2 From SPI Instance to Compilation Stage

```text
BaseCompileContext.customCompilers
  -> CustomCompilerManager.getCustomCompilers()
     -> URLClassLoader(customCompilerJars, current classloader)
     -> ServiceLoader.load(ICompilerCreator)
     -> creator.create(context, parent)
  -> BaseCompiler.compile(task)
     -> executeBeforeCustomCompilers(beforeCompileOrderRange, task)
        -> consumeFiles() filters later inputs first
        -> compile(filteredTask)
     -> built-in doCompile(filteredTask)
     -> executeAfterCustomCompilers(afterCompileOrderRange, filteredTask, result)
        -> convert built-in outputs back to CompileFile for the custom compiler
```

---

## 5. Compilation Interaction Protocol

`CompileUiHandler` is the only interaction surface the compilation flow should depend on. IDE, CLI, and test default implementations all supply behavior through it; compilation core does not manipulate a concrete UI directly.

| Object | File | Purpose |
|--------|------|---------|
| `isForceGradleCompile` | `CompileUiHandler` | User switch to force Gradle compilation |
| `isSkipDeploy` / `isAlwaysRestartApp` | `CompileUiHandler` | Post-compilation deployment-policy inputs |
| `createCompileStatusHolder()` | `CompileUiHandler` | Creates cancellation and current-file state object |
| `createOutputParser()` | `CompileUiHandler` | Gradle compilation-output parsing entry point |
| `confirmBuildChanges()` / `confirmDependencyChanges()` / `confirmTooManyChanges()` | `CompileUiHandler` | User confirmation for build-file changes, dependency changes, or too many source changes |
| `notifyByBalloon()` / `updateIndicatorText()` | `CompileUiHandler` | User-visible progress prompts |
| `testEventSinkFactory` | `CompileUiHandler` | Connects instrumentation events to Test Results during androidTest runs |
| `RunResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ui/RunResult.kt` | Final compilation/deployment state |
| `BuildChangesConfirmResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ui/BuildChangesConfirmResult.kt` | Result of build-change confirmation |
| `TooManyChangesConfirmResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ui/TooManyChangesConfirmResult.kt` | Result of too-many-source-changes confirmation |

---

## 6. Hidden Constraints / Design Rationale

- `updateCustomCompilers(null)` does not clear the old configuration; only a non-null list recomputes JARs and removes obsolete cache entries.
- Remote JAR download is asynchronous. On the first `getCustomCompilers()` after a configuration update, the list may still be empty if the JAR is absent; after download, `resetCompilerJars()` makes the next run reload it.
- `BaseCompiler` catches a custom compiler exception, emits a user-visible warning, and ends the current task as failed; the exception does not propagate into the IDE process.
- A before hook can alter later built-in compilation inputs through `consumeFiles()`; an after hook sees only built-in outputs converted to `CompileFile`.
- `order` must lie within the range exposed by a particular compiler to execute. For example, use `CompileOrder.afterSource` for outputs after Java/Kotlin compilation, and confirm that `JavaCompiler` / `KotlinCompiler` / `SourceCompiler` exposes the target stage.
- `URLClassLoader` uses Jugg's current classloader as parent. A custom JAR can reuse Jugg APIs, but packaging a conflicting version may make class loading unpredictable.
- `CompileUiHandler.DEFAULT` is a safe no-UI default for CLI/tests; it does not open confirmation dialogs or show a Run window.

---

## 7. Suggested Steps for a New Custom Compiler

1. Implement `ICompilerCreator` and a custom `ICompiler` in a separate module.
2. Configure `META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator`.
3. Choose a clear `CompileOrder` range for the custom `ICompiler.order`.
4. Declare the JAR path and MD5 in server configuration.
5. Update through `CustomCompilerManager` and inspect logs for JAR resolution, download, and `initCompilers finished`.

---

## 8. Investigation Entry Points

| Symptom | First entry point |
|---------|-------------------|
| JAR configured but ineffective | `CustomCompilerManager.updateCustomCompiler()`; check path type and MD5 |
| Remote JAR downloaded but did not run this time | `downloadCompilers()` / `resetCompilerJars()`; check whether a later compilation must reload it |
| `ServiceLoader` did not find an implementation | `META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator` inside the JAR |
| Compiler ran in the wrong stage | `ICompiler.order` and `CompileOrder` ranges, and the built-in compiler's `beforeCompileOrderRange` / `afterCompileOrderRange` |
| Custom compiler failure caused the entire run to fail | Warning logs from `BaseCompiler.executeBeforeCustomCompilers()` / `executeAfterCustomCompilers()` |
| UI confirmation or cancellation differs from expectations | Current `CompileUiHandler` implementation, not `CompileUiHandler.DEFAULT` |

---

## 9. Related Documents

- Core compilation: `02_compile_core.md`
- IDE execution flow: `04_engineering_ide.md`
- Project/configuration: `04_engineering_project.md`
