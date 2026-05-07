package com.sickworm.intellij.jugg.deploy.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTestResultModelTest {

    @Test
    fun `device details include device info and logs`() {
        val model = AndroidTestResultModel()

        model.startDevice(AndroidTestDeviceInfo("emulator-5554", "Pixel_9 API 35", 35))
        model.recordLog("Pixel_9 API 35", "INSTRUMENTATION_STATUS: class=com.example.FooTest")
        model.recordLog("Pixel_9 API 35", "INSTRUMENTATION_CODE: 1")

        val detail = model.deviceDetail("Pixel_9 API 35")

        assertTrue(detail.deviceInfo.contains("Name: Pixel_9 API 35"))
        assertTrue(detail.deviceInfo.contains("Serial: emulator-5554"))
        assertTrue(detail.deviceInfo.contains("API: 35"))
        assertEquals(listOf(
            "INSTRUMENTATION_STATUS: class=com.example.FooTest",
            "INSTRUMENTATION_CODE: 1",
        ), detail.logs)
    }

    @Test
    fun `test details include per device status and stack`() {
        val model = AndroidTestResultModel()

        model.startDevice(AndroidTestDeviceInfo("emulator-5554", "Pixel_9 API 35", 35))
        model.recordEvent("Pixel_9 API 35", InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        model.recordEvent(
            "Pixel_9 API 35",
            InstrumentationEvent.TestFinished(
                "com.example.FooTest",
                "testBar",
                InstrumentationEvent.TestResult.FAILURE,
                "java.lang.AssertionError: expected 1",
            ),
        )

        val detail = model.testDetail("com.example.FooTest", "testBar")

        assertTrue(detail.contains("com.example.FooTest#testBar"))
        assertTrue(detail.contains("Pixel_9 API 35: Fail"))
        assertTrue(detail.contains("java.lang.AssertionError: expected 1"))
    }

    @Test
    fun `test detail includes method scoped logcat`() {
        val model = AndroidTestResultModel()

        model.startDevice(AndroidTestDeviceInfo("emulator-5554", "Pixel_9 API 35", 35))
        model.recordTestLog(
            "Pixel_9 API 35",
            "com.example.FooTest",
            "testBar",
            "05-07 15:31:58.756 1234 1234 I Foo: line 1",
        )

        val detail = model.testLogDetail("com.example.FooTest", "testBar")

        assertTrue(detail.contains("Foo: line 1"))
    }

    @Test
    fun `method scoped logs stay within their own test name`() {
        val model = AndroidTestResultModel()

        model.startDevice(AndroidTestDeviceInfo("emulator-5554", "Pixel_9 API 35", 35))
        model.recordTestLog("Pixel_9 API 35", "com.example.FooTest", "testA", "05-07 15:31:58.756 1234 1234 I Foo: a")
        model.recordTestLog("Pixel_9 API 35", "com.example.FooTest", "testB", "05-07 15:31:59.756 1234 1234 I Foo: b")

        val detailA = model.testLogDetail("com.example.FooTest", "testA")
        val detailB = model.testLogDetail("com.example.FooTest", "testB")

        assertTrue(detailA.contains("Foo: a"))
        assertTrue(detailB.contains("Foo: b"))
        assertTrue(!detailA.contains("Foo: b"))
        assertTrue(!detailB.contains("Foo: a"))
    }

    @Test
    fun `method scoped logs stay within their own device`() {
        val model = AndroidTestResultModel()

        model.startDevice(AndroidTestDeviceInfo("emulator-5554", "Pixel_9 API 35", 35))
        model.startDevice(AndroidTestDeviceInfo("xiaomi-1234", "Xiaomi 2509 API 36", 36))
        model.recordTestLog("Pixel_9 API 35", "com.example.FooTest", "testA", "05-07 15:31:58.756 1234 1234 I Foo: pixel")
        model.recordTestLog("Xiaomi 2509 API 36", "com.example.FooTest", "testA", "05-07 15:31:58.756 1234 1234 I Foo: xiaomi")

        val detail = model.testLogDetail("com.example.FooTest", "testA")

        assertTrue(detail.contains("Pixel_9 API 35"))
        assertTrue(detail.contains("Xiaomi 2509 API 36"))
        assertTrue(detail.contains("Foo: pixel"))
        assertTrue(detail.contains("Foo: xiaomi"))
        assertTrue(detail.indexOf("Foo: pixel") < detail.indexOf("Foo: xiaomi"))
    }

    @Test
    fun `unrecorded method detail is deterministic and isolated`() {
        val model = AndroidTestResultModel()

        model.startDevice(AndroidTestDeviceInfo("emulator-5554", "Pixel_9 API 35", 35))
        model.recordTestLog("Pixel_9 API 35", "com.example.FooTest", "testA", "05-07 15:31:58.756 1234 1234 I Foo: a")

        val first = model.testLogDetail("com.example.FooTest", "testB")
        val second = model.testLogDetail("com.example.FooTest", "testB")

        assertEquals(first, second)
        assertTrue(first.contains("com.example.FooTest#testB"))
        assertTrue(!first.contains("Foo: a"))
        assertEquals(
            listOf(
                "com.example.FooTest#testB",
                "Pixel_9 API 35: -",
            ),
            first.lines(),
        )
    }

    @Test
    fun `matrix groups test rows by device columns`() {
        val model = AndroidTestResultModel()

        model.startDevice(AndroidTestDeviceInfo("emulator-5554", "Pixel_9 API 35", 35))
        model.recordEvent("Pixel_9 API 35", InstrumentationEvent.TestStarted("com.example.FooTest", "testPass"))
        model.recordEvent("Pixel_9 API 35", InstrumentationEvent.TestFinished("com.example.FooTest", "testPass", InstrumentationEvent.TestResult.OK, null))
        model.recordEvent("Pixel_9 API 35", InstrumentationEvent.TestStarted("com.example.FooTest", "testOnlyPixel"))
        model.recordEvent("Pixel_9 API 35", InstrumentationEvent.TestFinished("com.example.FooTest", "testOnlyPixel", InstrumentationEvent.TestResult.OK, null))

        model.startDevice(AndroidTestDeviceInfo("xiaomi-1234", "Xiaomi 2509 API 36", 36))
        model.recordEvent("Xiaomi 2509 API 36", InstrumentationEvent.TestStarted("com.example.FooTest", "testPass"))
        model.recordEvent("Xiaomi 2509 API 36", InstrumentationEvent.TestFinished("com.example.FooTest", "testPass", InstrumentationEvent.TestResult.FAILURE, "failed"))

        val matrix = model.matrix()
        val shared = matrix.rows.first { it.className == "com.example.FooTest" && it.testName == "testPass" }
        val pixelOnly = matrix.rows.first { it.className == "com.example.FooTest" && it.testName == "testOnlyPixel" }

        assertEquals(listOf("Pixel_9 API 35", "Xiaomi 2509 API 36"), matrix.devices.map { it.name })
        assertEquals(listOf(AndroidTestCellStatus.PASS, AndroidTestCellStatus.FAIL), shared.cells.map { it.status })
        assertEquals(listOf(AndroidTestCellStatus.PASS, AndroidTestCellStatus.NOT_RUN), pixelOnly.cells.map { it.status })
    }
}
