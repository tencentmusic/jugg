package com.sickworm.intellij.jugg.deploy.direct

import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayFile
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayId
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import java.util.Locale

/**
 * DirectOverlayWriteRequestBuilder converts Jugg overlay updates to direct writer requests.
 * It intentionally mirrors OptimisticApkUpdater path and overlay-id construction rules.
 */
class DirectOverlayWriteRequestBuilder {

    fun build(
        packageName: String,
        overlayUpdate: JuggOverlayUpdate,
        asDeployerCompat: IAsDeployerCompat,
        isFullResourcePush: Boolean,
    ): DirectOverlayPreparedRequest {
        val overlayFiles = mutableListOf<JuggOverlayFile>()
        val files = mutableListOf<DirectOverlayWriteFile>()

        overlayUpdate.dexOverlays.newClasses.forEach { clazz ->
            val path = String.format(Locale.US, "%s.dex", clazz.name)
            overlayFiles += JuggOverlayFile(path, clazz.checksum)
            files += DirectOverlayWriteFile(path, clazz.code)
        }

        overlayUpdate.dexOverlays.modifiedClasses.forEach { clazz ->
            val path = String.format(Locale.US, "%s.dex", clazz.name)
            overlayFiles += JuggOverlayFile(path, clazz.checksum)
            files += DirectOverlayWriteFile(path, clazz.code)
        }

        overlayUpdate.fileOverlays.entries.forEach { entry ->
            overlayFiles += JuggOverlayFile(entry.key.qualifiedPath, entry.key.checksum)
            files += DirectOverlayWriteFile(entry.key.qualifiedPath, entry.value.toByteArray())
        }

        val overlayId = asDeployerCompat.buildOverlayId(overlayUpdate.cachedDump.overlayId, overlayFiles)
        val expectedOverlayId = overlayUpdate.cachedDump.overlayId.let {
            if (it.isBaseInstall) "" else it.sha
        }
        return DirectOverlayPreparedRequest(
            request = DirectOverlayWriteRequest(
                packageName = packageName,
                expectedOverlayId = expectedOverlayId,
                overlayId = overlayId.sha,
                files = files,
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
