package com.sickworm.intellij.jugg.deploy.instrument

/**
 * InstrumentationOutputParser is a stateful line-by-line parser for the `am instrument -r` output protocol.
 *
 * Feed lines one at a time via [feed]; subscribe to structured events via [onEvent].
 *
 * Protocol reference (AOSP `am` tool):
 *   INSTRUMENTATION_STATUS: <key>=<value>
 *   INSTRUMENTATION_STATUS_CODE: <n>   (1=START, 0=OK, -1=ERROR, -2=FAILURE, -3=IGNORED, -4=ASSUMPTION_FAILURE)
 *   INSTRUMENTATION_RESULT: stream=...
 *   INSTRUMENTATION_CODE: <n>          (1=success, 0/negative=failure)
 *   INSTRUMENTATION_ABORTED: <reason>
 */
class InstrumentationOutputParser {

    /** Called synchronously for each [InstrumentationEvent] as it is emitted. */
    var onEvent: (InstrumentationEvent) -> Unit = {}

    // --- counters ---
    private var passedCount = 0
    private var failedCount = 0
    private var ignoredCount = 0

    // --- in-flight test state ---
    private var pendingClass: String? = null
    private var pendingTest: String? = null
    private val stackLines = mutableListOf<String>()
    private var inTestBlock = false

    fun feed(line: String) {
        when {
            line.startsWith("INSTRUMENTATION_STATUS: class=") -> {
                pendingClass = line.removePrefix("INSTRUMENTATION_STATUS: class=")
            }
            line.startsWith("INSTRUMENTATION_STATUS: test=") -> {
                pendingTest = line.removePrefix("INSTRUMENTATION_STATUS: test=")
            }
            line.startsWith("INSTRUMENTATION_STATUS: stack=") -> {
                stackLines.add(line.removePrefix("INSTRUMENTATION_STATUS: stack="))
            }
            line.startsWith("INSTRUMENTATION_STATUS_CODE:") -> {
                val code = line.removePrefix("INSTRUMENTATION_STATUS_CODE:").trim().toIntOrNull() ?: return
                handleStatusCode(code)
            }
            line.startsWith("INSTRUMENTATION_CODE:") -> {
                val code = line.removePrefix("INSTRUMENTATION_CODE:").trim().toIntOrNull() ?: return
                emitSuiteFinished()
            }
            line.startsWith("INSTRUMENTATION_ABORTED:") -> {
                val reason = line.removePrefix("INSTRUMENTATION_ABORTED:").trim()
                onEvent(InstrumentationEvent.Aborted(reason))
            }
        }
    }

    private fun handleStatusCode(code: Int) {
        val className = pendingClass ?: return
        val testName = pendingTest ?: return

        when (code) {
            1 -> {
                // Test started
                inTestBlock = true
                stackLines.clear()
                onEvent(InstrumentationEvent.TestStarted(className, testName))
            }
            0 -> emitTestFinished(className, testName, InstrumentationEvent.TestResult.OK)
            -1 -> emitTestFinished(className, testName, InstrumentationEvent.TestResult.ERROR)
            -2 -> emitTestFinished(className, testName, InstrumentationEvent.TestResult.FAILURE)
            -3 -> emitTestFinished(className, testName, InstrumentationEvent.TestResult.IGNORED)
            -4 -> emitTestFinished(className, testName, InstrumentationEvent.TestResult.ASSUMPTION_FAILURE)
        }
    }

    private fun emitTestFinished(className: String, testName: String, result: InstrumentationEvent.TestResult) {
        val stack = if (stackLines.isNotEmpty()) stackLines.joinToString("\n") else null
        onEvent(InstrumentationEvent.TestFinished(className, testName, result, stack))

        when (result) {
            InstrumentationEvent.TestResult.OK -> passedCount++
            InstrumentationEvent.TestResult.IGNORED, InstrumentationEvent.TestResult.ASSUMPTION_FAILURE -> ignoredCount++
            else -> failedCount++
        }

        stackLines.clear()
        inTestBlock = false
        pendingClass = null
        pendingTest = null
    }

    private fun emitSuiteFinished() {
        onEvent(InstrumentationEvent.SuiteFinished(passedCount, failedCount, ignoredCount))
    }
}
