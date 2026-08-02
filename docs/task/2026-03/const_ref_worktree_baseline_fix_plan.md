# ConstRef Worktree Baseline Fix Plan

> Date: 2026-03-04
> Status: Draft
> Scope: `compiler/constref/ConstRefCacheDatabase.kt`, `compiler/constref/ConstRefEngine.kt`

---

## 1. Problem

### 1.1 Root Cause

`loadPreviousDefinitionsLocked` determines the "baseline" (previous definitions) for detecting const changes. After a cold restart (IDE restart), the in-memory cache is empty, and the fallback queries DB using `queryLatestChecksum` which picks the version with the highest `analyzed_at`. This is semantically wrong:

- **"Last analysed" ≠ "last compiled by this project"**
- The shared DB (`repo_key` unified via `commondir`) mixes analysis records from different worktrees

### 1.2 Affected Scenarios

**Scenario A: Single project A→B→A + restart**

1. File has content A (`const X = 1`), analysed, DB stores `checksum_A` at `analyzed_at = t1`
2. Changed to B (`const X = 2`), analysed, DB stores `checksum_B` at `analyzed_at = t2`, compiled successfully
3. IDE restart, memory cleared
4. File reverted to A
5. `touchFileAnalysis` hits `checksum_A` (old DB record)
6. `loadPreviousDefinitionsLocked` → `queryLatestChecksum` → returns `checksum_B` (highest `analyzed_at`)
7. **legacy mode**: `cachedDefinitionsByFile` is empty → returns `[]` → all consts treated as "new" → **false positive** (over-recompilation)
8. **db_session mode**: returns `defs_B` → `defs_B ≠ defs_A` → correctly triggers recompile, but **by coincidence**, not by design

**Scenario B: Cross-worktree (Project A compiled, Project B pulls same change)**

1. Project A: modifies `const X = 1→2`, analyses, compiles successfully, DB stores `checksum_B`
2. Project B: `git pull` receives the same change, file content matches `checksum_B`
3. Project B: full scan → `touchFileAnalysis` hits `checksum_B` (shared DB record from A)
4. `loadPreviousDefinitionsLocked` → DB latest is `checksum_B` → `previous == current` → **no change detected**
5. **Bug**: Project B's device still runs old version, but no recompilation triggered

### 1.3 Core Issue

`file_checksum_mtime_map` uses `repo_key` (shared across worktrees) as key dimension. This means:
- Different worktrees share the same mtime→checksum mapping
- Cannot distinguish "has **this project** seen this file version before"

---

## 2. Solution

**Isolate `file_checksum_mtime_map` by worktree; use its checksum as the previous-definitions baseline on cold start.**

No new tables needed. Two changes only.

### 2.1 Change 1: `file_checksum_mtime_map` scoped by worktree, one row per file per project

**Before:**
```sql
CREATE TABLE IF NOT EXISTS file_checksum_mtime_map (
    repo_key      TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    last_modified INTEGER NOT NULL,
    checksum      INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL,
    PRIMARY KEY (repo_key, relative_path, last_modified)
);
```

**After:**
```sql
CREATE TABLE IF NOT EXISTS file_checksum_mtime_map (
    worktree_key  TEXT NOT NULL,
    repo_key      TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    last_modified INTEGER NOT NULL,
    checksum      INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL,
    PRIMARY KEY (worktree_key, relative_path)
);
```

Key differences:
- `worktree_key` = `worktreeRoot.canonicalPath` (project-specific, NOT unified via `commondir`)
- Primary key is `(worktree_key, relative_path)` — one row per file per project
- Each upsert **overwrites** the previous record, so it always reflects "the last checksum this project saw for this file"

All other tables (`file_analysis_head`, `const_definitions`, `const_references`) keep using `repo_key` for cross-worktree sharing of analysis results.

### 2.2 Change 2: `loadPreviousDefinitionsLocked` fallback uses mtime_map checksum

**Before:**
```kotlin
private fun loadPreviousDefinitionsLocked(filePath: String): List<ConstDefinition> {
    if (lookupMode == LookupMode.LEGACY) {
        return cachedDefinitionsByFile[filePath].orEmpty()
    }
    val cachedDefinitions = sessionCache.getFileDefinitions(filePath)
    if (cachedDefinitions != null) {
        return cachedDefinitions
    }
    val definitions = database.getLatestDefinitionsByFile(filePath)  // ← uses analyzed_at DESC
    if (definitions.isNotEmpty()) {
        sessionCache.putFileDefinitions(filePath, definitions)
    }
    return definitions
}
```

**After:**
```kotlin
private fun loadPreviousDefinitionsLocked(filePath: String): List<ConstDefinition> {
    // 1. In-session memory cache (unchanged for both modes)
    if (lookupMode == LookupMode.LEGACY) {
        val cached = cachedDefinitionsByFile[filePath]
        if (cached != null) return cached
    } else {
        val cached = sessionCache.getFileDefinitions(filePath)
        if (cached != null) return cached
    }
    // 2. Cold start: use this project's mtime_map checksum as baseline
    val previousChecksum = database.getMtimeMapChecksum(filePath)
    if (previousChecksum != null) {
        val defs = database.getDefinitionsByFileAndChecksum(filePath, previousChecksum)
        updatePreviousDefinitionsLocked(filePath, defs)
        return defs
    }
    // 3. This project has never seen this file → empty (safe: all consts treated as new)
    return emptyList()
}
```

