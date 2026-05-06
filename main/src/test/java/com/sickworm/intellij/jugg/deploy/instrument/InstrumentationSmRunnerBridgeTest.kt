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
        assertTrue(output.any { it.contains("testSuiteStarted") && it.contains("name='com.example.FooTest'") })
        assertTrue(output.any { it.contains("testStarted") && it.contains("name='testBar'") })
        assertTrue(output.any { it.contains("locationHint='java:test://com.example.FooTest/testBar'") })
        assertTrue(output.any { it.contains("testFinished") && it.contains("name='testBar'") })
        assertTrue(output.takeLast(2).all { it.contains("testSuiteFinished") })
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
        assertTrue(output[failedIndex].contains("details='java.lang.AssertionError: expected 1"))
        assertTrue(output[failedIndex].contains("FooTest.kt:42"))
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

        assertTrue(output.any { it.contains("testSuiteFinished") && it.contains("name='com.example.FirstTest'") })
        assertTrue(output.any { it.contains("testSuiteFinished") && it.contains("name='com.example.SecondTest'") })
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
        assertTrue(failed.contains("bad || value |' |[ |] |n next"))
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
        val classCloseIndex = output.indexOfFirst { it.contains("testSuiteFinished") && it.contains("com.example.FooTest") }
        val deviceCloseIndex = output.indexOfFirst { it.contains("testSuiteFinished") && it.contains("Pixel_9") }
        assertTrue(abortIndex >= 0)
        assertTrue(classCloseIndex > abortIndex)
        assertTrue(deviceCloseIndex > classCloseIndex)
    }
}
