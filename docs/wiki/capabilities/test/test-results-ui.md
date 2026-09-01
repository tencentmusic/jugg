---
title: Test Results UI
description: Explains the test tree, failure display, source navigation, and rerun failed capabilities for Jugg Android Test in the Run window.
status: active
tags:
  - capability
  - test
  - test-results
---

# Test Results UI

Jugg displays Android Test results in the IntelliJ / Android Studio Test Results UI. Jugg still performs compilation, deployment, and instrumentation, while SM Test Runner displays the test tree, failure details, source navigation, and rerun failed.

## Result display scope

| User scenario | Current support | Result |
|---|---|---|
| View the test class / method tree | Supported | Test Results tab in the Run window |
| Run on one device | Supported | Hides the device level and displays class / method directly |
| Run on multiple devices | Supported | Groups class / method results by device |
| Navigate to test source | Supported | Class / method nodes use Java test locations |
| Rerun failed tests | Supported | Converts failed leaf tests into test filters |
| View a multi-device result matrix | Supported | A text matrix displays each test's status on every device |

## How Test Results is integrated

```text
Android Test run creates an SM Runner console
  -> InstrumentationOutputParser parses am instrument output
  -> InstrumentationSmRunnerBridge emits test started / finished / failed events
  -> SM Test Runner displays the test tree
  -> rerun failed converts failed nodes back into AndroidTestRunSpec
```

A regular app run continues using the regular text console. Only an Android Test run creates the Test Results UI. Jugg logs from compilation and deployment remain in Run output, while test node details contain only instrumentation events and method-level logcat.

## Device display rules

| Run devices | Display |
|---|---|
| One device | Hides the device suite to remove one tree level |
| Multiple devices | Displays a device suite so results from different devices do not mix |
| Device details | Displays serial, name, API, and raw device-level instrumentation logs |

For multiple devices, Jugg runs instrumentation in device order and writes every device's results into the same Test Results session. The Android Test run fails if any device reports a nonzero instrumentation exit, aborted, failure, error, or assumption failure.

## Rerun failed

Rerun failed collects only failed leaf test nodes and creates new test filters. It preserves the original runner override and instrumentation arguments but does not change the class / method scope on the Run Configuration's General page.

## Related pages

- [Android Test guide](../../guide/android-test.md)
- [Android Test flow](../../concepts/android-test-flow.md)
- [Application Android Test](./application-android-test.md)
- [Library Android Test](./library-android-test.md)
- [Logcat attribution](./logcat-attribution.md)
