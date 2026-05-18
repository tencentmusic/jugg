package com.sickworm.intellij.jugg.deploy.direct

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DirectOverlayWriter writes Apply Changes overlay files without attaching to a running app process.
 * It validates the current overlay id on-device, extracts a pushed ZIP into `code_cache/.overlay`,
 * and writes the next overlay id last to match the install-server commit semantics.
 */
open class DirectOverlayWriter(
    private val adb: IDeviceAdb,
    private val logger: Logger,
) {

    open fun write(request: DirectOverlayWriteRequest): DirectOverlayWriteResult {
        val zipFile = File.createTempFile("jugg-direct-overlay-", ".zip")
        val remoteZipPath = "/data/local/tmp/jugg/direct-overlay-${System.currentTimeMillis()}.zip"
        var scriptStarted = false
        return try {
            require(isSafePackageName(request.packageName)) { "Unsafe package name: ${request.packageName}" }
            writeZip(zipFile, request.files)
            adb.execAdbShellCmd("mkdir -p /data/local/tmp/jugg")
            adb.execAdbShellCmd("rm -f /data/local/tmp/jugg/direct-overlay-*.zip")
            if (!adb.push(zipFile, remoteZipPath)) {
                logger.debug("Direct overlay push failed: $remoteZipPath")
                return DirectOverlayWriteResult.SKIPPED
            }
            scriptStarted = true
            val output = adb.execAdbShellScript(buildApplyScript(request, remoteZipPath))
            when {
                output.contains("$MARKER OK") -> DirectOverlayWriteResult.SUCCESS
                output.contains("$MARKER APPLYING") -> DirectOverlayWriteResult.FAILED_DIRTY
                else -> DirectOverlayWriteResult.SKIPPED
            }
        } catch (e: Exception) {
            logger.debug("Direct overlay write failed.", e)
            if (scriptStarted) {
                DirectOverlayWriteResult.FAILED_DIRTY
            } else {
                DirectOverlayWriteResult.SKIPPED
            }
        } finally {
            runCatching { adb.execAdbShellCmd("rm -f $remoteZipPath") }
            zipFile.delete()
        }
    }

    private fun writeZip(zipFile: File, files: List<DirectOverlayWriteFile>) {
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            files.forEach { file ->
                require(isSafeZipPath(file.path)) { "Unsafe overlay path: ${file.path}" }
                zip.putNextEntry(ZipEntry(file.path))
                zip.write(file.content)
                zip.closeEntry()
            }
        }
    }

    private fun buildApplyScript(request: DirectOverlayWriteRequest, remoteZipPath: String): String {
        val expectedOverlayId = shellDoubleQuote(request.expectedOverlayId)
        val overlayId = shellDoubleQuote(request.overlayId)
        return "run-as ${request.packageName} sh -c '" +
                "set -e; " +
                "overlay_dir=code_cache/.overlay; " +
                "actual=\"\"; " +
                "if [ -d \"\$overlay_dir\" ]; then " +
                "if [ -f \"\$overlay_dir/id\" ]; then actual=\$(cat \"\$overlay_dir/id\"); " +
                "else echo \"$MARKER MISSING_ID\"; exit 2; fi; " +
                "fi; " +
                "if [ \"\$actual\" != $expectedOverlayId ]; then echo \"$MARKER MISMATCH\"; exit 2; fi; " +
                "mkdir -p \"\$overlay_dir\"; " +
                "echo \"$MARKER APPLYING\"; " +
                "rm -f \"\$overlay_dir/id\"; " +
                "rm -rf code_cache/.ll; " +
                "unzip -oq $remoteZipPath -d \"\$overlay_dir\"; " +
                "printf %s $overlayId > \"\$overlay_dir/id\"; " +
                "echo \"$MARKER OK\"" +
                "'"
    }

    private fun isSafeZipPath(path: String): Boolean {
        return path.isNotEmpty() &&
                !path.startsWith("/") &&
                !path.contains("\\") &&
                !path.contains("../") &&
                path != ".." &&
                !path.startsWith("../")
    }

    private fun isSafePackageName(packageName: String): Boolean {
        return PACKAGE_NAME_PATTERN.matches(packageName)
    }

    private fun shellDoubleQuote(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
    }

    companion object {
        private const val MARKER = "__JUGG_DIRECT_OVERLAY__"
        private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

data class DirectOverlayWriteRequest(
    val packageName: String,
    val expectedOverlayId: String,
    val overlayId: String,
    val files: List<DirectOverlayWriteFile>,
)

data class DirectOverlayWriteFile(
    val path: String,
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DirectOverlayWriteFile

        if (path != other.path) return false
        if (!content.contentEquals(other.content)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}

enum class DirectOverlayWriteResult {
    SUCCESS,
    SKIPPED,
    FAILED_DIRTY,
}
