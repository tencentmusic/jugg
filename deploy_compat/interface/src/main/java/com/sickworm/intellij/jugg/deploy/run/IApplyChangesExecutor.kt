package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.idea.protobuf.ByteString
import com.android.utils.ILogger

/** Executes the Apply Changes transport without exposing host-specific IDE services. */
interface IApplyChangesExecutor {

    fun createInstallSession(
        installersFolder: String,
        device: IDevice,
        logger: ILogger,
        onPrompt: (String) -> Boolean,
        onMessage: (String) -> Unit,
    ): JuggInstallSession

    fun install(
        device: IDevice,
        session: JuggInstallSession,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        installMode: JuggInstallSession.Mode,
    ): Boolean

    fun getInstallMode(): JuggInstallSession.Mode

    fun parseApks(paths: List<String>): List<Apk>

    fun dumpApks(session: JuggInstallSession, apks: List<Apk>): List<Apk>

    fun getPackageName(apks: List<Apk>): String

    fun createBaseOverlayId(apks: List<Apk>): JuggOverlayId

    fun buildOverlayId(base: JuggOverlayId, addedFiles: List<JuggOverlayFile>): JuggOverlayId

    fun createOverlayUpdate(
        cachedDump: JuggDeploymentCacheEntry,
        dexOverlays: com.android.tools.deployer.DexComparator.ChangedClasses,
        fileOverlays: Map<ApkEntry, ByteString>,
    ): JuggOverlayUpdate

    fun optimisticSwap(
        session: JuggInstallSession,
        redefiners: Map<Int, JuggClassRedefiner>,
        packageName: String,
        restartActivity: Boolean,
        pids: List<Int>,
        arch: Deploy.Arch,
        overlayUpdate: JuggOverlayUpdate,
    ): JuggOverlayId

    fun createDeploymentCacheEntry(apks: List<Apk>, overlayId: JuggOverlayId): JuggDeploymentCacheEntry

    fun remoteApkNotFound(): JuggDeployerException

    fun overlayIdMismatch(): JuggDeployerException

    fun apiNotSupported(): JuggDeployerException

    fun wrapDeployerException(e: Throwable): JuggDeployerException?
}
