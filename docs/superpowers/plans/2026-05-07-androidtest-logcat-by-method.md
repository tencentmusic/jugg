# AndroidTest Logcat by Method Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put device logcat captured during AndroidTest execution onto the matching test method entry in Jugg Test Results.

**Architecture:** Keep `InstrumentationOutputParser` and SM Test Runner wiring as-is. Add per-device logcat collection inside `TestLauncher`, keyed by the current `InstrumentationEvent.TestStarted` / `TestFinished` lifecycle, then persist method-scoped logs in `AndroidTestResultModel` for later display. Preserve device-level logs separately so logs outside any method still remain visible.

**Tech Stack:** Kotlin, JUnit4, Mockito, IntelliJ Platform test harness, existing Jugg AndroidTest instrumentation pipeline.

---

### Task 1: Extend the result model to store method-scoped logcat

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/AndroidTestResultModel.kt:1-220`
- Test: `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/AndroidTestResultModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `test detail includes method scoped logcat`() {
    val model = AndroidTestResultModel()
    model.startDevice(AndroidTestDeviceInfo(serial = "emulator-5554", name = "Pixel_9 API 35", api = 35))
    model.recordTestLog("Pixel_9 API 35", "com.example.FooTest", "testBar", "05-07 15:31:58.756 1234 1234 I Foo: line 1")

    val detail = model.testLogDetail("com.example.FooTest", "testBar")

    assertTrue(detail.contains("Foo: line 1"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.AndroidTestResultModelTest"`
Expected: FAIL because `recordTestLog` / `testLogDetail` do not exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
private val testLogs = linkedMapOf<TestKey, LinkedHashMap<String, MutableList<String>>>()

fun recordTestLog(deviceName: String, className: String, testName: String, line: String) {
    val key = TestKey(className, testName)
    val deviceLogs = testLogs.getOrPut(key) { linkedMapOf() }
    deviceLogs.getOrPut(deviceName) { mutableListOf() }.add(line)
}

fun testLogDetail(className: String, testName: String): String {
    val key = TestKey(className, testName)
    val deviceLogs = testLogs[key].orEmpty()
    return buildString {
        appendLine("$className#$testName")
        devices.keys.forEach { deviceName ->
            appendLine("$deviceName:")
            deviceLogs[deviceName].orEmpty().forEach { appendLine(it) }
        }
    }.trimEnd()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.AndroidTestResultModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/AndroidTestResultModel.kt main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/AndroidTestResultModelTest.kt
git commit -m "[feature] store android test logcat by method"
```

### Task 2: Add a test-launcher hook for per-device logcat collection

**Files:**
- Modify: `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/TestLauncher.kt:1-220`
- Test: `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/TestLauncherResultTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `logcat lines between TestStarted and TestFinished are recorded for that method`() {
    val model = AndroidTestResultModel()
    val launcher = TestLauncher(
        devices = listOf(device()),
        spec = spec,
        testApk = testApk,
        consoleOutput = {},
        cancelSignal = { false },
        logger = Logger.getInstance(TestLauncherResultTest::class.java),
        resultModel = model,
        logcatSourceFactory = { _ -> FakeLogcatSource(listOf(
            "05-07 15:31:58.756 1234 1234 I Foo: before",
            "05-07 15:31:58.757 1234 1234 I Foo: during",
            "05-07 15:31:58.758 1234 1234 I Foo: after",
        )) },
        runInstrumentation = { _, _, _, lineConsumer, _ ->
            lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
            lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
            lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
            lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
            lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
            lineConsumer("INSTRUMENTATION_STATUS_CODE: 0")
            lineConsumer("INSTRUMENTATION_CODE: 1")
            0
        },
    )

    assertTrue(launcher.run())
    val detail = model.testLogDetail("com.example.FooTest", "testBar")
    assertTrue(detail.contains("during"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.TestLauncherResultTest"`
Expected: FAIL because `logcatSourceFactory` and method-scoped routing do not exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
private val logcatSourceFactory: (IDevice) -> AdbLogcatSource? = { null }

val parser = InstrumentationOutputParser()
var currentClass: String? = null
var currentTest: String? = null

parser.onEvent = { event ->
    when (event) {
        is InstrumentationEvent.TestStarted -> {
            currentClass = event.className
            currentTest = event.testName
        }
        is InstrumentationEvent.TestFinished -> {
            currentClass = null
            currentTest = null
        }
        else -> Unit
    }
}

val source = logcatSourceFactory(device)
while (!isCanceled()) {
    val line = source?.nextLine(50)
    if (line != null && currentClass != null && currentTest != null) {
        resultModel.recordTestLog(deviceName, currentClass!!, currentTest!!, line)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.TestLauncherResultTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/TestLauncher.kt idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/TestLauncherResultTest.kt
git commit -m "[feature] route android test logcat to active method"
```

### Task 3: Preserve logs on abort and keep device-level output separate

**Files:**
- Modify: `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/TestLauncher.kt:1-220`
- Test: `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/TestLauncherResultTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `aborted run flushes current method logcat`() {
    val model = AndroidTestResultModel()
    val launcher = TestLauncher(
        devices = listOf(device()),
        spec = spec,
        testApk = testApk,
        consoleOutput = {},
        cancelSignal = { false },
        logger = Logger.getInstance(TestLauncherResultTest::class.java),
        resultModel = model,
        logcatSourceFactory = { _ -> FakeLogcatSource(listOf("05-07 15:31:58.756 1234 1234 I Foo: tail")) },
        runInstrumentation = { _, _, _, lineConsumer, _ ->
            lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
            lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
            lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
            lineConsumer("INSTRUMENTATION_ABORTED: Process crashed.")
            0
        },
    )

    assertFalse(launcher.run())
    assertTrue(model.testLogDetail("com.example.FooTest", "testBar").contains("tail"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.TestLauncherResultTest"`
Expected: FAIL because abort flush is not implemented.

- [ ] **Step 3: Write minimal implementation**

```kotlin
parser.onEvent = { event ->
    when (event) {
        is InstrumentationEvent.TestFinished,
        is InstrumentationEvent.Aborted -> {
            flushCurrentMethodLog(currentClass, currentTest)
            currentClass = null
            currentTest = null
        }
        else -> Unit
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.TestLauncherResultTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/TestLauncher.kt idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/TestLauncherResultTest.kt
git commit -m "[bugfix] flush android test logcat on abort"
```

### Task 4: Update docs and validate scoped tests

**Files:**
- Modify: `docs/ai_knowledge/06_android_test.md`
- Modify: `docs/ai_knowledge/98_code_map.md`
- Modify: `docs/ai_knowledge/99_index.md`
- Test: rerun the same scoped tests from Tasks 1-3

- [ ] **Step 1: Write the doc updates**

```markdown
- Add method-level logcat archiving to AndroidTest support scope.
- Describe `TestLauncher` as the routing point for method-scoped device logcat.
- Update code map entries for `AndroidTestResultModel` and `TestLauncher`.
```

- [ ] **Step 2: Run scoped verification**

Run:
```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.AndroidTestResultModelTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.TestLauncherResultTest"
./gradlew :idea:compileKotlin
```
Expected: all pass.

- [ ] **Step 3: Commit**

```bash
git add docs/ai_knowledge/06_android_test.md docs/ai_knowledge/98_code_map.md docs/ai_knowledge/99_index.md
git commit -m "[docs] document android test method logcat routing"
```
