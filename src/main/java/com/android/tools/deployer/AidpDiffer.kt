package com.android.tools.deployer

import com.android.tools.deployer.OptimisticApkSwapper.OverlayUpdate
import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.deployer.model.DexClass
import com.android.tools.idea.protobuf.ByteString
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import java.util.*

class AidpDiffer {

    @Throws(DeployerException::class)
    fun diff(cacheEntry: DeploymentCacheDatabase.Entry?, data: AidpDeployData): OverlayUpdate {
        // from ApkDiffer
        if (cacheEntry == null) {
            // TODO: We could just fall back to non-optimistic swap.
            throw DeployerException.remoteApkNotFound()
        }

        // load apk file structure from cache
        val apkFiles: MutableList<ApkFileStructure> = ArrayList()
        for (apk in cacheEntry.apks) {
            var info = cache[apk.packageName]
            if (info == null) {
                info = parseApk(apk)
                cache[apk.packageName] = info
            }
            apkFiles.add(info)
        }

        // find out classes in AidpDeployData is modified or new
        val newClasses = mutableListOf<DexClass>()
        val modifiedClasses = mutableListOf<DexClass>()
        data.classes.forEach { dexClass ->
            val matchedFile: AidpFileInfo? = apkFiles.firstNotNullResult {
                it.classFiles[dexClass.name]
            }
            if (matchedFile == null) {
                newClasses.add(dexClass)
            } else if (matchedFile.checksum != dexClass.checksum) {
                modifiedClasses.add(dexClass)
            }
        }
        val dexOverlays = DexComparator.ChangedClasses(newClasses, modifiedClasses)

        // TODO find out overlay files
        val fileOverlays = emptyMap<ApkEntry, ByteString>()

        return OverlayUpdate(cacheEntry, dexOverlays, fileOverlays)
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
        return ApkFileStructure(classFiles, overlayFiles)
    }

    companion object {
        val cache: MutableMap<String, ApkFileStructure> = HashMap()
    }
}