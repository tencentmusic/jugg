package com.sickworm.intellij.jugg.deploy.direct

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayFile
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayId
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import java.util.Locale

/**
 * DirectOverlayWriteRequestBuilder converts Jugg overlay updates to direct writer requests.
 * It intentionally mirrors OptimisticApkUpdater path and overlay-id construction rules.
 */
class DirectOverlayWriteRequestBuilder(
    private val logger: Logger? = null,
) {

    fun build(
        packageName: String,
        overlayUpdate: JuggOverlayUpdate,
        asDeployerCompat: IAsDeployerCompat,
        isFullResourcePush: Boolean,
    ): DirectOverlayPreparedRequest {
        val overlayFiles = linkedMapOf<String, JuggOverlayFile>()
        val files = linkedMapOf<String, DirectOverlayWriteFile>()
        val duplicatePaths = mutableListOf<String>()
        fun addOverlayFile(path: String, checksum: Long, content: ByteArray) {
            if (files.containsKey(path)) {
                duplicatePaths += path
                return
            }
            overlayFiles[path] = JuggOverlayFile(path, checksum)
            files[path] = DirectOverlayWriteFile(path, content)
        }

        overlayUpdate.dexOverlays.newClasses.forEach { clazz ->
            val path = String.format(Locale.US, "%s.dex", clazz.name)
            addOverlayFile(path, clazz.checksum, clazz.code)
        }

        overlayUpdate.dexOverlays.modifiedClasses.forEach { clazz ->
            val path = String.format(Locale.US, "%s.dex", clazz.name)
            addOverlayFile(path, clazz.checksum, clazz.code)
        }

        overlayUpdate.fileOverlays.entries.forEach { entry ->
            addOverlayFile(entry.key.qualifiedPath, entry.key.checksum, entry.value.toByteArray())
        }

        if (duplicatePaths.isNotEmpty()) {
            logger?.debug(
                "Direct overlay request dropped duplicate files: " +
                        "count=${duplicatePaths.size}, samples=${duplicatePaths.distinct().take(5)}"
            )
        }

        val overlayId = asDeployerCompat.buildOverlayId(overlayUpdate.cachedDump.overlayId, overlayFiles.values.toList())
        val expectedOverlayId = overlayUpdate.cachedDump.overlayId.let {
            if (it.isBaseInstall) "" else it.sha
        }
        return DirectOverlayPreparedRequest(
            request = DirectOverlayWriteRequest(
                packageName = packageName,
                expectedOverlayId = expectedOverlayId,
                overlayId = overlayId.sha,
                files = files.values.toList(),
                isFullResourcePush = isFullResourcePush,
                skipPayloadCleanup = expectedOverlayId.isEmpty(),
            ),
            overlayId = overlayId,
        )
    }
}

data class DirectOverlayPreparedRequest(
    val request: DirectOverlayWriteRequest,
    val overlayId: JuggOverlayId,
)
