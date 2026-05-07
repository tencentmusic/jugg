package com.sickworm.intellij.jugg.deploy.instrument

/**
 * AndroidTestResultModel keeps per-device instrumentation results for detail panes and matrix views.
 */
class AndroidTestResultModel {
    private val devices = linkedMapOf<String, AndroidTestDeviceInfo>()
    private val logs = linkedMapOf<String, MutableList<String>>()
    private val testLogs = linkedMapOf<TestKey, LinkedHashMap<String, MutableList<String>>>()
    private val results = linkedMapOf<TestKey, LinkedHashMap<String, AndroidTestCell>>()

    fun startDevice(info: AndroidTestDeviceInfo) {
        devices[info.name] = info
        logs.getOrPut(info.name) { mutableListOf() }
    }

    fun recordLog(deviceName: String, line: String) {
        logs.getOrPut(deviceName) { mutableListOf() }.add(line)
    }

    fun recordTestLog(deviceName: String, className: String, testName: String, line: String) {
        val key = TestKey(className, testName)
        val deviceLogs = testLogs.getOrPut(key) { linkedMapOf() }
        deviceLogs.getOrPut(deviceName) { mutableListOf() }.add(line)
    }

    fun recordEvent(deviceName: String, event: InstrumentationEvent) {
        when (event) {
            is InstrumentationEvent.TestStarted -> putCell(
                deviceName,
                event.className,
                event.testName,
                AndroidTestCellStatus.RUNNING,
                null,
            )
            is InstrumentationEvent.TestFinished -> putCell(
                deviceName,
                event.className,
                event.testName,
                event.result.toCellStatus(),
                event.stack,
            )
            is InstrumentationEvent.Aborted -> putCell(
                deviceName,
                "Instrumentation",
                "aborted",
                AndroidTestCellStatus.FAIL,
                event.reason,
            )
            is InstrumentationEvent.SuiteFinished,
            is InstrumentationEvent.TestOutput -> Unit
        }
    }

    fun deviceDetail(deviceName: String): AndroidTestDeviceDetail {
        val info = devices[deviceName] ?: AndroidTestDeviceInfo(serial = "", name = deviceName, api = null)
        return AndroidTestDeviceDetail(
            deviceInfo = listOf(
                "Name: ${info.name}",
                "Serial: ${info.serial.ifBlank { "unknown" }}",
                "API: ${info.api?.toString() ?: "unknown"}",
            ).joinToString("\n"),
            logs = logs[deviceName].orEmpty(),
        )
    }

    fun testDetail(className: String, testName: String): String {
        val key = TestKey(className, testName)
        val deviceCells = results[key].orEmpty()
        return buildString {
            appendLine("$className#$testName")
            devices.keys.forEach { deviceName ->
                val cell = deviceCells[deviceName]
                appendLine("$deviceName: ${cell?.status?.label ?: AndroidTestCellStatus.NOT_RUN.label}")
                cell?.stack?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            }
        }.trimEnd()
    }

    fun testLogDetail(className: String, testName: String): String {
        val key = TestKey(className, testName)
        val deviceCells = results[key].orEmpty()
        val deviceLogs = testLogs[key].orEmpty()
        return buildString {
            appendLine("$className#$testName")
            (devices.keys + deviceLogs.keys).distinct().forEach { deviceName ->
                val cell = deviceCells[deviceName]
                appendLine("$deviceName: ${cell?.status?.label ?: AndroidTestCellStatus.NOT_RUN.label}")
                cell?.stack?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                deviceLogs[deviceName].orEmpty().forEach { appendLine(it) }
            }
        }.trimEnd()
    }

    fun matrix(): AndroidTestResultMatrix {
        val deviceList = devices.values.toList()
        val rows = results.map { (key, deviceCells) ->
            AndroidTestResultRow(
                className = key.className,
                testName = key.testName,
                cells = deviceList.map { device ->
                    deviceCells[device.name] ?: AndroidTestCell(device.name, AndroidTestCellStatus.NOT_RUN, null)
                },
            )
        }
        return AndroidTestResultMatrix(deviceList, rows)
    }

    fun matrixText(): String {
        val matrix = matrix()
        if (matrix.devices.isEmpty()) return "No devices"
        return buildString {
            appendLine("Android Test Results Matrix")
            appendLine((listOf("Test") + matrix.devices.map { it.name }).joinToString(" | "))
            matrix.rows.forEach { row ->
                appendLine((listOf("${row.className}#${row.testName}") + row.cells.map { it.status.label }).joinToString(" | "))
            }
        }.trimEnd()
    }

    private fun putCell(
        deviceName: String,
        className: String,
        testName: String,
        status: AndroidTestCellStatus,
        stack: String?,
    ) {
        val key = TestKey(className, testName)
        val row = results.getOrPut(key) { linkedMapOf() }
        row[deviceName] = AndroidTestCell(deviceName, status, stack)
    }

    private data class TestKey(val className: String, val testName: String)
}

data class AndroidTestDeviceInfo(
    val serial: String,
    val name: String,
    val api: Int?,
)

data class AndroidTestDeviceDetail(
    val deviceInfo: String,
    val logs: List<String>,
)

data class AndroidTestResultMatrix(
    val devices: List<AndroidTestDeviceInfo>,
    val rows: List<AndroidTestResultRow>,
)

data class AndroidTestResultRow(
    val className: String,
    val testName: String,
    val cells: List<AndroidTestCell>,
)

data class AndroidTestCell(
    val deviceName: String,
    val status: AndroidTestCellStatus,
    val stack: String?,
)

enum class AndroidTestCellStatus(val label: String) {
    RUNNING("Running"),
    PASS("Pass"),
    FAIL("Fail"),
    IGNORED("Ignored"),
    NOT_RUN("-"),
}

private fun InstrumentationEvent.TestResult.toCellStatus(): AndroidTestCellStatus {
    return when (this) {
        InstrumentationEvent.TestResult.OK -> AndroidTestCellStatus.PASS
        InstrumentationEvent.TestResult.FAILURE,
        InstrumentationEvent.TestResult.ERROR -> AndroidTestCellStatus.FAIL
        InstrumentationEvent.TestResult.IGNORED,
        InstrumentationEvent.TestResult.ASSUMPTION_FAILURE -> AndroidTestCellStatus.IGNORED
    }
}
