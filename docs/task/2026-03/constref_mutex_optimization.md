# ConstRefEngine analysisMutex Contention Optimization

## Problem

`analyzeOnDemand` (1 file) is blocked by full scan's `analysisMutex.withLock` for up to 60s.
Full scan holds the mutex per-batch (up to 50 files), including heavy AST parsing inside the lock.

## Root Cause

In `analyzeFiles`, all 4 lock sites mix **CPU-intensive work** (AST parsing) with **shared-state mutations** (DB/cache writes) inside the same `analysisMutex.withLock` block. Full scan processes 50-file batches, causing long lock hold times that starve on-demand analysis of single files.

## Current Lock Anatomy

### Checksum phase (line 405): per-file lock — OK
Each file locks independently, hold time is short (checksums + DB touch). No change needed.

### Phase 1 (line 461): per-batch lock — **problematic**
```kotlin
analysisMutex.withLock {          // holds lock for entire batch
    batch.forEach { file ->
        loadPreviousDefinitionsLocked(path)          // shared state read
        analyzer.parseDefinitions(listOf(file))      // ← HEAVY CPU, no shared state needed
        // build entry ...
        updatePreviousDefinitionsLocked(path, ...)   // shared state write
    }
    database.upsertBatchDefinitions(definitionsBatch) // shared state write
}
```

`analyzer.parseDefinitions` is pure computation (reads file → builds AST → extracts definitions).
It does NOT access `database`, `sessionCache`, or `changeTracker`.

### Phase 2 (line 500): per-batch lock — **most problematic**
```kotlin
analysisMutex.withLock {          // holds lock for entire batch
    batch.forEach { file ->
        loadPreviousDefinitionsLocked(path)           // shared state read
        database.getDefinitionsByFileAndChecksum(...)  // shared state read
        sessionCache.clearLookupCache()                // shared state write
        parseReferencesByDbOnly(file)                  // ← HEAVY: AST parse + DB queries
        // build entry ...
    }
    database.upsertBatchAnalysis(analysisBatch)        // shared state write
    pendingStates.forEach { ... }                      // shared state writes
}
```

`parseReferencesByDbOnly` internally calls:
- `analyzer.collectHintsAndParseReferences(file)` — **AST parsing** (CPU-heavy, no shared state)
  - callback: `queryCandidateDefinitionsForFile` — reads `database` + `sessionCache`

## Proposed Solution: Extract CPU work out of lock

Core idea: **split each batch iteration into lock→unlock→lock cycles**, moving AST parsing to the unlocked window.

### Phase 1 Refactoring

```kotlin
// Before (all under one lock):
analysisMutex.withLock { batch.forEach { ... parseDefinitions ... } }

// After (per-file lock/unlock cycle):
changedFiles.chunked(analyzeFilesBatchSize).forEach { batch ->
    data class Phase1FileState(
        val path: String,
        val file: File,
        val previousDefinitions: List<ConstDefinition>,
        val checksum: Long,
    )
    // Step 1: read shared state under lock (fast, per-file)
    val pendingFiles = mutableListOf<Phase1FileState>()
    batch.forEach { file ->
        val state = analysisMutex.withLock {
            val path = file.toStdPath()
            val previousDefinitions = loadPreviousDefinitionsLocked(path)
            Phase1FileState(path, file, previousDefinitions, checksumMap[path] ?: calculateChecksum(file))
        }
        pendingFiles += state
    }
    // Step 2: parse definitions WITHOUT lock (CPU-intensive)
    val definitionsBatch = mutableListOf<ConstRefCacheDatabase.FileDefinitionsEntry>()
    pendingFiles.forEach { state ->
        val definitions = analyzer.parseDefinitions(listOf(state.file))[state.path].orEmpty()
        definitionsBatch += ConstRefCacheDatabase.FileDefinitionsEntry(
            filePath = state.path,
            lastModified = state.file.lastModified(),
            checksum = state.checksum,
            definitions = definitions,
        )
    }
    // Step 3: write results under lock (fast batch DB write)
    analysisMutex.withLock {
        database.upsertBatchDefinitions(definitionsBatch)
        pendingFiles.forEachIndexed { i, state ->
            updatePreviousDefinitionsLocked(state.path, state.previousDefinitions)
        }
    }
    analyzer.resetEnvironment()
    phase1ProcessedCount += batch.size
    maybeThrottleIo(phase1ProcessedCount)
}
```

### Phase 2 Refactoring

Phase 2 is harder because `parseReferencesByDbOnly` internally queries `database`/`sessionCache` via `queryCandidateDefinitionsForFile`. Two options:

**Option A (simpler, per-file lock granularity):**
Convert from per-batch lock to per-file lock. Each file's lock hold time is short individually.