New DB method needed:
```kotlin
// Query this project's last-known checksum for a file from mtime_map
fun getMtimeMapChecksum(filePath: String): Long?
```

### 2.3 `RepoFileIdentity` exposes `worktreeKey`

```kotlin
internal data class RepoFileIdentity(
    val repoKey: String,        // commondir-unified, for definitions/references sharing
    val worktreeKey: String,    // worktreeRoot path, for mtime_map isolation
    val relativePath: String,
    val worktreeRoot: File,
)
```

`worktreeKey` = `worktreeRoot.canonicalPath`, always project-specific.

---

## 3. Scenario Verification

### 3.1 Single project A→B→A + restart

| Step | mtime_map (worktree=A) | Behavior |
|------|------------------------|----------|
| File A, analysed | `checksum_A` | — |
| Changed to B, analysed | `checksum_B` (overwritten) | in-memory diff: defs_A vs defs_B → changed key |
| Compile success, diff consumed | — | changeTracker cleared |
| **Restart**, file reverted to A | mtime_map still has `checksum_B` | — |
| Analysis: CRC → `checksum_A`, `touchFileAnalysis` hits | — | — |
| `loadPreviousDefinitionsLocked` | memory empty → mtime_map → `checksum_B` → **defs_B** | — |
| Compare: defs_B vs defs_A | Different → **triggers recompile** ✅ | — |

### 3.2 Cross-worktree: A compiles, B pulls

| Step | mtime_map | Behavior |
|------|-----------|----------|
| Project A: X=1→2, analysed | `(worktree=A) → checksum_B` | — |
| Project A: compile success | — | — |
| Project B: pull, file becomes X=2 | B's mtime_map: **no record** | — |
| Project B: full scan | CRC → `checksum_B`, `touchFileAnalysis` hits (shared) | — |
| `loadPreviousDefinitionsLocked` | mtime_map(worktree=B) → **null** → returns `[]` | — |
| Compare: `[]` vs defs_B | Different → **triggers recompile** ✅ | First-time safe over-report |
| After analysis | mtime_map(worktree=B) → `checksum_B` | Subsequent diffs are precise |

### 3.3 Whitespace-only change (no const change)

| Step | Behavior |
|------|----------|
| File analysed, mtime_map has `checksum_A` | — |
| Whitespace edit, mtime changes | mtime_map miss → CRC → `checksum_A` (content unchanged) |
| `touchFileAnalysis` hits | — |
| `loadPreviousDefinitionsLocked` → mtime_map → `checksum_A` → defs_A | — |
| Compare: defs_A vs defs_A | Same → **no trigger** ✅ | |

### 3.4 In-session A→B (no restart)

| Step | Behavior |
|------|----------|
| In-memory previous = defs_A | — |
| Changed to B, analysed | `loadPreviousDefinitionsLocked` → memory hit → defs_A |
| diff(defs_A, defs_B) | Normal behavior, unchanged ✅ |

---

## 4. Impact Summary

| Aspect | Detail |
|--------|--------|
| Tables modified | `file_checksum_mtime_map` only (PK change + add `worktree_key`) |
| Tables added | None |
| New DB methods | `getMtimeMapChecksum(filePath): Long?` |
| Modified methods | `loadPreviousDefinitionsLocked`, `upsertMtimeMap`, `getChecksumByLastModified` |
| Schema version | Bump `PRAGMA schema_version` from 2 to 3 (triggers DB recreate) |
| `RepoFileIdentity` | Add `worktreeKey` field |
| Shared analysis reuse | Unaffected — `file_analysis_head` / `const_definitions` / `const_references` still use `repo_key` |
| Write frequency | Unchanged — mtime_map upsert happens during analysis as before |
| First-time behavior | New project seeing a file for the first time: all consts treated as "new" (safe over-report, one-time only) |

---

## 5. Migration

Schema version bump from 2 to 3. Existing behavior in `ConstRefCacheDatabase.init()` already handles incompatible schema by deleting and recreating the DB. This is acceptable because:

- Analysis cache is a performance optimization, not critical data
- Full scan will repopulate the DB on next IDE session
- No user-visible impact beyond a one-time full scan cost

---

## 6. Test Plan

### 6.1 Unit tests (ConstRefEngineTest / ConstRefCacheDatabaseTest)

- [ ] mtime_map isolation: two worktree keys writing same `relative_path` produce independent rows
- [ ] `getMtimeMapChecksum` returns correct checksum per worktree
- [ ] `loadPreviousDefinitionsLocked` cold start: returns defs from mtime_map checksum (not `analyzed_at DESC`)
- [ ] Cross-worktree: project B has no mtime_map record → returns empty → all consts flagged as changed
- [ ] A→B→A + restart: mtime_map retains checksum_B → correctly detects B→A change
- [ ] Whitespace-only change: same checksum → no false trigger

### 6.2 Integration tests (ConstRefIntegrationTest)

- [ ] Full scan after restart with pre-existing DB: baseline correctly loaded from mtime_map
- [ ] Multi-round compile loop: changeTracker consumed correctly, no stale keys after restart

### 6.3 Regression

- [ ] All existing `constref` tests pass: `./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.constref.*"`
