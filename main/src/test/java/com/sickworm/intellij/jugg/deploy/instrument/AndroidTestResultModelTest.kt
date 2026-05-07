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
