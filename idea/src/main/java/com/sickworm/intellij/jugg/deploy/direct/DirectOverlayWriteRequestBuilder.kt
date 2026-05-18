package com.sickworm.intellij.jugg.deploy.direct

import com.android.tools.deployer.OverlayId
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import java.util.Locale

/**
 * DirectOverlayWriteRequestBuilder converts Jugg overlay updates to direct writer requests.
 * It intentionally mirrors OptimisticApkUpdater path and overlay-id construction rules.
 */
class DirectOverlayWriteRequestBuilder {

    fun build(packageName: String, overlayUpdate: JuggOverlayUpdate): DirectOverlayPreparedRequest {
        val overlayIdBuilder = OverlayId.builder(overlayUpdate.cachedDump.overlayId)
        val files = mutableListOf<DirectOverlayWriteFile>()

        overlayUpdate.dexOverlays.newClasses.forEach { clazz ->
            val path = String.format(Locale.US, "%s.dex", clazz.name)
            overlayIdBuilder.addOverlayFile(path, clazz.checksum)
            files += DirectOverlayWriteFile(path, clazz.code)
        }

        overlayUpdate.dexOverlays.modifiedClasses.forEach { clazz ->
            val path = String.format(Locale.US, "%s.dex", clazz.name)
            overlayIdBuilder.addOverlayFile(path, clazz.checksum)
            files += DirectOverlayWriteFile(path, clazz.code)
        }

        overlayUpdate.fileOverlays.entries.forEach { entry ->
            overlayIdBuilder.addOverlayFile(entry.key.qualifiedPath, entry.key.checksum)
            files += DirectOverlayWriteFile(entry.key.qualifiedPath, entry.value.toByteArray())
        }

        val overlayId = overlayIdBuilder.build()
        val expectedOverlayId = overlayUpdate.cachedDump.overlayId.let {
            if (it.isBaseInstall) "" else it.sha
        }
        return DirectOverlayPreparedRequest(
            request = DirectOverlayWriteRequest(
                packageName = packageName,
                expectedOverlayId = expectedOverlayId,
                overlayId = overlayId.sha,
                files = files,
            ),
            overlayId = overlayId,
        )
    }
}

data class DirectOverlayPreparedRequest(
    val request: DirectOverlayWriteRequest,
    val overlayId: OverlayId,
)
