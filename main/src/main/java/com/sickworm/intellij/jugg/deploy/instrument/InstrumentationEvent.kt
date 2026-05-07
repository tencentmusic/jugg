package com.sickworm.intellij.jugg.deploy.instrument

/**
 * InstrumentationEvent models a single outcome from the `am instrument -r` output stream.
 */
sealed interface InstrumentationEvent {

    data class TestStarted(val className: String, val testName: String) : InstrumentationEvent

    data class TestFinished(
        val className: String,
        val testName: String,
        val result: TestResult,
        val stack: String?,
    ) : InstrumentationEvent

    data class SuiteFinished(val passed: Int, val failed: Int, val ignored: Int) : InstrumentationEvent

    data class Aborted(val reason: String) : InstrumentationEvent

    data class TestOutput(val className: String, val testName: String, val text: String) : InstrumentationEvent

    enum class TestResult {
        OK, FAILURE, ERROR, IGNORED, ASSUMPTION_FAILURE
    }
}