```kotlin
changedFiles.chunked(analyzeFilesBatchSize).forEach { batch ->
    val analysisBatch = mutableListOf<ConstRefCacheDatabase.FileAnalysisEntry>()
    data class FilePendingState(...)
    val pendingStates = mutableListOf<FilePendingState>()

    // Per-file: lock → read state + parse refs → unlock
    batch.forEach { file ->
        analysisMutex.withLock {
            val path = file.toStdPath()
            val previousDefinitions = loadPreviousDefinitionsLocked(path)
            val definitions = database.getDefinitionsByFileAndChecksum(
                path, checksumMap[path] ?: calculateChecksum(file),
            )
            sessionCache.clearLookupCache()
            val references = parseReferencesByDbOnly(file)
            analysisBatch += ConstRefCacheDatabase.FileAnalysisEntry(
                filePath = path,
                lastModified = file.lastModified(),
                checksum = checksumMap[path] ?: calculateChecksum(file),
                definitions = definitions,
                references = references,
            )
            pendingStates += FilePendingState(path, file, previousDefinitions, definitions, references)
        }
        // Lock released between files — on-demand can acquire here
    }
    // Final batch write + state updates
    analysisMutex.withLock {
        database.upsertBatchAnalysis(analysisBatch)
        pendingStates.forEach { state ->
            changeTracker.updateDefinitionDiff(
                filePath = state.path,
                previousDefinitions = state.previousDefinitions,
                currentDefinitions = state.definitions,
            )
            sessionCache.putFileAnalysis(
                filePath = state.path,
                lastModified = state.file.lastModified(),
                checksum = checksumMap[state.path] ?: 0L,
                definitions = state.definitions,
                references = state.references,
            )
            markAnalyzed(state.path)
            sessionCache.clearLookupCache()
        }
    }
    analyzer.resetEnvironment()
    phase2ProcessedCount += batch.size
    maybeThrottleIo(phase2ProcessedCount)
}
```

**Option B (more aggressive, extract AST out of lock):**
Split `parseReferencesByDbOnly` into two steps:
1. Lock: query candidate definitions from DB/cache
2. Unlock: AST parse with the definitions snapshot
3. Lock: write results

```kotlin
batch.forEach { file ->
    val path = file.toStdPath()
    // Step 1: read shared state under lock
    val (previousDefinitions, definitions, hints) = analysisMutex.withLock {
        val prev = loadPreviousDefinitionsLocked(path)
        val defs = database.getDefinitionsByFileAndChecksum(
            path, checksumMap[path] ?: calculateChecksum(file),
        )
        sessionCache.clearLookupCache()
        Triple(prev, defs, null) // hints are collected during AST parse
    }
    // Step 2: parse AST and collect hints WITHOUT lock
    val references = analyzer.collectHintsAndParseReferences(file) { hints ->
        // Only this callback needs shared state — acquire lock briefly
        analysisMutex.withLock {
            val candidateDefinitions = queryCandidateDefinitionsForFile(
                filePath = path, hints = hints,
            )
            if (candidateDefinitions.isEmpty()) null
            else ConstDefinitionIndex(candidateDefinitions)
        }
    }
    // Collect for batch write...
    pendingStates += FilePendingState(path, file, previousDefinitions, definitions, references)
}
// Step 3: batch write under lock (same as Option A)
```

## Recommendation

**Phase 1: Use the extract-CPU-out approach** (straightforward, `parseDefinitions` has zero shared-state dependency).

**Phase 2: Use Option A (per-file lock)** as the first step.
- Simpler to implement, no need to restructure `parseReferencesByDbOnly`
- Reduces max lock hold time from `O(batchSize * perFileCost)` to `O(perFileCost)`
- For on-demand (1 file), worst case wait = 1 file's Phase 2 processing (~tens of ms)
- Option B can be a follow-up if per-file granularity is still insufficient

## Expected Impact

| Metric | Before | After |
|--------|--------|-------|
| Max mutex hold time (Phase 1) | 50 files × ~10ms parse = ~500ms | ~50ms (batch DB write only) |
| Max mutex hold time (Phase 2) | 50 files × ~20ms parse = ~1000ms | 1 file × ~20ms parse = ~20ms |
| on-demand wait for mutex | up to 60s (queued behind full scan batch) | < 100ms (next file boundary) |

## Correctness Notes

- **Phase 1 extract-CPU**: Safe. `analyzer.parseDefinitions` is stateless — reads file, returns definitions. No shared state accessed.
- **Phase 2 per-file lock (Option A)**: Correctness is preserved because each file's lock scope still covers its full read→compute→prepare cycle. The batch DB write is a separate lock scope but `markAnalyzed` happens there, so `awaitAnalysis` observers see consistent data.
- **`upsertBatchAnalysis` atomicity**: Currently the batch DB write + `markAnalyzed` are in the same lock scope. In Option A they are separated into a second lock acquisition. Between the two lock acquisitions, another coroutine could read partially stale data. However, `awaitAnalysis` checks `analyzedAt` which is set inside the second lock, so it will not see data as ready until the write is complete. This is safe.
