package com.android.tools.deployer

import com.android.tools.deployer.DexComparator.ChangedClasses
import com.android.tools.deployer.OptimisticApkSwapper.OverlayUpdate
import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.deployer.model.DexClass
import com.android.tools.idea.protobuf.ByteString
import com.android.utils.ILogger
import com.sickworm.intellij.aidp.AidpException
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import java.util.*

class AidpDiffer(private val logger: ILogger) {

    @Throws(DeployerException::class)
    fun diff(cacheEntry: DeploymentCacheDatabase.Entry?, data: AidpDeployData): AidpOverlayUpdate {
        // from ApkDiffer
        if (cacheEntry == null) {
            // TODO: We could just fall back to non-optimistic swap.
            throw DeployerException.remoteApkNotFound()
        }

        // load apk file structure from cache, overlayCache needs to be first
        val apkFiles: MutableList<ApkFileStructure> = mutableListOf(overlayCache)
        for (apk in cacheEntry.apks) {
            var info = apkCache[apk.packageName]
            if (info == null) {
                info = parseApk(apk)
                apkCache[apk.packageName] = info
            }
            apkFiles.add(info)
        }

        // find out classes in AidpDeployData is modified or new
        val newClasses = mutableListOf<DexClass>()
        val modifiedClasses = mutableListOf<DexClass>()
        data.classes.forEach { clazz ->
            // TODO speedup when R file updates
            val matchedFile: AidpFileInfo? = apkFiles.firstNotNullResult {
                it.classFiles[clazz.name]
            }
            if (matchedFile == null) {
                newClasses.add(clazz.toIncompleteDexClass())
            } else if (matchedFile.checksum != clazz.checksum) {
                modifiedClasses.add(clazz.toIncompleteDexClass())
            }
        }
        val dexOverlays = ChangedClasses(newClasses, modifiedClasses)

        if (cacheEntry.apks.size > 1 && data.overlays.isNotEmpty()) {
            throw AidpException.notSupportMultiApkOverlays()
        }
        val overlayFiles = data.overlays.associate {
            it.toIncompleteOverlay(cacheEntry.apks.first())
        }

        logDiffResult(newClasses, modifiedClasses, overlayFiles)

        return AidpOverlayUpdate(cacheEntry, dexOverlays, overlayFiles)
    }

    @Throws(DeployerException::class)
    private fun parseApk(apk: Apk): ApkFileStructure {
        val classFiles = mutableMapOf<String, AidpFileInfo>()
        val overlayFiles = mutableMapOf<String, AidpFileInfo>()
        val splitter: DexSplitter = D8DexSplitter()
        for (entry in apk.apkEntries.values) {
            if (entry.name.endsWith(".dex")) {
                val dexClasses = splitter.split(entry) { true }
                for (dexClass in dexClasses) {
                    classFiles[dexClass.name] = AidpFileInfo(dexClass.name, dexClass.checksum)
                }
            } else {
                overlayFiles[entry.name] = AidpFileInfo(entry.name, entry.checksum)
            }
        }

        logger.info("parseApk ${apk.name} with ${classFiles.size} classes and ${overlayFiles.size} overlays")
        return ApkFileStructure(classFiles, overlayFiles)
    }

    private fun logDiffResult(newClasses: List<DexClass>,
                              modifiedClasses: List<DexClass>,
                              overlayFiles: Map<ApkEntry, ByteString>) {

        val resultLog = StringBuilder()
        if (newClasses.isNotEmpty()) {
            resultLog.append("\nnew classes:\n")
            resultLog.append(newClasses.toLogStringDex())
        }
        if (modifiedClasses.isNotEmpty()) {
            resultLog.append("\nmodified classes:\n")
            resultLog.append(modifiedClasses.toLogStringDex())
        }
        if (overlayFiles.isNotEmpty()) {
            resultLog.append("\noverlay files:\n")
            resultLog.append(overlayFiles.map{ it.key }.toLogStringEntry())
        }
        if (resultLog.isEmpty()) {
            logger.warning("nothing to deploy")
        } else {
            logger.info("deploy files: $resultLog")
        }
    }

    fun convert(overlayUpdate: AidpOverlayUpdate): OverlayUpdate {
        return overlayUpdate.overlayUpdate
    }

    @Synchronized
    fun update(overlayUpdate: AidpOverlayUpdate): Boolean {
        val classFiles = overlayUpdate.dexOverlays.newClasses.map { it.name to AidpFileInfo(it.name, it.checksum) } +
                overlayUpdate.dexOverlays.modifiedClasses.map { it.name to AidpFileInfo(it.name, it.checksum) }
        val overlayFiles = overlayUpdate.fileOverlays.keys.map { it.name to AidpFileInfo(it.name, it.checksum) }
        overlayCache = ApkFileStructure(
            classFiles = overlayCache.classFiles + classFiles.toMap(),
            overlayFiles = overlayCache.overlayFiles + overlayFiles.toMap()
        )
        return true
    }

    companion object {
        val apkCache: MutableMap<String, ApkFileStructure> = HashMap()
        var overlayCache: ApkFileStructure = ApkFileStructure(emptyMap(), emptyMap())
    }
}

/**
 * [OverlayUpdate] fields are all private, so we need this.
 */
data class AidpOverlayUpdate(
    val cachedDump: DeploymentCacheDatabase.Entry?,
    val dexOverlays: ChangedClasses,
    val fileOverlays: Map<ApkEntry, ByteString>
) {
    val overlayUpdate = OverlayUpdate(cachedDump, dexOverlays, fileOverlays)
}