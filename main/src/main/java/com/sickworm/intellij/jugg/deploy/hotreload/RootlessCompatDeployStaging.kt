package com.sickworm.intellij.jugg.deploy.hotreload

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayWriteRequest
import com.sickworm.intellij.jugg.deploy.direct.RootlessCompatDeployArchive
import com.sickworm.intellij.jugg.deploy.direct.RootlessCompatImportResult
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayId
import java.io.File

/**
 * A compat request staged under the app external files directory for the app to import on its next
 * start. Deploy state must not advance until the app confirms the import result.
 */
data class RootlessCompatPending(
    val packageName: String,
    val requestId: String,
    val overlayId: JuggOverlayId,
    val apkPaths: List<String>,
)

data class RootlessCompatStagedRequest(
    val requestId: String,
    val requestDir: String,
)

/**
 * Stages a compat overlay payload for the app-side importer. Used when the app sandbox is
 * unavailable, so nothing but plain `adb push` into a shell-writable directory is required and no
 * JVMTI agent is prepared, copied or attached.
 */
class RootlessCompatDeployStaging(
    private val adb: IDeviceAdb,
    private val logger: Logger,
) {

    fun stage(packageName: String, request: DirectOverlayWriteRequest): RootlessCompatStagedRequest {
        require(isSafePackageName(packageName)) { "Unsafe package name: $packageName" }
        val createdAtMillis = System.currentTimeMillis()
        val requestId = RootlessCompatDeployArchive.newRequestId(createdAtMillis)
        val requestDir = RootlessCompatDeployArchive.requestDir(packageName, requestId)
        val payload = RootlessCompatDeployArchive.buildPayload(request)
        val metadata = RootlessCompatDeployArchive.buildMetadata(
            request = request,
            requestId = requestId,
            payloadSha256 = RootlessCompatDeployArchive.sha256(payload),
            createdAtMillis = createdAtMillis,
        )
        adb.execAdbShellCmd("rm -rf $requestDir")
        adb.execAdbShellCmd("mkdir -p $requestDir")
        pushBytes(payload, "$requestDir/${RootlessCompatDeployArchive.PAYLOAD_FILE_NAME}")
        pushBytes(metadata.toByteArray(), "$requestDir/${RootlessCompatDeployArchive.REQUEST_FILE_NAME}")
        // The ready marker is written last so a half staged request is never picked up by the app.
        pushBytes(ByteArray(0), "$requestDir/${RootlessCompatDeployArchive.READY_FILE_NAME}")
        // Files written through adb keep the shell uid, so the nested request paths must stay
        // readable by the app process after scoped-storage path checks allow its own package.
        adb.execAdbShellCmd(
            "chmod 755 ${RootlessCompatDeployArchive.packageRootDir(packageName)} $requestDir && " +
                    "chmod 644 $requestDir/*",
        )
        logger.debug("Rootless compat request staged: requestId=$requestId, files=${request.files.size}")
        return RootlessCompatStagedRequest(requestId, requestDir)
    }

    private fun pushBytes(content: ByteArray, remotePath: String) {
        val localFile = File.createTempFile("jugg-rootless-compat-", ".tmp")
        try {
            localFile.writeBytes(content)
            check(adb.push(localFile, remotePath)) { "Rootless compat staging push failed: $remotePath" }
        } finally {
            localFile.delete()
        }
    }

    private fun isSafePackageName(packageName: String): Boolean {
        return PACKAGE_NAME_PATTERN.matches(packageName)
    }

    companion object {
        private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

/**
 * Reads the app-side import result of one staged rootless compat request from the device log,
 * and stops at a bounded timeout so a missing or failed import can never loop forever.
 */
class RootlessCompatImportConfirmer(
    private val adb: IDeviceAdb,
    private val logger: Logger,
) {

    /**
     * Returns the terminal result, or null when the app did not report it within [TIMEOUT_MILLIS].
     */
    fun await(requestId: String): RootlessCompatImportResult? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val output = adb.execAdbShellCmd(
                "logcat -d -s ${RootlessCompatDeployArchive.IMPORT_RESULT_TAG}:V",
            )
            RootlessCompatDeployArchive.parseImportResult(output, requestId)?.let {
                logger.debug("Rootless compat import result: requestId=$requestId, success=${it.success}")
                return it
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        logger.warn("Rootless compat import was not confirmed within ${TIMEOUT_MILLIS}ms: $requestId")
        return null
    }

    companion object {
        internal const val TIMEOUT_MILLIS = 30_000L
        internal const val POLL_INTERVAL_MILLIS = 500L
    }
}
