package com.sickworm.intellij.jugg.deploy.direct

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DirectOverlayWriter writes Apply Changes overlay files without attaching to a running app process.
 * It validates the current overlay id on-device, extracts a pushed ZIP into `code_cache/.overlay`,
 * marks overlay `.dex` files read-only for Android 14+ W^X, and writes the next overlay id last
 * to match the install-server commit semantics.
 * Concurrent writes are serialized globally because cleanup removes shared temp ZIP files on device.
 */
open class DirectOverlayWriter(
    private val adb: IDeviceAdb,
    private val logger: Logger,
) {

    open fun write(request: DirectOverlayWriteRequest): DirectOverlayWriteResult = synchronized(writeLock) {
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
            val output = adb.execAdbShellScriptNoFallback(buildApplyScript(request, remoteZipPath))
            when {
                output.contains("$MARKER OK") -> DirectOverlayWriteResult.SUCCESS
                output.contains("$MARKER APPLYING") -> DirectOverlayWriteResult.FAILED_DIRTY
                output.contains("$MARKER MISSING_ID") -> DirectOverlayWriteResult.FAILED_DIRTY
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
                "had_overlay_dir=0; " +
                "if [ -d \"\$overlay_dir\" ]; then " +
                "had_overlay_dir=1; " +
                "if [ -f \"\$overlay_dir/id\" ]; then actual=\$(cat \"\$overlay_dir/id\"); " +
                "else echo \"$MARKER MISSING_ID\"; exit 2; fi; " +
                "fi; " +
                "if [ \"\$actual\" != $expectedOverlayId ]; then echo \"$MARKER MISMATCH\"; exit 2; fi; " +
                buildPreApplyGuardScript(request) +
                "mkdir -p \"\$overlay_dir\"; " +
                "echo \"$MARKER APPLYING\"; " +
                buildHeartbeatScript() +
                buildCleanupScript(request) +
                "unzip -oq $remoteZipPath -d \"\$overlay_dir\"; " +
                "find \"\$overlay_dir\" -type f -name '*.dex' -exec chmod 0444 {} +; " +
                "printf %s $overlayId > \"\$overlay_dir/id\"; " +
                "echo \"$MARKER OK\"" +
                "'"
    }

    private fun buildHeartbeatScript(): String {
        return "heartbeat() { while true; do echo \"$MARKER HEARTBEAT\"; sleep 1; done; }; " +
                "heartbeat & heartbeat_pid=\$!; " +
                "trap \"kill \$heartbeat_pid 2>/dev/null || true\" EXIT; "
    }

    private fun buildPreApplyGuardScript(request: DirectOverlayWriteRequest): String {
        if (request.skipPayloadCleanup) {
            return "if [ \"\$had_overlay_dir\" = \"1\" ]; then echo \"$MARKER MISMATCH\"; exit 2; fi; "
        }
        return ""
    }

    private fun buildCleanupScript(request: DirectOverlayWriteRequest): String {
        if (request.skipPayloadCleanup) {
            return "rm -rf code_cache/.ll; "
        }
        return "rm -f \"\$overlay_dir/id\"; " +
                "rm -rf code_cache/.ll; " +
                buildRemovePayloadTargetsScript(request)
    }

    private fun buildRemovePayloadTargetsScript(files: List<DirectOverlayWriteFile>): String {
        return files.joinToString(separator = "") { file ->
            "rm -f \"\$overlay_dir\"/${shellSingleQuote(file.path)}; "
        }
    }

    private fun buildRemovePayloadTargetsScript(request: DirectOverlayWriteRequest): String {
        if (!request.isFullResourcePush) {
            return buildRemovePayloadTargetsScript(request.files)
        }
        val removeBaseApk = request.files.any { it.path.startsWith(BASE_APK_PREFIX) }
        val baseApkScript = if (removeBaseApk) {
            "rm -rf \"\$overlay_dir\"/${shellSingleQuote(BASE_APK_DIR)}; "
        } else {
            ""
        }
        val otherFilesScript = request.files
            .filterNot { it.path.startsWith(BASE_APK_PREFIX) }
            .joinToString(separator = "") { file ->
                "rm -f \"\$overlay_dir\"/${shellSingleQuote(file.path)}; "
            }
        return baseApkScript + otherFilesScript
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

    private fun shellSingleQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    companion object {
        private val writeLock = Any()
        private const val MARKER = "__JUGG_DIRECT_OVERLAY__"
        private const val BASE_APK_DIR = "base.apk"
        private const val BASE_APK_PREFIX = "$BASE_APK_DIR/"
        private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

data class DirectOverlayWriteRequest(
    val packageName: String,
    val expectedOverlayId: String,
    val overlayId: String,
    val files: List<DirectOverlayWriteFile>,
    val isFullResourcePush: Boolean = false,
    val skipPayloadCleanup: Boolean = false,
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
