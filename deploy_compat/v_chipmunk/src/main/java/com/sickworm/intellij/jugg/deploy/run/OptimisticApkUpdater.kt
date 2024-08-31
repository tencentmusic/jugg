package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deploy.proto.Deploy
import com.android.tools.deploy.proto.Deploy.OverlayFile
import com.android.tools.deployer.*
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.idea.protobuf.ByteString
import java.io.IOException
import java.util.*

/**
 * @see OptimisticApkSwapper
 */
class OptimisticApkUpdater(
    private val installer: Installer,
    private val redefiners: Map<Int, ClassRedefiner>,
) {
    @Throws(DeployerException::class)
    fun pushOverlays(
        packageId: String?,
        pids: List<Int?>,
        arch: Deploy.Arch?,
        overlayUpdate: JuggOverlayUpdate
    ): UpdateResult {
        val cachedDump = overlayUpdate.cachedDump
        val dexOverlays = overlayUpdate.dexOverlays
        val fileOverlays: Map<ApkEntry, ByteString> = overlayUpdate.fileOverlays
        val overlayIdBuilder = OverlayId.builder(cachedDump.overlayId)
        val expectedOverlayId = cachedDump.overlayId
        val request = Deploy.OverlayInstallRequest.newBuilder()
            .setPackageName(packageId)
            .setArch(arch)
            .setExpectedOverlayId(if (expectedOverlayId.isBaseInstall) "" else expectedOverlayId.sha)

        var hasDebuggerAttached = false
        pids.forEach { pid ->
            if (redefiners.containsKey(pid)) {
                hasDebuggerAttached = true
            }
        }

        dexOverlays.newClasses.forEach { clazz ->
            val file = String.format(Locale.US, "%s.dex", clazz.name)
            overlayIdBuilder.addOverlayFile(file, clazz.checksum)
            val overlayFile = OverlayFile.newBuilder()
                .setPath(file)
                .setContent(ByteString.copyFrom(clazz.code))
                .build()
            request.addOverlayFiles(overlayFile)
        }

        dexOverlays.modifiedClasses.forEach { clazz ->
            val file = String.format(Locale.US, "%s.dex", clazz.name)
            overlayIdBuilder.addOverlayFile(file, clazz.checksum)
            val overlayFile = OverlayFile.newBuilder()
                .setPath(file)
                .setContent(ByteString.copyFrom(clazz.code))
                .build()
            request.addOverlayFiles(overlayFile)
        }

        fileOverlays.entries.forEach { entry ->
            overlayIdBuilder.addOverlayFile(entry.key.qualifiedPath, entry.key.checksum)
            val overlayFile = OverlayFile.newBuilder()
                .setPath(entry.key.qualifiedPath)
                .setContent(entry.value)
                .build()
            request.addOverlayFiles(overlayFile)
        }

        val overlayId = overlayIdBuilder.build()
        request.setOverlayId(overlayId.sha)
        if (hasDebuggerAttached) {
            try {
                val response = installer.verifyOverlayId(request.packageName, request.expectedOverlayId)
                if (response.status != Deploy.OverlayIdPushResponse.Status.OK) {
                    throw DeployerException.overlayIdMismatch()
                }
            } catch (var17: IOException) {
                val e = var17
                throw DeployerException.installerIoException(e)
            }
        }

        // Caused by: java.lang.IncompatibleClassChangeError: Found class com.android.tools.deployer.Installer, but interface was expected
        val method = Installer::class.java.getMethod("overlayInstall", Deploy.OverlayInstallRequest::class.java)
        val response = method.invoke(installer, request.build()) as Deploy.OverlayInstallResponse

        if (response.status != Deploy.OverlayInstallResponse.Status.OK) {
            // JuggDeployerHelper will use response.status to consider retry
            throw IllegalStateException("OptimisticApkUpdater failed, status: ${response.status}")
        }
        return UpdateResult(overlayId, isSuccess = true)
    }

    class UpdateResult(val overlayId: OverlayId, val isSuccess: Boolean)

}