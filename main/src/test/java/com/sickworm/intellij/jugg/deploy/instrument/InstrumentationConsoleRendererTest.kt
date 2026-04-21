package com.sickworm.intellij.jugg.deploy.instrument

import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentationConsoleRendererTest {

    private fun collectLines(block: InstrumentationConsoleRenderer.() -> Unit): String {
        val out = StringBuilder()
        val renderer = InstrumentationConsoleRenderer { line -> out.appendLine(line) }
        renderer.block()
        return out.toString()
    }

    @Test
    fun `OK event output contains OK keyword`() {
        val output = collectLines {
            render(InstrumentationEvent.TestFinished("com.example.FooTest", "testBar", InstrumentationEvent.TestResult.OK, null))
        }
        assertTrue(output.contains("OK"))
        assertTrue(output.contains("testBar"))
    }

    @Test
    fun `FAILURE event contains FAILURE keyword and stack`() {
        val output = collectLines {
            render(InstrumentationEvent.TestFinished(
                "com.example.FooTest", "testBaz",
                InstrumentationEvent.TestResult.FAILURE,
                "AssertionError: expected 1 but was 2\n\tat FooTest.kt:42"
            ))
        }
        assertTrue(output.contains("FAILURE"))
        assertTrue(output.contains("AssertionError"))
        assertTrue(output.contains("FooTest.kt:42"))
    }

    @Test
    fun `IGNORED event contains IGNORED keyword`() {
        val output = collectLines {
            render(InstrumentationEvent.TestFinished("T", "skip", InstrumentationEvent.TestResult.IGNORED, null))
        }
        assertTrue(output.contains("IGNORED"))
    }

    @Test
    fun `SuiteFinished contains pass fail ignored counts`() {
        val output = collectLines {
            render(InstrumentationEvent.SuiteFinished(passed = 3, failed = 1, ignored = 2))
        }
        assertTrue("Suite line should contain pass count", output.contains("3"))
        assertTrue("Suite line should contain fail count", output.contains("1"))
        assertTrue("Suite line should contain ignored count", output.contains("2"))
    }

    @Test
    fun `Aborted event output contains reason`() {
        val output = collectLines {
            render(InstrumentationEvent.Aborted("Process crashed"))
        }
        assertTrue(output.contains("Process crashed"))
    }
}
