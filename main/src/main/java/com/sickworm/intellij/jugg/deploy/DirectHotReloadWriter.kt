package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Stages a Direct Deploy Hot Reload request and waits for the Jugg JVMTI agent result.
 */
class DirectHotReloadWriter(
    private val adb: IDeviceAdb,
    loggerArg: Logger,
    private val sandbox: AppSandboxExecutor,
) {

    private val logger = loggerArg.getInstance("DirectHotReloadWriter")

    fun apply(
        packageName: String,
        pid: Int,
        newClasses: List<DirectHotReloadClass>,
        modifiedClasses: List<DirectHotReloadClass>,
        refreshResources: Boolean,
        restartActivity: Boolean,
    ): DirectHotReloadResult {
        val classCount = newClasses.size + modifiedClasses.size
        if (classCount > MAX_CLASS_COUNT) {
            return DirectHotReloadResult(false, "invalid class count: $classCount")
        }
        if (sandbox.mode == AppSandboxExecutor.Mode.RUN_AS || sandbox.mode == AppSandboxExecutor.Mode.UNAVAILABLE) {
            return DirectHotReloadResult(false, "direct app sandbox unavailable")
        }
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val requestDir = "$REQUEST_ROOT/$requestId"
        val remoteZip = "/data/local/tmp/jugg/hot-reload/$requestId.zip"
        val archive = File.createTempFile("jugg-hot-reload-", ".zip")
        var requestCompleted = false
        try {
            writeArchive(archive, newClasses, modifiedClasses, refreshResources, restartActivity)
            stageRequest(archive, remoteZip, requestDir)?.let {
                return it
            }
            val absoluteRequestDir = sandbox.absolutePath(requestDir)
                ?: return DirectHotReloadResult(false, "request path unavailable")
            val manager = JuggJvmtiAgentManager(adb, logger)
            val agentPath = manager.prepareHotReloadAgent(packageName, requestDir, sandbox)
                ?: return DirectHotReloadResult(false, "dynamic agent preparation failed")
            if (!manager.attachHotReloadAgent(pid, agentPath, absoluteRequestDir)) {
                return DirectHotReloadResult(false, "attach-agent failed")
            }
            val result = waitForResult(requestDir)
            if (result != null) {
                requestCompleted = true
                return result
            }
            return DirectHotReloadResult(false, "agent result timed out")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } finally {
            if (requestCompleted) {
                runCatching { sandbox.exec("rm -rf $requestDir", repairCodeCache = true) }
            }
            runCatching { adb.execAdbShellCmd("rm -f $remoteZip") }
            archive.delete()
        }
    }

    private fun stageRequest(archive: File, remoteZip: String, requestDir: String): DirectHotReloadResult? {
        adb.execAdbShellCmd("mkdir -p /data/local/tmp/jugg/hot-reload")
        if (!adb.push(archive, remoteZip)) {
            return DirectHotReloadResult(false, "request push failed")
        }
        val output = sandbox.exec(
            "rm -rf $REQUEST_ROOT && mkdir -p $requestDir && " +
                "unzip -oq $remoteZip -d $requestDir && echo $MARKER STAGED",
            repairCodeCache = true,
        )
        return if (output.contains("$MARKER STAGED")) {
            null
        } else {
            DirectHotReloadResult(false, "request staging failed: ${output.trim()}")
        }
    }

    private fun waitForResult(requestDir: String): DirectHotReloadResult? {
        repeat(RESULT_POLL_COUNT) {
            val output = sandbox.exec(
                "if [ -f $requestDir/result.txt ]; then cat $requestDir/result.txt; fi",
            ).trim()
            parseResult(output)?.let {
                return it
            }
            Thread.sleep(RESULT_POLL_INTERVAL_MS)
        }
        return null
    }

    private fun writeArchive(
        file: File,
        newClasses: List<DirectHotReloadClass>,
        modifiedClasses: List<DirectHotReloadClass>,
        refreshResources: Boolean,
        restartActivity: Boolean,
    ) {
        var totalSize = 0L
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            val request = buildString {
                append(PROTOCOL).append('\n')
                append(REFRESH_RESOURCES).append('\t').append(if (refreshResources) 1 else 0).append('\n')
                append(RESTART_ACTIVITY).append('\t').append(if (restartActivity) 1 else 0).append('\n')
                newClasses.forEachIndexed { index, clazz ->
                    require(clazz.name.isNotBlank() && clazz.name.none { it == '\t' || it == '\n' || it == '\r' }) {
                        "Invalid class name: ${clazz.name}"
                    }
                    totalSize = validateDex(clazz, totalSize)
                    append(NEW_CLASS).append('\t').append(clazz.name).append('\t')
                        .append("dex/new/$index.dex").append('\n')
                }
                modifiedClasses.forEachIndexed { index, clazz ->
                    require(DESCRIPTOR_PATTERN.matches(clazz.name)) {
                        "Invalid class descriptor: ${clazz.name}"
                    }
                    totalSize = validateDex(clazz, totalSize)
                    append(MODIFIED_CLASS).append('\t').append(clazz.name).append('\t')
                        .append("dex/modified/$index.dex").append('\n')
                }
            }
            zip.putNextEntry(ZipEntry("request.txt"))
            zip.write(request.toByteArray())
            zip.closeEntry()
            newClasses.forEachIndexed { index, clazz ->
                zip.putNextEntry(ZipEntry("dex/new/$index.dex"))
                zip.write(clazz.dex)
                zip.closeEntry()
            }
            modifiedClasses.forEachIndexed { index, clazz ->
                zip.putNextEntry(ZipEntry("dex/modified/$index.dex"))
                zip.write(clazz.dex)
                zip.closeEntry()
            }
        }
    }

    private fun validateDex(clazz: DirectHotReloadClass, currentTotalSize: Long): Long {
        require(clazz.dex.isNotEmpty() && clazz.dex.size <= MAX_DEX_SIZE) {
            "Invalid dex size for ${clazz.name}: ${clazz.dex.size}"
        }
        return (currentTotalSize + clazz.dex.size).also {
            require(it <= MAX_TOTAL_DEX_SIZE) { "Hot Reload request is too large" }
        }
    }

    private fun parseResult(output: String): DirectHotReloadResult? {
        val line = output.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        return when {
            line.startsWith("OK\t") -> DirectHotReloadResult(true, line)
            line.startsWith("ERROR\t") || line.startsWith("CLASS_NOT_FOUND\t") ||
                line.startsWith("MISSING\t") ||
                line.startsWith("UNMODIFIABLE\t") -> DirectHotReloadResult(false, line)
            else -> null
        }
    }

    companion object {
        private const val REQUEST_ROOT = "code_cache/jugg_hot_reload"
        private const val PROTOCOL = "JUGG_HOT_RELOAD_V4"
        private const val REFRESH_RESOURCES = "REFRESH_RESOURCES"
        private const val RESTART_ACTIVITY = "RESTART_ACTIVITY"
        private const val NEW_CLASS = "NEW"
        private const val MODIFIED_CLASS = "MODIFIED"
        private const val MARKER = "__JUGG_HOT_RELOAD__"
        private const val RESULT_POLL_COUNT = 50
        private const val RESULT_POLL_INTERVAL_MS = 100L
        private const val MAX_CLASS_COUNT = 512
        private const val MAX_DEX_SIZE = 16 * 1024 * 1024
        private const val MAX_TOTAL_DEX_SIZE = 64 * 1024 * 1024
        private val DESCRIPTOR_PATTERN = Regex("L[A-Za-z0-9_$/-]+;")
    }
}

data class DirectHotReloadClass(
    val name: String,
    val dex: ByteArray,
)

data class DirectHotReloadResult(
    val success: Boolean,
    val detail: String,
)
