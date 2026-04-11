# Guide: Writing Auto-Run Entry Code

Auto-run entry = a method guaranteed to execute after app launch. Agent places debug/verification code here to navigate, reproduce scenarios, and verify behavior automatically.

---

## Quick Start {#quick-start}

An auto-run entry is a method in a known entry point that the app calls on launch. Code placed here should:

1. Navigate to the target page.
2. Wait for the page to stabilize.
3. Execute verification or reproduction actions.
4. Output structured log results.

---

## Auto-Run Code Structure Template

Every auto-run code block follows this 4-phase pattern:

```kotlin
// Phase 1: Navigate
Log.d(TAG, "[JUGG_AR] START: navigating to TargetActivity")
startActivity(Intent(this, TargetActivity::class.java))

// Phase 2: Wait for stability
val maxWait = 10_000L  // timeout in ms
val interval = 500L
var elapsed = 0L
while (!isPageReady() && elapsed < maxWait) {
    delay(interval)
    elapsed += interval
    Log.d(TAG, "[JUGG_AR] WAITING: ${elapsed}ms elapsed")
}
if (elapsed >= maxWait) {
    Log.e(TAG, "[JUGG_AR] TIMEOUT: page not ready after ${maxWait}ms")
    return  // abort gracefully
}
Log.d(TAG, "[JUGG_AR] PAGE_READY: ${elapsed}ms")

// Phase 3: Execute actions
try {
    val result = performVerification()
    Log.d(TAG, "[JUGG_AR] RESULT: $result")
} catch (e: Exception) {
    Log.e(TAG, "[JUGG_AR] ERROR: ${e.message}", e)
    return  // abort on exception
}

// Phase 4: Report
Log.d(TAG, "[JUGG_AR] DONE: all checks completed")
```

---

## Mandatory Flows

Every auto-run code block must implement these 4 concerns:

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
    log "[JUGG_AR] TIMEOUT: <context>"
    abort
```

- Default timeout: 10s for page navigation, 30s for data-heavy operations.
- Always log the timeout context (what was being waited for).

### 3. Exception Flow

```
try { ... } catch (e: Exception) {
    log "[JUGG_AR] ERROR: <message>"
    abort
}
```

- Catch at action boundaries, not globally.
- Log exception class + message at minimum.

### 4. Logging Flow

All phases must log with the `[JUGG_AR]` prefix. Required markers:

| Marker | When | Example |
|--------|------|---------|
| `[JUGG_AR] START` | Auto-run begins | `[JUGG_AR] START: navigating to SettingsActivity` |
| `[JUGG_AR] WAITING` | Each wait iteration | `[JUGG_AR] WAITING: 1500ms elapsed` |
| `[JUGG_AR] PAGE_READY` | Page stabilized | `[JUGG_AR] PAGE_READY: 2000ms` |
| `[JUGG_AR] RESULT` | Verification outcome | `[JUGG_AR] RESULT: text="Confirm" matches expected` |
| `[JUGG_AR] TIMEOUT` | Wait exceeded limit | `[JUGG_AR] TIMEOUT: list not loaded after 10000ms` |
| `[JUGG_AR] ERROR` | Exception caught | `[JUGG_AR] ERROR: NullPointerException at line 42` |
| `[JUGG_AR] DONE` | All checks completed | `[JUGG_AR] DONE: 3/3 checks passed` |

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

After auto-run execution, agent collects results:

```bash
# Filter auto-run logs
adb logcat -d -s <TAG> | grep -E "\\[JUGG_AR\\]"

# Or with broader regex
adb logcat -d | grep -E "\\[JUGG_AR\\] (RESULT|ERROR|TIMEOUT|DONE)"
```

Agent parses markers to determine: success (`DONE` + no `ERROR`/`TIMEOUT`) or failure.
