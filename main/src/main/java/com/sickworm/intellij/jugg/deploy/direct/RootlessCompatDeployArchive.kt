package com.sickworm.intellij.jugg.deploy.direct

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the rootless compat pending archive and reads back the app import result.
 *
 * When the app sandbox is unavailable Jugg cannot write `code_cache/.overlay` itself. It stages a
 * package- and request-scoped request for the app to import during its next startup instead:
 *
 * ```
 * /sdcard/Android/data/<package>/files/jugg/rootless-compat/<requestId>/
 *     payload.zip          overlay files, same entry paths as a Direct Overlay request
 *     request.properties   deterministic key=value metadata, written before the ready marker
 *     ready                written last; the app only imports a request carrying this marker
 * ```
 *
 * The importer contract lives in `com.sickworm.intellij.jugg.hotfix.RootlessCompatDeployImporter`.
 * Keep [PROTOCOL_VERSION], the property keys and [IMPORT_RESULT_MARKER] in sync with that class.
 */
object RootlessCompatDeployArchive {

    const val PROTOCOL_VERSION = 1
    private const val EXTERNAL_APP_DATA_ROOT = "/sdcard/Android/data"
    private const val ROOT_DIR_NAME = "jugg/rootless-compat"
    const val PAYLOAD_FILE_NAME = "payload.zip"
    const val REQUEST_FILE_NAME = "request.properties"
    const val READY_FILE_NAME = "ready"

    /** Log tag shared with `HotfixLoader.TAG` so the host can read the result with a tag filter. */
    const val IMPORT_RESULT_TAG = "jugg-agent"

    /** Terminal import result line: `__JUGG_ROOTLESS_IMPORT__ OK|FAILED <requestId> [<stage> <reason>]`. */
    const val IMPORT_RESULT_MARKER = "__JUGG_ROOTLESS_IMPORT__"

    const val KEY_PROTOCOL_VERSION = "protocolVersion"
    const val KEY_REQUEST_ID = "requestId"
    const val KEY_PACKAGE_NAME = "packageName"
    const val KEY_EXPECTED_OVERLAY_ID = "expectedOverlayId"
    const val KEY_NEXT_OVERLAY_ID = "nextOverlayId"
    const val KEY_PAYLOAD_SHA256 = "payloadSha256"
    const val KEY_IS_FULL_RESOURCE_PUSH = "isFullResourcePush"
    const val KEY_CREATED_AT_MILLIS = "createdAtMillis"

    /** Request id that is unique per run and safe to use as one device path segment. */
    fun newRequestId(createdAtMillis: Long): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "$createdAtMillis-$suffix"
    }

    fun requestDir(packageName: String, requestId: String): String {
        return "${packageRootDir(packageName)}/$requestId"
    }

    /** Shell-visible path that maps to the app's `Context.getExternalFilesDir(null)` directory. */
    fun packageRootDir(packageName: String): String {
        return "$EXTERNAL_APP_DATA_ROOT/$packageName/files/$ROOT_DIR_NAME"
    }

    /**
     * Zips the overlay payload with the same entry path rules as [DirectOverlayWriter], so the app
     * importer can reuse the Direct Overlay cleanup and full-resource-push semantics unchanged.
     */
    fun buildPayload(request: DirectOverlayWriteRequest): ByteArray {
        val seen = mutableSetOf<String>()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            request.files.forEach { file ->
                require(isSafeOverlayZipPath(file.path)) { "Unsafe overlay path: ${file.path}" }
                require(seen.add(file.path)) { "Duplicate overlay path: ${file.path}" }
                zip.putNextEntry(ZipEntry(file.path))
                zip.write(file.content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun buildMetadata(
        request: DirectOverlayWriteRequest,
        requestId: String,
        payloadSha256: String,
        createdAtMillis: Long,
    ): String {
        return buildString {
            appendProperty(KEY_PROTOCOL_VERSION, PROTOCOL_VERSION.toString())
            appendProperty(KEY_REQUEST_ID, requestId)
            appendProperty(KEY_PACKAGE_NAME, request.packageName)
            appendProperty(KEY_EXPECTED_OVERLAY_ID, request.expectedOverlayId)
            appendProperty(KEY_NEXT_OVERLAY_ID, request.overlayId)
            appendProperty(KEY_PAYLOAD_SHA256, payloadSha256)
            appendProperty(KEY_IS_FULL_RESOURCE_PUSH, request.isFullResourcePush.toString())
            appendProperty(KEY_CREATED_AT_MILLIS, createdAtMillis.toString())
        }
    }

    fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { String.format(Locale.US, "%02x", it) }
    }

    /**
     * Returns the terminal result of [requestId], or null when the app has not reported it yet.
     * Repeated lines are accepted when they agree, because the app process may start more than once
     * while the host waits; conflicting terminal states stay unmatched.
     */
    fun parseImportResult(logOutput: String, requestId: String): RootlessCompatImportResult? {
        // The marker is matched anywhere in the line because logcat prefixes differ per format.
        val lines = logOutput.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf(IMPORT_RESULT_MARKER)
                if (index < 0) null else line.substring(index)
            }
            .filter { it.startsWith("$IMPORT_RESULT_MARKER ") }
            .mapNotNull { parseResultLine(it, requestId) }
            .toList()
        if (lines.isEmpty() || lines.any { it.success != lines.first().success }) {
            return null
        }
        return lines.first()
    }

    private fun parseResultLine(line: String, requestId: String): RootlessCompatImportResult? {
        val parts = line.split(' ', limit = 5)
        if (parts.size < 3 || parts[2] != requestId) {
            return null
        }
        val success = when (parts[1]) {
            "OK" -> true
            "FAILED" -> false
            else -> return null
        }
        return RootlessCompatImportResult(
            success = success,
            requestId = requestId,
            stage = parts.getOrNull(3).orEmpty(),
            detail = parts.getOrNull(4).orEmpty(),
        )
    }

    private fun StringBuilder.appendProperty(key: String, value: String) {
        append(key).append('=').append(value).append('\n')
    }
}

data class RootlessCompatImportResult(
    val success: Boolean,
    val requestId: String,
    val stage: String,
    val detail: String,
)
