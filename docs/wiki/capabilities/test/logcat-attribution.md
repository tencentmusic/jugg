---
title: Logcat attribution
description: Explains how Jugg Android Test attributes logcat output to a specific device and test method.
status: active
tags:
  - capability
  - test
  - logcat
---

# Logcat attribution

Jugg captures logcat during Android Test runs and displays short logs related to a test method on the corresponding Test Results node. Complete device logs remain available in device details for troubleshooting instrumentation or device-level problems.

## Log display and attribution scope

| User scenario | Current support | Result |
|---|---|---|
| View logcat related to a failed method | Supported | Method details display method-level logs |
| View complete test logs for a device | Supported | Device details display raw device-level logs |
| Separate logs in multi-device tests | Supported | Each device maintains its own logcat start point and method window |
| Preserve attributed logs when instrumentation fails | Supported | Logs in the active method window remain visible |

## How logcat is attributed

```text
Read device time before instrumentation starts on each device
  -> Use logcat -T to capture logs after that point
  -> Instrumentation events produce the test lifecycle
  -> Prefer AndroidX TestRunner markers to determine the method window
  -> Fall back to the instrumentation lifecycle when complete markers are unavailable
  -> Output method logs to the corresponding Test Results node
```

Jugg uses device-side time as the logcat start point so that host-device clock skew does not filter out logs from the current run. `logcat -T` also reduces contamination of the first test method by historical logs in the old buffer.

## Attribution boundaries

| Log type | Display location |
|---|---|
| Logs inside the method window from the current test process | Corresponding method node |
| Logs outside the method window | Device details |
| Device noise outside the current test process | Device details |
| Failure stack trace | Appended after method logs |
| Logs beyond the method-level limit | Displayed after truncation |

> [!IMPORTANT]
> Jugg does not infer method ownership from application tags, message text, or nearby timestamps. Method attribution depends only on AndroidX TestRunner markers or the instrumentation lifecycle.

## Related pages

- [Application Android Test](./application-android-test.md)
- [Library Android Test](./library-android-test.md)
- [Test Results UI](./test-results-ui.md)
