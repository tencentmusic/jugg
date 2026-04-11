# Guide: Writing Playground Code

Playground = code that auto-executes after app launch to navigate, reproduce scenarios, and verify behavior.

---

## Quick Start {#quick-start}

A playground is a code block placed in a known entry point that the app executes on launch. It should:

1. Navigate to the target page.
2. Wait for the page to stabilize.
3. Execute verification or reproduction actions.
4. Output structured log results.

---

## Playground Structure Template

Every playground follows this 4-phase pattern:

```kotlin
// Phase 1: Navigate
Log.d(TAG, "[JUGG_PG] START: navigating to TargetActivity")
startActivity(Intent(this, TargetActivity::class.java))

// Phase 2: Wait for stability
val maxWait = 10_000L  // timeout in ms
val interval = 500L
var elapsed = 0L
while (!isPageReady() && elapsed < maxWait) {
    delay(interval)
    elapsed += interval
    Log.d(TAG, "[JUGG_PG] WAITING: ${elapsed}ms elapsed")
}
if (elapsed >= maxWait) {
    Log.e(TAG, "[JUGG_PG] TIMEOUT: page not ready after ${maxWait}ms")
    return  // abort gracefully
}
Log.d(TAG, "[JUGG_PG] PAGE_READY: ${elapsed}ms")

// Phase 3: Execute actions
try {
    val result = performVerification()
    Log.d(TAG, "[JUGG_PG] RESULT: $result")
} catch (e: Exception) {
    Log.e(TAG, "[JUGG_PG] ERROR: ${e.message}", e)
    return  // abort on exception
}

// Phase 4: Report
Log.d(TAG, "[JUGG_PG] DONE: all checks completed")
```

---

## Mandatory Flows

Every playground must implement these 4 concerns:

### 1. Wait Flow

```
while (condition not met AND not timed out):
    sleep(interval)
    log progress
```

- `isPageReady()`: check via `Activity.isResumed`, data loaded callback, or view existence.
- Interval: 300-500ms. Avoid busy-wait.

### 2. Timeout Flow

```
if elapsed >= maxWait:
    log "[JUGG_PG] TIMEOUT: <context>"
    abort
```

- Default timeout: 10s for page navigation, 30s for data-heavy operations.
- Always log the timeout context (what was being waited for).

### 3. Exception Flow

```
try { ... } catch (e: Exception) {
    log "[JUGG_PG] ERROR: <message>"
    abort
}
```

- Catch at action boundaries, not globally.
- Log exception class + message at minimum.

### 4. Logging Flow

All phases must log with the `[JUGG_PG]` prefix. Required markers:

| Marker | When | Example |
|--------|------|---------|
| `[JUGG_PG] START` | Playground begins | `[JUGG_PG] START: navigating to SettingsActivity` |
| `[JUGG_PG] WAITING` | Each wait iteration | `[JUGG_PG] WAITING: 1500ms elapsed` |
| `[JUGG_PG] PAGE_READY` | Page stabilized | `[JUGG_PG] PAGE_READY: 2000ms` |
| `[JUGG_PG] RESULT` | Verification outcome | `[JUGG_PG] RESULT: text="Confirm" matches expected` |
| `[JUGG_PG] TIMEOUT` | Wait exceeded limit | `[JUGG_PG] TIMEOUT: list not loaded after 10000ms` |
| `[JUGG_PG] ERROR` | Exception caught | `[JUGG_PG] ERROR: NullPointerException at line 42` |
| `[JUGG_PG] DONE` | All checks completed | `[JUGG_PG] DONE: 3/3 checks passed` |

---

## Wait Condition Examples

| Scenario | Condition |
|----------|-----------|
| Page visible | `activity.lifecycle.currentState.isAtLeast(RESUMED)` |
| RecyclerView loaded | `recyclerView.adapter?.itemCount ?: 0 > 0` |
| Network data ready | `viewModel.data.value != null` |
| Specific view exists | `findViewById<View>(R.id.target) != null` |

---

## Log Collection by Agent

After playground execution, agent collects results:

```bash
# Filter playground logs
adb logcat -d -s <TAG> | grep -E "\\[JUGG_PG\\]"

# Or with broader regex
adb logcat -d | grep -E "\\[JUGG_PG\\] (RESULT|ERROR|TIMEOUT|DONE)"
```

Agent parses markers to determine: success (`DONE` + no `ERROR`/`TIMEOUT`) or failure.
