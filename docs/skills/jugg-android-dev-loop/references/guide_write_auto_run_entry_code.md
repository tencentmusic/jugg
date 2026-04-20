# Guide: Writing Auto-Run Entry Code

Auto-run entry = a method guaranteed to execute after app launch. Agent places debug/verification code here to navigate, reproduce scenarios, and verify behavior automatically.

---

## Locating the Entry (Required Before Writing Code)

The entry's fully-qualified location **must be supplied by the user**; it is not auto-discoverable. Example prompt the user should provide:

> "本工程调试入口/自动调试入口/自动运行调试入口为 `com.myapp.Test.run`"

Rules:

- Do **not** guess the entry. If the user has not declared it in the prompt or visible context, stop and ask: *"请提供自动调试入口，例如 `com.myapp.Test.run`。"*
- Once provided, all auto-run code in this conversation goes into that method.

---

## Fixed Conventions

| Item | Value | Notes |
|------|-------|-------|
| Log TAG | `"jugg"` | All `Log.*` calls in auto-run code **must** use this TAG. |
| Marker prefix | `[JUGG_AR]` | Every log line produced by auto-run code starts with this prefix. |
| End-of-run marker | `[JUGG_AR] DONE` | **Must be printed in every exit branch** (success / failure / timeout / exception). Verdict is carried by `RESULT` / `ERROR` / `TIMEOUT`, not by whether `DONE` fires. |
| Blocking work thread | non-UI thread | Any `sleep`/`wait` must run off the main thread (see template). |

### Required markers

| Marker | When to emit |
|--------|--------------|
| `[JUGG_AR] START` | Auto-run begins |
| `[JUGG_AR] PAGE_READY` | Page stabilized |
| `[JUGG_AR] RESULT`   | Verification outcome (pass or fail detail) |
| `[JUGG_AR] TIMEOUT`  | Wait exceeded limit |
| `[JUGG_AR] ERROR`    | Exception caught |
| `[JUGG_AR] DONE`     | Auto-run exits (always, in every branch) |

---

## Code Template (self-contained, compilable)

The template uses `Thread` + `Thread.sleep` to avoid coroutine dependencies, and guarantees `[JUGG_AR] DONE` via a `finally` block.

### Kotlin

```kotlin
// Inside the auto-run entry method body (e.g. com.myapp.Test.run)
// `context` must be an Android Context available at entry. Replace with the actual
// accessor exposed by your entry class (Application / Activity / static holder).
val TAG = "jugg"

Thread {
    try {
        Log.d(TAG, "[JUGG_AR] START: navigating to TargetActivity")

        // Phase 1: Navigate
        val intent = Intent(context, TargetActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        // Phase 2: Wait for page stability (off main thread; Thread.sleep is safe here)
        val maxWaitMs = 10_000L
        val intervalMs = 500L
        var elapsed = 0L
        while (!isPageReady() && elapsed < maxWaitMs) {
            Thread.sleep(intervalMs)
            elapsed += intervalMs
        }
        if (elapsed >= maxWaitMs) {
            Log.e(TAG, "[JUGG_AR] TIMEOUT: page not ready after ${maxWaitMs}ms")
            return@Thread
        }
        Log.d(TAG, "[JUGG_AR] PAGE_READY: ${elapsed}ms")

        // Phase 3: Execute verification on whichever thread the check requires
        val result = performVerification()
        Log.d(TAG, "[JUGG_AR] RESULT: $result")
    } catch (e: Throwable) {
        Log.e(TAG, "[JUGG_AR] ERROR: ${e.javaClass.simpleName}: ${e.message}", e)
    } finally {
        // Phase 4: Always signal end so `wait-logs --marker '[JUGG_AR] DONE'` converges
        Log.d(TAG, "[JUGG_AR] DONE")
    }
}.start()
```

Placeholders `isPageReady()` / `performVerification()` / `TargetActivity` are **not** real APIs — replace them with real checks for the current task before compiling.

---

## Checklist (before handing code to `deploy`)

- [ ] Code lives in the user-declared entry method.
- [ ] All logs use TAG `"jugg"` and `[JUGG_AR] ...` markers.
- [ ] Every exit path (return / exception / timeout / success) hits `[JUGG_AR] DONE` via `finally`.
- [ ] Any `sleep` / polling runs on a `Thread { }` / `new Thread(...)`, never on the UI thread.
- [ ] Placeholder symbols (`isPageReady`, `performVerification`, `TargetActivity`, `context`) have been replaced or wired up.

---

## Wait Condition Examples

| Scenario | Condition |
|----------|-----------|
| Activity visible | `activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)` |
| Fragment view ready | `fragment.view != null && fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(RESUMED)` |
| Compose UI idle | `composeTestRule.waitUntil(10_000) { /* semantics match */ }` |
| RecyclerView loaded | `recyclerView.adapter?.itemCount ?: 0 > 0` |
| Network data ready | `viewModel.data.value != null` |
| Specific view exists | `findViewById<View>(R.id.target) != null` |

---

## Log Consumption

Log collection and verdict logic belong to the deploy/verify flow, not this guide. See `flow_with_auto_run.md` §Step 4 — it drives `wait-logs --marker '\[JUGG_AR\] DONE'` to block until this code signals completion.
