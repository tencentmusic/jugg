package com.sickworm.intellij.jugg.deploy.instrument

/**
 * Converts instrumentation parser events to TeamCity service messages consumed by IntelliJ SM Test Runner.
 */
class InstrumentationSmRunnerBridge(
    private val output: (String) -> Unit,
) {
    private var currentDevice: String? = null
    private var isCurrentDeviceSuiteShown: Boolean = true
    private val openedClasses = linkedSetOf<String>()
    private var started = false

    fun startDevice(deviceName: String, showDeviceSuite: Boolean = true) {
        if (!started) {
            output(serviceMessage("enteredTheMatrix"))
            started = true
        }
        currentDevice = deviceName
        isCurrentDeviceSuiteShown = showDeviceSuite
        openedClasses.clear()
        if (showDeviceSuite) {
            output(serviceMessage("testSuiteStarted", "name" to deviceName))
        }
    }

    fun onEvent(event: InstrumentationEvent) {
        when (event) {
            is InstrumentationEvent.TestStarted -> {
                ensureClassSuite(event.className)
                output(serviceMessage(
                    "testStarted",
                    "name" to event.testName,
                    "locationHint" to testLocation(event.className, event.testName),
                ))
            }
            is InstrumentationEvent.TestFinished -> renderTestFinished(event)
            is InstrumentationEvent.TestOutput -> output(serviceMessage(
                "testStdOut",
                "name" to event.testName,
                "out" to event.text.ensureTrailingNewline(),
            ))
            is InstrumentationEvent.Aborted -> renderAborted(event)
            is InstrumentationEvent.SuiteFinished -> Unit
        }
    }

    fun finishDevice() {
        openedClasses.toList().asReversed().forEach { className ->
            output(serviceMessage("testSuiteFinished", "name" to className.substringAfterLast('.')))
        }
        if (isCurrentDeviceSuiteShown) {
            currentDevice?.let { output(serviceMessage("testSuiteFinished", "name" to it)) }
        }
        currentDevice = null
        isCurrentDeviceSuiteShown = true
        openedClasses.clear()
    }

    private fun renderTestFinished(event: InstrumentationEvent.TestFinished) {
        ensureClassSuite(event.className)
        when (event.result) {
            InstrumentationEvent.TestResult.OK -> Unit
            InstrumentationEvent.TestResult.FAILURE,
            InstrumentationEvent.TestResult.ERROR -> output(serviceMessage(
                "testFailed",
                "name" to event.testName,
                "message" to failureMessage(event.stack, event.result.name),
                "details" to failureDetails(event.stack),
            ))
            InstrumentationEvent.TestResult.IGNORED,
            InstrumentationEvent.TestResult.ASSUMPTION_FAILURE -> output(serviceMessage(
                "testIgnored",
                "name" to event.testName,
                "message" to (event.stack ?: event.result.name),
            ))
        }
        output(serviceMessage("testFinished", "name" to event.testName))
    }

    private fun renderAborted(event: InstrumentationEvent.Aborted) {
        val name = "Instrumentation aborted"
        output(serviceMessage("testStarted", "name" to name))
        output(serviceMessage("testFailed", "name" to name, "message" to event.reason, "details" to event.reason))
        output(serviceMessage("testFinished", "name" to name))
    }

    private fun ensureClassSuite(className: String) {
        if (openedClasses.add(className)) {
            output(serviceMessage(
                "testSuiteStarted",
                "name" to className.substringAfterLast('.'),
                "locationHint" to suiteLocation(className),
            ))
        }
    }

    private fun failureMessage(stack: String?, fallback: String): String = stack?.lineSequence()?.firstOrNull()?.ifBlank { null } ?: fallback

    private fun failureDetails(stack: String?): String = stack
        ?.lineSequence()
        ?.drop(1)
        ?.joinToString("\n")
        .orEmpty()

    companion object {
        fun serviceMessage(command: String, vararg attributes: Pair<String, String>): String {
            if (attributes.isEmpty()) return "##teamcity[$command]"
            return attributes.joinToString(prefix = "##teamcity[$command", postfix = "]", separator = "") { (name, value) ->
                " $name='${escape(value)}'"
            }
        }

        fun suiteLocation(className: String): String = "java:suite://$className"

        fun testLocation(className: String, testName: String): String = "java:test://$className/$testName"

        fun escape(value: String): String = buildString {
            value.forEach { ch ->
                when (ch) {
                    '|' -> append("||")
                    '\'' -> append("|'")
                    '\n' -> append("|n")
                    '\r' -> append("|r")
                    '[' -> append("|[")
                    ']' -> append("|]")
                    else -> append(ch)
                }
            }
        }

        private fun String.ensureTrailingNewline(): String =
            if (endsWith('\n')) this else "$this\n"
    }
}
