# Jugg Constant-Reference Impact Analysis (ConstRefEngine / ConstRefAnalyzer)

> Last checked: 2026-05-23
> Consistency rule: when documentation conflicts with code, follow the code.

---

## 1. Scope

This page describes how constant-reference impact analysis feeds incremental recompilation:

- Which classes handle scanning, caching, and impact queries.
- How the index advances on save, delete, and before compilation.
- Why the SQLite/fingerprint cache can be reused across worktrees.
- Where to begin investigating missed recompilation or excessive analysis time.

For ordinary class-structure impact analysis, see `03_deploy_data_generator.md`.

---

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|
| `DeployFileManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt` | Deployment-facing ConstRef facade for save/delete events, full-scan initialization, pre-compile readiness, and affected-file queries. |
| `DeployDataGenerator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt` | Waits for ConstRef analysis while building `JuggDeployData`, then writes results to `constRefEffectedSourcePaths`. |
| `ConstRefEffectProvider` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/ConstRefEffectProvider.kt` | Narrow interface between `DeployDataGenerator` and `ConstRefEngine`, allowing ConstRef to be disabled or substituted in tests. |
| `ConstRefEngine` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngine.kt` | Lifecycle coordinator for edit-state delay, full scan, pre-compile flush, on-demand analysis, readiness, and impact queries. |
| `ConstRefAnalyzer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefAnalyzer.kt` | Language-neutral parsing wrapper that dispatches Java/Kotlin parsers and serializes Kotlin PSI access. |
| `JavaConstParser` / `KotlinConstParser` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/*ConstParser.kt` | Parse constant definitions and syntax-only reference candidates. |
| `ConstRefChangeTracker` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefChangeTracker.kt` | Records genuinely changed and removed definition keys so whitespace changes do not cause spurious recompilation. |
| `ConstRefImpactResolver` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefImpactResolver.kt` | Consumes changed/removed definition keys and restores affected source files from the DB. |
| `ConstRefCacheDatabase` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabase.kt` | Shared SQLite index for string dictionary, mtime/checksum, analysis head, definitions, and reference candidates. |
| `RepoSharedFingerprintStore` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/RepoSharedFingerprintStore.kt` | Shares checksum fingerprints across a Git repo and its worktrees, reducing repeated cold-start parsing. |
| `ConstRefSessionCache` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefSessionCache.kt` | Session-level LRU/TTL hot cache. |
| `ConstRefCacheCleaner` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheCleaner.kt` | Background TTL, version-cap, and checkpoint/vacuum cleanup. |

---

## 3. Core Data Model

| Data | Source | Consumer | Meaning |
|---|---|---|---|
| `ConstDefinition` | Java/Kotlin parser | `ConstRefChangeTracker`, DB | One inlinable constant definition: file, package, class, constant name, type, and value. |
| `ConstReferenceCandidate` | Java/Kotlin parser | `ConstRefImpactResolver`, DB | Syntax-only reference fact; target const need not have been scanned. |
| `changedDefinitionKeys` | `ConstRefChangeTracker` | `ConstRefImpactResolver` | `(fqClassName, constName)` keys genuinely changed this run. |
| `removedDefinitionKeys` | `ConstRefChangeTracker` | `ConstRefImpactResolver` | Old keys for `const -> val` or a removed constant; old candidate index still finds references. |
| `ConstDefinitionChange` | `ConstRefChangeTracker` | `ConstRefEngine` log | Structured before/after constant signatures for identifying which change triggered dependent recompilation. |
| `AnalysisReadiness` | `ConstRefEngine.awaitAnalysis()` | `DeployDataGenerator` | Whether target files finished this run's analysis before compilation; degradation is permitted if not ready. |
| `JuggDeployData.constRefEffectedSourcePaths` | `DeployDataGenerator` | Compilation loop / logs | Additional source paths ConstRef requires recompiling. |

`ConstReferenceCandidate.ownerKind` is stored as an integer enum in the DB: explicit const import, explicit class import, package wildcard import, class wildcard import, owner-qualified expression, or unqualified same-package reference.

---

## 4. Core Call Chain

### 4.1 Save / Delete / Full-Scan Integration

```text
DeployFileManager.addChangedFile()
  -> Java/Kotlin file: ConstRefEngine.onFileSaved()
  -> enqueue only the previous editing file as pending; mark the current file editing first

DeployFileManager.removeChangedFile()
  -> ConstRefEngine.onFileDeleted()
  -> only `.java` / `.kt` within source roots or source-directory candidates enter ConstRef cleanup
  -> clear memory state + change tracker + session cache
  -> enqueue DB deletion cleanup in the background; skip deletion of non-source build outputs

DeployFileManager.updateModuleInfos()
  -> sourceFileManager.init(sourceDirs)
  -> ConstRefEngine.initializeFullScan(sourceDirs)
  -> delay first full scan by 10s to avoid resource contention during IDE startup
```

`onFileSaved()` delays the current file and queues the previous one to reduce repeated analysis during frequent saves. Before compilation, `awaitAnalysis()` flushes the current editing file.

### 4.2 Pre-Compile Impact Query

```text
DeployFileManager.getRecompileFiles()
  -> DeployDataGenerator.buildDeployData(..., constRefChangedSourcePaths)
  -> ConstRefEffectProvider.ensureReadyForRecompile()
      -> ConstRefEngine.awaitAnalysis(timeout=5s)
      -> flush editing file + analyze target files in PRE_COMPILE
  -> readiness not ready: warn, then use completed cache
  -> ConstRefEffectProvider.getEffectedFiles()
      -> ConstRefChangeTracker.peekDefinitionDiff()
      -> ConstRefImpactResolver.getEffectedFiles()
      -> query DB candidates by constName, then conservatively match owner/package rules
  -> write JuggDeployData.constRefEffectedSourcePaths
  -> after successful deployment, DeployFileManager.commit()
      -> ConstRefEngine.acknowledgeEffectedFilesAfterDeployCommit()
      -> ConstRefChangeTracker.consumeDefinitionDiff()
```

`FULL_SCAN` is no longer a hard pre-compile gate; `awaitAnalysis()` requires only that changed target files reach this run's analysis timeline. Query exceptions return an empty list without blocking the main deployment path.

Recursive dependent-compilation rounds (`isCompilingEffectedSourceFiles=true`) do not pass `constRefChangedSourcePaths` again. ConstRef consumes only the user's original source changes this run, so source files recompiled for structural impact do not trigger another query for the same const-reference batch.

`getEffectedFiles()` only queries and registers a definition diff awaiting acknowledgment; it does not clear it during the query. Only a successful deployment commit acknowledges and clears it, so a failed dependent compilation does not make the next run miss the same const-reference impact.

### 4.3 Cache-Hit Path

```text
analyzeFiles()
  -> file_checksum_mtime_map hit: obtain checksum directly
  -> RepoSharedFingerprintStore hit: reuse checksum across worktrees
  -> both miss: compute CRC32 and write fingerprint back
  -> file_analysis_head hit: touch existing analysis result
  -> analysis miss: parse definitions + reference candidates and store them in DB
```

`ConstRefEngine` does not construct DB paths directly. `DeployFileManager` injects `JuggPathManager.constRefSharedDbFile` and `repoFingerprintDbFile`, and may create the `ConstRefEngine` object at construction time. The SQLite database, repo fingerprint store, and impact resolver belong to `ConstRefEngine`'s internal runtime and must not be initialized in its constructor. They initialize lazily at first need during `updateModuleInfos()`, save/delete events, on-demand analysis, impact query, or commit acknowledgment. If runtime initialization fails, ConstRef is short-circuited to a no-op for this process. If an initialized shared SQLite cache becomes corrupt at runtime, close its connection, delete or move aside DB/WAL/SHM, rebuild the schema, and retry the original operation once. Other runtime exceptions in analysis, impact queries, full scan, cleanup, or scheduling degrade only the current operation; compile/deploy continues and later ConstRef calls may retry.

---
## 5. SQLite and Cache Design

### 5.1 `ConstRefCacheDatabase`

Core tables:

| Table | Purpose |
|---|---|
| `strings` | Global string dictionary for repeated repo/worktree/path/package/class/const/type/value/import strings. |
| `file_checksum_mtime_map` | `(worktree_id, path_id) -> (last_modified, checksum)`, one row per file per worktree. |
| `file_analysis_head` | Analysis-version head keyed by `(repo_id, path_id, checksum)`, supplying `file_id` to child tables. |
| `const_definitions` | Definitions by `file_id`; package/class/const/type/value use `string_id`. |
| `const_references` | Legacy exact-reference table retained for historical queries and tests. |
| `const_reference_candidates` | Syntax-only candidate references by `file_id`; package/const/owner/import use `string_id`, while `owner_kind` uses an integer enum. |
| `maintenance_meta` | Cleanup-throttling metadata. |

Key behaviors:

- `file_analysis_head`, `const_definitions`, and `const_reference_candidates` share analysis results through `file_id`, avoiding repeated long paths in the high-frequency reference index.
- `file_checksum_mtime_map` separates worktree-local baselines by `worktree_id + path_id`.
- The write path preloads string IDs for the current batch, reducing per-row `strings` queries during full scan/batch analysis. In-process `stringIdCache` is a bounded LRU auxiliary cache.
- Within one IDE process, writes share a lock by DB path. Public write entry points and maintenance writes run serially, reducing lock contention when multiple Projects/connections write one global DB. Reads are not additionally serialized.
- An affected-file query first locates definition keys, matches the latest candidate rows, then reconstructs absolute paths for the current worktree; it returns only locally existing files.
- `queryClassesBySimpleNames` supports point lookup through a `simple_class_id + const_name_id` index, avoiding a full-table scan.
- A shared long-lived SQLite connection avoids frequent reconnects. Latest-version selection adds `checksum` as a stable tie-breaker.
- `PRAGMA schema_version=7`; rebuild on incompatibility. Parser visibility-rule or index-semantic changes must bump the version so stale definitions/candidates from the old DB do not enter impact analysis.
- Initialization and runtime DB operations recognize corruption signals such as `SQLITE_CORRUPT`, `SQLITE_NOTADB`, and `database disk image is malformed`. On runtime corruption, delete or bypass the old DB, rebuild, and retry the original operation only once. If rebuild or retry fails, the caller degrades the current operation without blocking normal compilation/deployment.

### 5.2 `RepoSharedFingerprintStore`

- The key combines `repo_key + relative_path + file_size + head/tail(+middle) signature`.
- Git worktrees can share hits because `commondir` normalizes `repo_key`.
- A change in middle content avoids a false hit when head and tail are unchanged.
- Independent cleanup supports TTL, a per-file version cap, and checkpoint/vacuum.

### 5.3 Session Cache and I/O Throttling

`ConstRefSessionCache` uses LRU + TTL:

- `fileCache` stores definitions / legacy references for files accessed in this session.
- `lookupCache` stores point-query results for constName, class+const, package+const, and simpleClassName.

I/O throttling normally affects background tasks only; user-waiting paths do not sleep by default:

| Property | Default |
|---|---|
| `jugg.constref.fullscan.io.throttle.ms` | `3000` |
| `jugg.constref.fullscan.io.throttle.every` | `50` |
| `jugg.constref.filechange.io.throttle.ms` | `500` |
| `jugg.constref.filechange.io.throttle.every` | `200` |
| `jugg.constref.precompile.io.throttle.ms` | `0` |
| `jugg.constref.precompile.io.throttle.every` | `1` |
| `jugg.constref.ondemand.io.throttle.ms` | `0` |
| `jugg.constref.ondemand.io.throttle.every` | `1` |
| `jugg.constref.session.file.cache.max` | `500` |
| `jugg.constref.session.lookup.cache.max` | `4000` |
| `jugg.constref.session.cache.ttl.ms` | `900_000` |

`jugg.constref.io.throttle.ms` / `jugg.constref.io.throttle.every` remain available as compatibility fallback, at lower priority than scenario-specific properties.

---

## 6. Hidden Constraints

- Reference scanning does not query definitions or require the target const to have been scanned. The impact query conservatively matches changed definitions with syntax candidates: extra compilation is acceptable, missed compilation is not.
- A companion const matches both `Owner.CONST` and `Owner.Companion.CONST` forms.
- A qualified reference to a nested class/object in the same package uses a relative owner, such as `Outer.Inner.CONST`, to match the definition's fully qualified owner.
- If `const` becomes an ordinary `val` or is deleted, `removedDefinitionKeys` still hits the old candidate index.
- `awaitAnalysis()` succeeds when target-file `analyzedAt >= wait start time`; full-scan readiness no longer blocks compilation.
- Exceptions on user-waiting or query paths such as `ensureReadyForRecompile()`, `analyzeOnDemand()`, and `getEffectedFiles()` emit warnings and continue with degraded behavior.
- If not ready, emit a warning and query the current cache anyway.
- If `getEffectedFiles()` throws, warn and return an empty list without blocking the main deployment path.
- A const-reference definition diff clears on commit acknowledgment after successful deployment, not on the impact query itself. After compilation, dependent compilation, or deployment fails, the same diff remains queryable on the next compile.
- `private const val` and `private static final` do not enter the definition index or diff. They affect only their declaring source file, which is already in the first compilation batch, so no additional dependent recompilation is needed.
- Exceptions in background full scan, file-change analysis, cache cleanup, or delete cleanup are debug-only and do not affect incremental compilation. SQLite busy/locked during delete cleanup gets bounded background retries; if it still fails, deduplicate and requeue it later.
- ConstRef is optional. Failed DB/fingerprint-store initialization degrades this process's ConstRef runtime to a no-op. Runtime SQLite corruption first triggers one delete/rebuild and retry of the original operation. If that still fails, full scan, save/delete analysis, pre-compile readiness, on-demand analysis, affected-file query, cleanup, and commit acknowledgment affect only their current operations: they must not break the main Run/compile/deploy path or permanently disable an initialized runtime. Scheduling cancellation, such as `CancellationException`, is normal rescheduling/release rather than a runtime fault.
- Java records only `static final` fields of inlinable types. Kotlin supports top-level, object, companion, and nested class/object `const val`.
- Java/Kotlin parsers ignore pseudo-references inside comments and string literals.

---

## 7. Investigation Entry Points

| Symptom | Start with |
|---|---|
| `constRefEffectedSourcePaths` is empty | Check whether `DeployFileManager.getRecompileFiles()` passed changed source, and `constRefEffectProvider.getEffectedFiles()` in `DeployDataGenerator`. |
| Result appears stale | `ConstRefEngine.awaitAnalysis()`, and `unreadyPathCount` in `analysis not ready` / `awaitAnalysis timeout` logs. |
| Delete or `const -> val` does not trigger recompilation | Removed keys in `ConstRefChangeTracker`, and `ConstRefImpactResolver.getEffectedFiles()`. |
| Whitespace changes trigger excessive recompilation | Whether `ConstRefChangeTracker.consumeDefinitionDiff()` produced changed keys. |
| The same affected sources recur | When `ConstRefChangeTracker` clears changes, and whether the DB-reuse hit is stale. |
| Cold scan is slow in a large repo | Hits/writes in `RepoSharedFingerprintStore`, and `ConstRefCacheDatabase.findReusablePathsByLastModified()`. |
| Global cache has no effect | Whether `JuggPathManager.constRefSharedDbFile` and `repoFingerprintDbFile` were created. |
| IDE appears frozen by ConstRef | `09_plugin_runtime_debug.md`; align `compile_latest.log`, `idea.log` / freeze dump, and the `ConstRefEngine` timeline. |

Key logs (`build/jugg/log/compile_latest.log`):

- `ConstRefEngine checksum resolve stats`
- `ConstRefEngine effected definition changes`: prints `fqClass.const: [type:old] -> [type:new]`; a missing old baseline shows `<missing> -> [type:new]`, meaning this run cannot establish that a real source edit changed the value.
- `const ref effected source files`
- `Compile success, but found effected source files, continue compile`
- `analysis not ready` / `awaitAnalysis timeout`

---

## 8. Test Locations

| Test file | Focus |
|---|---|
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngineTest.kt` | Edit-state delay, await flush, deletion cleanup, removed keys, full scan not blocking readiness, on-demand degradation. |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefIntegrationTest.kt` | Cold-start full scan, companion const, same-package nested object const, no false positives from unrelated classes. |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefAnalyzerTest.kt` | Serialized concurrent access to Java/Kotlin parsers. |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/JavaConstParserTest.kt` | Java definitions/references, annotation constants, ignoring comments/strings. |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/KotlinConstParserTest.kt` | Kotlin aliases/wildcard imports, same-package resolution, ignoring comments/strings. |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabaseTest.kt` | DB upsert/query, mtime mapping, cleanup, DB-first batch query. |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/RepoSharedFingerprintStoreTest.kt` | mtime hit, miss on middle-content change, worktree sharing, cleanup. |

---

## 9. Related Documents

- Deployment core: `03_deploy_core.md`
- Impact analysis and deployment-data generation: `03_deploy_data_generator.md`
- Main compilation flow: `02_compile_core.md`
- Runtime investigation: `09_plugin_runtime_debug.md`
- Test strategy: `06_testing.md`
