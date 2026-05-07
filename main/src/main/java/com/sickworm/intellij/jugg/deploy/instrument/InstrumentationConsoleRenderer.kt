package com.sickworm.intellij.jugg.deploy.instrument

// ANSI escape codes
private const val GREEN = "\u001B[32m"
private const val RED = "\u001B[31m"
private const val GRAY = "\u001B[90m"
private const val RESET = "\u001B[0m"

/**
 * InstrumentationConsoleRenderer converts [InstrumentationEvent] objects to human-readable ANSI-coloured text lines.
 *
 * Each call to [render] synchronously calls [lineOutput] for every produced line.
 */
class InstrumentationConsoleRenderer(private val lineOutput: (String) -> Unit) {

    fun render(event: InstrumentationEvent) {
        when (event) {
            is InstrumentationEvent.TestStarted -> Unit // no output on start; result line covers it

            is InstrumentationEvent.TestFinished -> renderTestFinished(event)

            is InstrumentationEvent.SuiteFinished -> {
                lineOutput("Tests summary: ${event.passed} passed, ${event.failed} failed, ${event.ignored} ignored")
            }

            is InstrumentationEvent.Aborted -> {
                lineOutput("${RED}Test process aborted: ${event.reason}${RESET}")
            }

            is InstrumentationEvent.TestOutput -> Unit
        }
    }

    private fun renderTestFinished(event: InstrumentationEvent.TestFinished) {
        val (icon, color, label) = when (event.result) {
            InstrumentationEvent.TestResult.OK -> Triple("▶", GREEN, "OK")
            InstrumentationEvent.TestResult.FAILURE -> Triple("✗", RED, "FAILURE")
            InstrumentationEvent.TestResult.ERROR -> Triple("✗", RED, "ERROR")
            InstrumentationEvent.TestResult.IGNORED -> Triple("⊘", GRAY, "IGNORED")
            InstrumentationEvent.TestResult.ASSUMPTION_FAILURE -> Triple("⊘", GRAY, "ASSUMPTION_FAILURE")
        }
        lineOutput("  $color$icon ${event.className}.${event.testName} ... $label$RESET")

        event.stack?.lines()?.forEach { stackLine ->
            lineOutput("    $RED$stackLine$RESET")
        }
    }
}
