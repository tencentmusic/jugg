# Constant-Reference Impact Analysis

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

ConstRef finds source that must recompile after an inlinable Java/Kotlin constant changes. This page covers its edit-to-commit state, cache boundaries, and diagnostic meaning. Structural class-reference propagation is in `03_deploy_data_generator.md`.

## 2. Core Source Index

| Owner | Location | Responsibility |
|---|---|---|
| `DeployFileManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt` | Forwards save/delete/module events and acknowledges ConstRef after deployment commit |
| `DeployDataGenerator` / `ConstRefEffectProvider` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/` | Waits for current analysis, then puts affected paths in `JuggDeployData.constRefEffectedSourcePaths` |
| `ConstRefEngine` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngine.kt` | Editing-file delay, full scan, pre-compile readiness, impact query, and lifecycle |
| `ConstRefAnalyzer` / `JavaConstParser` / `KotlinConstParser` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/` | Java/Kotlin definitions and syntax-only reference candidates; Kotlin PSI access is serialized |
| `ConstRefChangeTracker` / `ConstRefImpactResolver` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/` | Changed/removed definition keys and conservative affected-file lookup |
| `ConstRefCacheDatabase` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabase.kt` | Shared SQLite definitions, candidates, per-worktree file baseline, and analysis versions |
| `RepoSharedFingerprintStore` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/RepoSharedFingerprintStore.kt` | Content fingerprint reuse across worktrees of one Git repository |

## 3. State and Call Chain

```text
DeployFileManager.addChangedFile(Java/Kotlin)
  -> ConstRefEngine marks current file as editing and queues the previous file
DeployFileManager.removeChangedFile(Java/Kotlin source-root path)
  -> ConstRefEngine clears pending/session/change state and schedules DB deletion cleanup
DeployFileManager.updateModuleInfos()
  -> register source roots and schedule initial full scan after the 10s startup delay
DeployFileManager.getRecompileFiles() → CompileEffectAnalyzer.getRecompileFiles() → DeployDataGenerator.buildDeployData(original changed source paths)
  -> ConstRefEffectProvider.ensureReadyForRecompile(timeout 5s)
     flush editing file; analyze target paths for this wait, independent of background full scan
  -> ConstRefChangeTracker.peekDefinitionDiff() -> ConstRefImpactResolver queries DB candidates
  -> write `constRefEffectedSourcePaths`; recursive affected-source rounds do not requery original changes
successful deploy → DeployFileManager.commit() → ConstRefEngine.acknowledgeEffectedFilesAfterDeployCommit() consumes definition diff
```

A definition key is `(fqClassName, constName)`. Changed keys represent actual type/value/signature differences, not whitespace edits; removed keys retain old reference candidates when a definition becomes a `val` or is removed from an analyzed file. A whole-file delete instead clears tracker state and schedules DB cleanup; it does not create a source-recompile input for callers. A reference candidate is syntax-only and does not require the target definition to have been scanned first. Java definitions cover inlinable `static final` fields (including interface fields); Kotlin covers top-level, object, companion, and nested `const val`. The resolver matches owner/import/package forms conservatively, including companion and same-package nested owners; an extra recompilation is preferable to a missed inlined constant. Private Java `static final` fields and private Kotlin `const val` definitions do not enter this cross-file index because they affect only the source already in the first compile batch. Parsers ignore comments and string literals.

The impact query *peeks* at changed and removed keys and records paths awaiting acknowledgment. It does not consume them. If dependent compilation or deployment fails, the same difference remains available next Run. Only successful deployment commit clears it. `constRefEffectedSourcePaths` is separate from structural `effectedSourceAndClassNodes`; neither is a deployment result until downstream work succeeds.

`awaitAnalysis()` clears stale completion timestamps before this wait, flushes the editing file, and waits for target files to be analyzed on this timeline. The delayed `FULL_SCAN` is background indexing, not a prerequisite to compile. A timeout reports unready paths and then queries completed cache anyway. Query or readiness exceptions warn and degrade ConstRef for this operation, while the main compile/deploy path continues. An empty affected-path list therefore does not prove no constant references exist.

## 4. Cache and Recovery Boundaries

The shared SQLite cache separates a worktree's `path + mtime -> checksum` baseline from repo-wide `path + checksum -> analysis` versions. Definitions and syntax candidates point to one analysis version; affected-file results are reconstructed only for locally existing paths. The repo fingerprint key normalizes Git `commondir`, allowing checksum reuse across worktrees while each worktree retains its own file baseline. An in-process LRU/TTL cache accelerates recent files and point lookups; cleanup is independent of compilation.

`DeployFileManager` supplies DB paths from `JuggPathManager`. `ConstRefEngine` creates its database/fingerprint runtime lazily at first need, avoiding eager shared DB work when the manager is constructed. Failed initial runtime setup turns ConstRef into a no-op for this process. For an initialized DB, recognized runtime corruption closes/rebuilds the DB and retries the original operation once; if recovery fails, only that operation degrades and later calls can try again. Schema version 7 invalidates older candidate semantics; changes to parser visibility or index meaning require a version bump. Writes to the same DB path serialize across Projects in one process; reads are not serialized by that lock.

Background full scan and file-change analysis can be throttled, whereas pre-compile and on-demand paths default to no delay. SQLite busy/locked delete cleanup has bounded background retries and can requeue a deduplicated path. Scheduling cancellation during reschedule/release is not a runtime failure. These mechanisms cannot make an incomplete analysis a proven negative result.

## 5. Diagnostic Boundaries

| Observation | What it establishes | Next evidence |
|---|---|---|
| `constRefEffectedSourcePaths` is empty | No path was returned by this query; readiness, query failure, or unchanged keys remain possible | Original changed-source paths passed by `CompileEffectAnalyzer`, `ConstRefEngine` definition diff, readiness, and candidate query |
| `analysis not ready` / `awaitAnalysis timeout` | Target analysis was incomplete within the wait; full scan alone is not the compile gate | `unreadyPathCount`, `pendingSourceDirCount`, target file's analysis timeline, completed-cache result |
| `ConstRefEngine effected definition changes` shows `<missing> -> [type:value]` | The old baseline was unavailable; the log cannot establish that this Run changed the constant | Previous DB/version, save/delete timeline, actual old and new source |
| ConstRef recompiles the same source next Run | A failed compile/deploy may have correctly preserved the diff | Whether `DeployFileManager.commit()` and acknowledgment ran after successful deployment |
| `const -> val` or removing a definition within an analyzed file misses callers | The removed-key lookup may be incomplete; whole-file deletion is a different, cleanup-only path | `ConstRefChangeTracker` removed keys, candidate rows, and source-root classification |
| IDE stalls while ConstRef runs | A ConstRef log alone does not locate the blocking owner | Align `compile_latest.log`, `idea.log` or freeze dump, and `ConstRefEngine` timeline; see `09_plugin_runtime_debug.md` |

For cache misses or slow cold scans, start with `ConstRefEngine checksum resolve stats`, `RepoSharedFingerprintStore`, and the per-worktree mtime map. A missing log line is evidence only after confirming the compile log and time span collected for that Run.

## 6. Related Documents

- Class-reference propagation and deploy data: `03_deploy_data_generator.md`
- Compile/deploy scheduling: `02_compile_core.md`, `03_deploy_core.md`
- Verification ownership and test-value gate: `06_testing.md`
- Runtime investigation: `09_plugin_runtime_debug.md`
