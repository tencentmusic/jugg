package com.sickworm.intellij.jugg.mcp.viewhierarchy

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * ViewHierarchyClient talks to app-side LocalSocket server through `adb forward`.
 */
open class ViewHierarchyClient(
    private val adb: IDeviceAdb,
    private val packageName: String,
) {
    private val gson = Gson()

    /**
     * Request layout dump from app process, optionally scoped to a subtree.
     * When excludeGone is true, GONE nodes and their subtrees are omitted from output.
     * When topWindowOnly is true, only the topmost window is included in the dump.
     */
    fun dumpLayout(rootLayout: String? = null, excludeGone: Boolean = false, topWindowOnly: Boolean = true): LayoutDumpResult? {
        val params = linkedMapOf<String, Any?>()
        if (!rootLayout.isNullOrBlank()) {
            params["rootLayout"] = rootLayout
        }
        if (excludeGone) {
            params["excludeGone"] = true
        }
        params["topWindowOnly"] = topWindowOnly
        val response = sendRequest(
            ViewHierarchyRequest(
                action = "layout_dump",
                params = params,
            )
        ) ?: return null
        if (!response.isOk()) {
            return null
        }
        val data = response.data ?: return null

        val mode = data.optStringOrNull("mode")
        return if (mode == "file") {
            LayoutDumpResult(
                payloadJson = null,
                remoteFilePath = data.optStringOrNull("filePath"),
            )
        } else {
            LayoutDumpResult(
                payloadJson = gson.toJson(data),
                remoteFilePath = null,
            )
        }
    }

    /**
     * Execute atomic find-and-tap on device side.
     */
    fun findAndTap(
        text: String?,
        resourceId: String?,
        contentDesc: String?,
        className: String?,
    ): FindAndTapResult? {
        return findAndPress(
            action = "find_and_tap",
            text = text,
            resourceId = resourceId,
            contentDesc = contentDesc,
            className = className,
            duration = null,
        )
    }

    /**
     * Execute atomic find-and-long-press on device side.
     */
    fun findAndLongPress(
        text: String?,
        resourceId: String?,
        contentDesc: String?,
        className: String?,
        duration: Int,
    ): FindAndTapResult? {
        return findAndPress(
            action = "find_and_long_press",
            text = text,
            resourceId = resourceId,
            contentDesc = contentDesc,
            className = className,
            duration = duration,
        )
    }

    private fun findAndPress(
        action: String,
        text: String?,
        resourceId: String?,
        contentDesc: String?,
        className: String?,
        duration: Int?,
    ): FindAndTapResult? {
        val params = linkedMapOf<String, Any?>(
            "text" to text,
            "resourceId" to resourceId,
            "contentDesc" to contentDesc,
            "className" to className,
            "topWindowOnly" to true,
        )
        if (duration != null) {
            params["duration"] = duration
        }
        val response = sendRequest(
            ViewHierarchyRequest(
                action = action,
                params = params,
            )
        ) ?: return null

        if (response.isOk()) {
            val data = response.data ?: return FindAndTapResult.Failure("$action returned empty data")
            val x = data.optIntOrNull("x") ?: return FindAndTapResult.Failure("missing x in $action response")
            val y = data.optIntOrNull("y") ?: return FindAndTapResult.Failure("missing y in $action response")
            val matchedElement = parseMatchedElement(data.optJsonObject("matchedElement"))
                ?: return FindAndTapResult.Failure("missing matchedElement in $action response")
            val matchCount = data.optIntOrNull("matchCount") ?: 1
            return FindAndTapResult.Success(
                x = x,
                y = y,
                matchedElement = matchedElement,
                matchCount = matchCount,
            )
        }

        val message = response.message ?: "$action failed"
        val data = response.data
        val matchCount = data?.optIntOrNull("matchCount")
        val candidates = parseCandidates(data)

        return when {
            matchCount != null && matchCount > 1 -> FindAndTapResult.Multiple(
                matchCount = matchCount,
                matches = candidates,
                message = message,
            )

            matchCount != null && matchCount == 0 -> FindAndTapResult.NotFound(
                candidates = candidates,
                message = message,
            )

            else -> FindAndTapResult.Failure(message)
        }
    }

    private fun parseMatchedElement(element: JsonObject?): MatchedElementData? {
        if (element == null) {
            return null
        }
        val bounds = element.optJsonArray("bounds")
            ?.mapNotNull { it.asIntOrNull() }
            .orEmpty()
        if (bounds.size != 4) {
            return null
        }
        return MatchedElementData(
            text = element.optStringOrNull("text").orEmpty(),
            className = element.optStringOrNull("className").orEmpty(),
            resourceId = element.optStringOrNull("resourceId").orEmpty(),
            contentDesc = element.optStringOrNull("contentDesc").orEmpty(),
            bounds = bounds,
            centerX = element.optIntOrNull("centerX") ?: return null,
            centerY = element.optIntOrNull("centerY") ?: return null,
        )
    }

    private fun parseCandidates(data: JsonObject?): List<MatchCandidate> {
        if (data == null) {
            return emptyList()
        }
        val array = data.optJsonArray("elements") ?: data.optJsonArray("matches") ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element.asJsonObjectOrNull() ?: return@mapNotNull null
            MatchCandidate(
                text = item.optStringOrNull("text").orEmpty(),
                resourceId = item.optStringOrNull("resourceId").orEmpty(),
                contentDesc = item.optStringOrNull("contentDesc").orEmpty(),
                className = item.optStringOrNull("className").orEmpty(),
                bounds = item.optJsonArray("bounds")?.mapNotNull { it.asIntOrNull() }?.takeIf { it.size == 4 },
                centerX = item.optIntOrNull("centerX") ?: -1,
                centerY = item.optIntOrNull("centerY") ?: -1,
            )
        }
    }

    private fun sendRequest(request: ViewHierarchyRequest): ViewHierarchyResponse? {
        val socketNames = resolveSocketCandidates()
        if (socketNames.isEmpty()) {
            return null
        }

        for (socketName in socketNames) {
            val response = sendRequestToSocket(socketName, request)
            if (response != null) {
                return response
            }
        }
        return null
    }

    protected open fun resolveSocketCandidates(): List<String> {
        if (!PACKAGE_NAME_PATTERN.matches(packageName)) {
            return emptyList()
        }
        val processCandidates = resolveAppProcessCandidates()
        if (processCandidates.isEmpty()) {
            return listOf("jugg_vh")
        }

        val socketNames = mutableListOf<String>()
        processCandidates.forEach { socketNames.add("jugg_vh_${it.pid}") }
        socketNames.add("jugg_vh")
        return socketNames.distinct()
    }

    private fun resolveAppProcessCandidates(): List<ProcessCandidate> {
        val pidofOutput = adb.execAdbShellScript("pidof $packageName 2>/dev/null")
        val pidCandidates = parseAllPids(pidofOutput)
        val psOutput = adb.execAdbShellScript("ps | grep \"$packageName\"")
        val processNameByPid = parseProcessNameByPid(psOutput)

        val rawCandidates = mutableListOf<ProcessCandidate>()
        if (pidCandidates.isNotEmpty()) {
            pidCandidates.forEachIndexed { index, pid ->
                rawCandidates.add(
                    ProcessCandidate(
                        pid = pid,
                        processName = processNameByPid[pid],
                        order = index,
                    )
                )
            }
        } else {
            processNameByPid.entries.forEachIndexed { index, entry ->
                rawCandidates.add(
                    ProcessCandidate(
                        pid = entry.key,
                        processName = entry.value,
                        order = index,
                    )
                )
            }
        }

        val dedupByPid = LinkedHashMap<String, ProcessCandidate>()
        rawCandidates.forEach { candidate ->
            if (candidate.pid !in dedupByPid) {
                dedupByPid[candidate.pid] = candidate
            }
        }

        return dedupByPid.values
            .sortedWith(
                compareBy<ProcessCandidate> { processPriority(it.processName) }
                    .thenBy { it.order }
            )
    }

    private fun parseAllPids(output: String): List<String> {
        return output.split(WHITESPACE_REGEX)
            .map { it.trim() }
            .filter { token ->
                token.length >= 2 && token.all { it.isDigit() }
            }
            .distinct()
    }

    private fun parseProcessNameByPid(psOutput: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        psOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(packageName) }
            .forEach { line ->
                val tokens = line.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
                val pid = tokens.firstOrNull { token -> token.length >= 2 && token.all { it.isDigit() } } ?: return@forEach
                val processName = tokens.lastOrNull() ?: return@forEach
                if (!processName.contains(packageName)) {
                    return@forEach
                }
                if (processName.contains("grep")) {
                    return@forEach
                }
                if (pid !in result) {
                    result[pid] = processName
                }
            }
        return result
    }

    private fun processPriority(processName: String?): Int {
        return when {
            processName == packageName -> 0
            processName?.startsWith("$packageName:") == true -> 1
            else -> 2
        }
    }

    protected open fun sendRequestToSocket(socketName: String, request: ViewHierarchyRequest): ViewHierarchyResponse? {
        val localPort = reserveLocalPort() ?: return null
        if (!createForward(localPort, socketName)) {
            return null
        }

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", localPort), CONNECT_TIMEOUT_MS)
                socket.soTimeout = REQUEST_TIMEOUT_MS

                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))

                writer.write(gson.toJson(request))
                writer.newLine()
                writer.flush()

                val responseLine = reader.readLine() ?: return null
                parseResponse(responseLine)
            }
        } catch (_: Exception) {
            null
        } finally {
            removeForward(localPort)
        }
    }

    private fun parseResponse(responseLine: String): ViewHierarchyResponse? {
        return try {
            val root = JsonParser.parseString(responseLine).asJsonObject
            ViewHierarchyResponse(
                status = root.optStringOrNull("status"),
                message = root.optStringOrNull("message"),
                data = root.optJsonObject("data"),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun reserveLocalPort(): Int? {
        return try {
            ServerSocket(0).use { serverSocket ->
                serverSocket.localPort
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createForward(localPort: Int, socketName: String): Boolean {
        val command = listOf(
            AdbCmdHelper.findAdbExecutablePath(),
            "-s",
            adb.serial,
            "forward",
            "tcp:$localPort",
            "localabstract:$socketName",
        )
        val result = runHostCommand(command, timeoutSec = 5)
        return result.exitCode == 0
    }

    private fun removeForward(localPort: Int) {
        val command = listOf(
            AdbCmdHelper.findAdbExecutablePath(),
            "-s",
            adb.serial,
            "forward",
            "--remove",
            "tcp:$localPort",
        )
        runHostCommand(command, timeoutSec = 3)
    }

    private fun runHostCommand(command: List<String>, timeoutSec: Long): HostCommandResult {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return HostCommandResult(exitCode = 124, stdout = "", stderr = "timeout")
            }

            HostCommandResult(
                exitCode = process.exitValue(),
                stdout = String(process.inputStream.readAllBytes()).trim(),
                stderr = String(process.errorStream.readAllBytes()).trim(),
            )
        } catch (e: Exception) {
            HostCommandResult(exitCode = 1, stdout = "", stderr = e.message ?: "unknown")
        }
    }

    private data class HostCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private data class ProcessCandidate(
        val pid: String,
        val processName: String?,
        val order: Int,
    )

    private fun ViewHierarchyResponse.isOk(): Boolean {
        return status.equals("ok", ignoreCase = true)
    }

    private fun JsonObject.optStringOrNull(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) {
            return null
        }
        return value.asString
    }

    private fun JsonObject.optIntOrNull(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull) {
            return null
        }
        return value.asInt
    }

    private fun JsonObject.optJsonObject(name: String): JsonObject? {
        val value = get(name) ?: return null
        return value.asJsonObjectOrNull()
    }

    private fun JsonObject.optJsonArray(name: String): JsonArray? {
        val value = get(name) ?: return null
        if (!value.isJsonArray) {
            return null
        }
        return value.asJsonArray
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
        return if (isJsonObject) asJsonObject else null
    }

    private fun JsonElement.asIntOrNull(): Int? {
        if (isJsonNull) {
            return null
        }
        return runCatching { asInt }.getOrNull()
    }

    companion object {
        private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z0-9_.]+$")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val REQUEST_TIMEOUT_MS = 8_000
    }
}
