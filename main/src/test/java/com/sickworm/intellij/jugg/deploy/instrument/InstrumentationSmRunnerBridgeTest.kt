package com.sickworm.intellij.jugg.deploy.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentationSmRunnerBridgeTest {

    @Test
    fun `passed test emits device suite class suite test start and finish`() {
        val output = mutableListOf<String>()
        val bridge = InstrumentationSmRunnerBridge(output::add)

        bridge.startDevice("Pixel_9")
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.FooTest", "testBar", InstrumentationEvent.TestResult.OK, null))
        bridge.finishDevice()

        assertEquals("##teamcity[enteredTheMatrix]", output[0])
        assertTrue(output.any { it.contains("testSuiteStarted") && it.contains("name='Pixel_9'") })
        assertTrue(output.any { it.contains("testSuiteStarted") && it.contains("name='FooTest'") })
        assertTrue(output.any { it.contains("testSuiteStarted") && it.contains("locationHint='java:suite://com.example.FooTest'") })
        assertTrue(output.any { it.contains("testStarted") && it.contains("name='testBar'") })
        assertTrue(output.any { it.contains("locationHint='java:test://com.example.FooTest/testBar'") })
        assertTrue(output.any { it.contains("testFinished") && it.contains("name='testBar'") })
        assertTrue(output.takeLast(2).all { it.contains("testSuiteFinished") })
    }

    @Test
    fun `single device can hide device suite`() {
        val output = mutableListOf<String>()
        val bridge = InstrumentationSmRunnerBridge(output::add)

        bridge.startDevice("Pixel_9", showDeviceSuite = false)
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.FooTest", "testBar", InstrumentationEvent.TestResult.OK, null))
        bridge.finishDevice()

        assertFalse(output.any { it.contains("testSuiteStarted") && it.contains("name='Pixel_9'") })
        assertFalse(output.any { it.contains("testSuiteFinished") && it.contains("name='Pixel_9'") })
        assertTrue(output.any { it.contains("testSuiteStarted") && it.contains("name='FooTest'") })
    }

    @Test
    fun `failed test emits failure details before finish`() {
        val output = mutableListOf<String>()
        val bridge = InstrumentationSmRunnerBridge(output::add)
        val stack = "java.lang.AssertionError: expected 1\n\tat com.example.FooTest.testBar(FooTest.kt:42)"

        bridge.startDevice("Pixel_9")
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.FooTest", "testBar", InstrumentationEvent.TestResult.FAILURE, stack))
        bridge.finishDevice()

        val failedIndex = output.indexOfFirst { it.contains("testFailed") && it.contains("name='testBar'") }
        val finishedIndex = output.indexOfFirst { it.contains("testFinished") && it.contains("name='testBar'") }
        assertTrue(failedIndex >= 0)
        assertTrue(finishedIndex > failedIndex)
        assertTrue(output[failedIndex].contains("message='java.lang.AssertionError: expected 1'"))
        assertFalse(output[failedIndex].contains("details='java.lang.AssertionError: expected 1"))
        assertTrue(output[failedIndex].contains("FooTest.kt:42"))
    }

    @Test
    fun `failed test details do not repeat failure message first line`() {
        val output = mutableListOf<String>()
        val bridge = InstrumentationSmRunnerBridge(output::add)
        val stack = "org.junit.ComparisonFailure: expected a\n\tat com.example.FooTest.testBar(FooTest.kt:42)"

        bridge.startDevice("Pixel_9")
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.FooTest", "testBar", InstrumentationEvent.TestResult.FAILURE, stack))

        val failed = output.first { it.contains("testFailed") && it.contains("name='testBar'") }
        assertTrue(failed.contains("message='org.junit.ComparisonFailure: expected a'"))
        assertTrue(failed.contains("details='\tat com.example.FooTest.testBar(FooTest.kt:42)'"))
        assertEquals(1, Regex("org\\.junit\\.ComparisonFailure: expected a").findAll(failed).count())
    }

    @Test
    fun `test output emits stdout service message for active test`() {
        val output = mutableListOf<String>()
        val bridge = InstrumentationSmRunnerBridge(output::add)

        bridge.startDevice("Pixel_9")
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        bridge.onEvent(InstrumentationEvent.TestOutput(
            "com.example.FooTest",
            "testBar",
            "05-07 15:31:58.756 1234 1234 I Foo: line 1",
        ))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.FooTest", "testBar", InstrumentationEvent.TestResult.OK, null))
        bridge.finishDevice()

        val stdoutIndex = output.indexOfFirst { it.contains("testStdOut") && it.contains("name='testBar'") }
        val finishIndex = output.indexOfFirst { it.contains("testFinished") && it.contains("name='testBar'") }
        assertTrue(stdoutIndex >= 0)
        assertTrue(finishIndex > stdoutIndex)
        assertTrue(output[stdoutIndex].contains("Foo: line 1"))
    }

    @Test
    fun `test output appends newline for exported method log`() {
        val output = mutableListOf<String>()
        val bridge = InstrumentationSmRunnerBridge(output::add)

        bridge.startDevice("Pixel_9")
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        bridge.onEvent(InstrumentationEvent.TestOutput(
            "com.example.FooTest",
            "testBar",
            "05-07 15:31:58.756 1234 1234 I Foo: line 1",
        ))

        val stdout = output.first { it.contains("testStdOut") }
        assertTrue(stdout.contains("out='05-07 15:31:58.756 1234 1234 I Foo: line 1|n'"))
    }

    @Test
    fun `ignored and assumption failure emit ignored test events`() {
        val output = mutableListOf<String>()
        val bridge = InstrumentationSmRunnerBridge(output::add)

        bridge.startDevice("Pixel_9")
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "skip"))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.FooTest", "skip", InstrumentationEvent.TestResult.IGNORED, null))
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "assume"))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.FooTest", "assume", InstrumentationEvent.TestResult.ASSUMPTION_FAILURE, "assumption failed"))
        bridge.finishDevice()

        assertTrue(output.any { it.contains("testIgnored") && it.contains("name='skip'") })
        assertTrue(output.any { it.contains("testIgnored") && it.contains("name='assume'") && it.contains("assumption failed") })
        assertFalse(output.any { it.contains("testFailed") })
    }

    @Test
    fun `multiple classes close all suites on device finish`() {
        val output = mutableListOf<String>()
        val bridge = InstrumentationSmRunnerBridge(output::add)

        bridge.startDevice("Pixel_9")
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FirstTest", "a"))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.FirstTest", "a", InstrumentationEvent.TestResult.OK, null))
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.SecondTest", "b"))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.SecondTest", "b", InstrumentationEvent.TestResult.OK, null))
        bridge.finishDevice()

        assertTrue(output.any { it.contains("testSuiteFinished") && it.contains("name='FirstTest'") })
        assertTrue(output.any { it.contains("testSuiteFinished") && it.contains("name='SecondTest'") })
        assertTrue(output.last().contains("testSuiteFinished") && output.last().contains("name='Pixel_9'"))
    }

    @Test
    fun `service message attributes are escaped`() {
        val output = mutableListOf<String>()
        val bridge = InstrumentationSmRunnerBridge(output::add)

        bridge.startDevice("Pixel_9")
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "test[weird]'name"))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.FooTest", "test[weird]'name", InstrumentationEvent.TestResult.ERROR, "bad | value ' [ ] \n next"))

        val started = output.first { it.contains("testStarted") }
        val failed = output.first { it.contains("testFailed") }
        assertTrue(started.contains("name='test|[weird|]|'name'"))
        assertTrue(failed.contains("message='bad || value |' |[ |] '"))
        assertTrue(failed.contains("details=' next'"))
    }

    @Test
    fun `aborted run closes opened class and device suites`() {
        val output = mutableListOf<String>()
        val bridge = InstrumentationSmRunnerBridge(output::add)

        bridge.startDevice("Pixel_9")
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        bridge.onEvent(InstrumentationEvent.Aborted("Process crashed"))
        bridge.finishDevice()

        val abortIndex = output.indexOfFirst { it.contains("testFailed") && it.contains("Instrumentation aborted") }
        val classCloseIndex = output.indexOfFirst { it.contains("testSuiteFinished") && it.contains("FooTest") }
        val deviceCloseIndex = output.indexOfFirst { it.contains("testSuiteFinished") && it.contains("Pixel_9") }
        assertTrue(abortIndex >= 0)
        assertTrue(classCloseIndex > abortIndex)
        assertTrue(deviceCloseIndex > classCloseIndex)
    }

    @Test
    fun `multiple devices share one test session and keep device suites separated`() {
        val output = mutableListOf<String>()
        val bridge = InstrumentationSmRunnerBridge(output::add)

        bridge.startDevice("Pixel_9 API 35")
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.FooTest", "testBar", InstrumentationEvent.TestResult.OK, null))
        bridge.finishDevice()
        bridge.startDevice("Xiaomi 2509 API 36")
        bridge.onEvent(InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        bridge.onEvent(InstrumentationEvent.TestFinished("com.example.FooTest", "testBar", InstrumentationEvent.TestResult.OK, null))
        bridge.finishDevice()

        assertEquals(1, output.count { it == "##teamcity[enteredTheMatrix]" })
        val firstDeviceStart = output.indexOfFirst { it.contains("testSuiteStarted") && it.contains("Pixel_9 API 35") }
        val firstDeviceFinish = output.indexOfFirst { it.contains("testSuiteFinished") && it.contains("Pixel_9 API 35") }
        val secondDeviceStart = output.indexOfFirst { it.contains("testSuiteStarted") && it.contains("Xiaomi 2509 API 36") }
        assertTrue(firstDeviceStart >= 0)
        assertTrue(firstDeviceFinish > firstDeviceStart)
        assertTrue(secondDeviceStart > firstDeviceFinish)
    }
}
