package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ApkEntry
import com.sickworm.intellij.jugg.deploy.api.ByteString
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.sickworm.intellij.jugg.deploy.api.DexComparator
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.api.ILogger

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
        dexOverlays: DexComparator.ChangedClasses,
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
