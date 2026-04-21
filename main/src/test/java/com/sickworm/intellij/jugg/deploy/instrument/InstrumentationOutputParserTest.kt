package com.sickworm.intellij.jugg.deploy.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentationOutputParserTest {

    private fun feedLines(parser: InstrumentationOutputParser, vararg lines: String) {
        lines.forEach { parser.feed(it) }
    }

    /** Builds the typical ok-test output block for STATUS_CODE = 0. */
    private fun okSequence(className: String, testName: String): Array<String> = arrayOf(
        "INSTRUMENTATION_STATUS: class=$className",
        "INSTRUMENTATION_STATUS: test=$testName",
        "INSTRUMENTATION_STATUS_CODE: 1",
        "INSTRUMENTATION_STATUS: class=$className",
        "INSTRUMENTATION_STATUS: test=$testName",
        "INSTRUMENTATION_STATUS_CODE: 0",
    )

    private fun failureSequence(className: String, testName: String, stack: String): Array<String> = arrayOf(
        "INSTRUMENTATION_STATUS: class=$className",
        "INSTRUMENTATION_STATUS: test=$testName",
        "INSTRUMENTATION_STATUS_CODE: 1",
        "INSTRUMENTATION_STATUS: class=$className",
        "INSTRUMENTATION_STATUS: test=$testName",
        "INSTRUMENTATION_STATUS: stack=$stack",
        "INSTRUMENTATION_STATUS_CODE: -2",
    )

    @Test
    fun `single OK test produces TestStarted TestFinished and SuiteFinished`() {
        val parser = InstrumentationOutputParser()
        val events = mutableListOf<InstrumentationEvent>()
        parser.onEvent = { events.add(it) }

        feedLines(parser, *okSequence("com.example.FooTest", "testBar"))
        feedLines(parser,
            "INSTRUMENTATION_RESULT: stream=",
            "INSTRUMENTATION_CODE: 1"
        )

        assertTrue(events.any { it is InstrumentationEvent.TestStarted && it.testName == "testBar" })
        assertTrue(events.any { it is InstrumentationEvent.TestFinished
                && it.testName == "testBar"
                && it.result == InstrumentationEvent.TestResult.OK })
        assertTrue(events.any { it is InstrumentationEvent.SuiteFinished && it.passed == 1 && it.failed == 0 })
    }

    @Test
    fun `single FAILURE test carries stack`() {
        val parser = InstrumentationOutputParser()
        val events = mutableListOf<InstrumentationEvent>()
        parser.onEvent = { events.add(it) }

        feedLines(parser, *failureSequence("com.example.FooTest", "testBaz", "AssertionError: expected 1"))
        feedLines(parser, "INSTRUMENTATION_CODE: 0")

        val finished = events.filterIsInstance<InstrumentationEvent.TestFinished>().first()
        assertEquals(InstrumentationEvent.TestResult.FAILURE, finished.result)
        assertTrue(finished.stack?.contains("AssertionError") == true)
    }

    @Test
    fun `mix of OK FAILURE and IGNORED yields correct suite counts`() {
        val parser = InstrumentationOutputParser()
        val events = mutableListOf<InstrumentationEvent>()
        parser.onEvent = { events.add(it) }

        // OK
        feedLines(parser, *okSequence("T", "ok"))
        // FAILURE
        feedLines(parser,
            "INSTRUMENTATION_STATUS: class=T",
            "INSTRUMENTATION_STATUS: test=fail",
            "INSTRUMENTATION_STATUS_CODE: 1",
            "INSTRUMENTATION_STATUS: class=T",
            "INSTRUMENTATION_STATUS: test=fail",
            "INSTRUMENTATION_STATUS_CODE: -2",
        )
        // IGNORED
        feedLines(parser,
            "INSTRUMENTATION_STATUS: class=T",
            "INSTRUMENTATION_STATUS: test=skip",
            "INSTRUMENTATION_STATUS_CODE: 1",
            "INSTRUMENTATION_STATUS: class=T",
            "INSTRUMENTATION_STATUS: test=skip",
            "INSTRUMENTATION_STATUS_CODE: -3",
        )
        feedLines(parser, "INSTRUMENTATION_CODE: 0")

        val suite = events.filterIsInstance<InstrumentationEvent.SuiteFinished>().first()
        assertEquals(1, suite.passed)
        assertEquals(1, suite.failed)
        assertEquals(1, suite.ignored)
    }

    @Test
    fun `INSTRUMENTATION_ABORTED produces Aborted event`() {
        val parser = InstrumentationOutputParser()
        val events = mutableListOf<InstrumentationEvent>()
        parser.onEvent = { events.add(it) }

        feedLines(parser, "INSTRUMENTATION_ABORTED: Process crashed.")

        assertTrue(events.any { it is InstrumentationEvent.Aborted && it.reason.contains("crashed") })
    }

    @Test
    fun `stack continuation lines are accumulated into one stack string`() {
        val parser = InstrumentationOutputParser()
        val events = mutableListOf<InstrumentationEvent>()
        parser.onEvent = { events.add(it) }

        feedLines(parser,
            "INSTRUMENTATION_STATUS: class=T",
            "INSTRUMENTATION_STATUS: test=t",
            "INSTRUMENTATION_STATUS_CODE: 1",
            "INSTRUMENTATION_STATUS: class=T",
            "INSTRUMENTATION_STATUS: test=t",
            "INSTRUMENTATION_STATUS: stack=AssertionError: msg",
            "INSTRUMENTATION_STATUS: stack=\tat com.example.T.t(T.kt:10)",
            "INSTRUMENTATION_STATUS_CODE: -2",
        )
        feedLines(parser, "INSTRUMENTATION_CODE: 0")

        val finished = events.filterIsInstance<InstrumentationEvent.TestFinished>().first()
        assertTrue(finished.stack?.contains("AssertionError") == true)
        assertTrue(finished.stack?.contains("T.kt:10") == true)
    }

    @Test
    fun `ASSUMPTION_FAILURE is parsed correctly`() {
        val parser = InstrumentationOutputParser()
        val events = mutableListOf<InstrumentationEvent>()
        parser.onEvent = { events.add(it) }

        feedLines(parser,
            "INSTRUMENTATION_STATUS: class=T",
            "INSTRUMENTATION_STATUS: test=t",
            "INSTRUMENTATION_STATUS_CODE: 1",
            "INSTRUMENTATION_STATUS: class=T",
            "INSTRUMENTATION_STATUS: test=t",
            "INSTRUMENTATION_STATUS_CODE: -4",
        )
        feedLines(parser, "INSTRUMENTATION_CODE: 1")

        val finished = events.filterIsInstance<InstrumentationEvent.TestFinished>().first()
        assertEquals(InstrumentationEvent.TestResult.ASSUMPTION_FAILURE, finished.result)
    }
}
