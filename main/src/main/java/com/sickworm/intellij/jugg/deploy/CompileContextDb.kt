package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Manage compile context build files, e.g. apk, classpath, etc.
 */
class CompileContextDb(
    private val juggRootDir: File,
    private val dbDir: File,
    private val logger: Logger,
) {

    private val completeFlagFile = File(dbDir, "complete_flag")
    private val lastFullCompileFailedFlag = File(dbDir, "last_full_compile_failed_flag")
    private val apkDirFile = File(dbDir, "apks")
    private val apkInfoFile = File(apkDirFile, "apks.json")
    private val fullBuildInfoFile = File(dbDir, "full_build_info.json")
    private val moduleBuildPathDatFile = File(dbDir, "module_builds.json")

    private val deployedDir = File(dbDir, "deployed")
    private val dexDeployedDir = File(deployedDir, "classes")

    val hasBeenFullCompiled: Boolean get() = completeFlagFile.exists()

    var isLastFullCompileFailed: Boolean
        get() = lastFullCompileFailedFlag.exists()
        set(value) {
            if (value) {
                lastFullCompileFailedFlag.parentFile?.mkdirs()
                lastFullCompileFailedFlag.createNewFile()
            } else {
                lastFullCompileFailedFlag.delete()
            }
        }

    private var apkInfosCache: List<ApkInfo>? = if (apkInfoFile.exists()) {
        ApkInfoSerializer().deserialize(juggRootDir, apkInfoFile.readText())
    } else {
        null
    }

    fun saveCompileContext(
        fullBuildInfo: FullBuildInfo,
        apkInfos: List<ApkInfo>,
        modules: Map<String, ModuleInfo>
    ): CompileContextInfo {
        // remove complete flag first
        deleteCompileContext()

        // save full build info
        saveFullBuildInfo(fullBuildInfo)

        // save apk info
        apkInfoFile.parentFile?.mkdirs()
        apkInfoFile.writeText(ApkInfoSerializer().serialize(juggRootDir, apkInfos), Charsets.UTF_8)
        apkInfosCache = apkInfos

        // save module info
        BuildPathInfoSerializer(moduleBuildPathDatFile, logger).save(modules)

        // WOW! We have done!
        completeFlagFile.createNewFile()

        return CompileContextInfo(
            apkInfos,
            modules.mapValues { it.value.buildPathInfo },
        )
    }


    fun saveFullBuildInfo(fullBuildInfo: FullBuildInfo) {
        fullBuildInfoFile.parentFile?.mkdirs()
        fullBuildInfoFile.writeText(FullBuildInfoSerializer().serialize(fullBuildInfo), Charsets.UTF_8)
    }

    fun getFullBuildInfoFromDb(): FullBuildInfo? {
        if (!fullBuildInfoFile.exists()) {
            return null
        }
        return try {
            FullBuildInfoSerializer().deserialize(fullBuildInfoFile.readText())
        } catch (e: Exception) {
            logger.warn("Failed to load full build info from ${fullBuildInfoFile.absolutePath}", e)
            null
        }
    }

    fun deleteCompileContext() {
        completeFlagFile.delete()
        dbDir.deleteRecursively()
    }

    fun getCompileBuildPathInfoFromDb(): CompileContextInfo? {
        if (!hasBeenFullCompiled) {
            logger.debug("No compile context db found")
            return null
        }

        val apkInfos = ApkInfoSerializer().deserialize(juggRootDir, apkInfoFile.readText())
        if (apkInfos.isEmpty()) {
            logger.warn("Failed to load apk info from db")
            completeFlagFile.delete()
            return null
        }
        apkInfos.forEach { apkInfo ->
            apkInfo.files.forEach { apkFileUnit ->
                if (apkFileUnit.apkFile.exists().not()) {
                    logger.warn("Apk file not exists: ${apkFileUnit.apkFile}")
                    completeFlagFile.delete()
                    return null
                }
            }
        }

        val moduleBuilds = BuildPathInfoSerializer(moduleBuildPathDatFile, logger).load()
        if (moduleBuilds.isNullOrEmpty()) {
            logger.warn("Failed to load module build path info from db")
            completeFlagFile.delete()
            return null
        }
        return CompileContextInfo(apkInfos, moduleBuilds)
    }

    fun updateDeployedData(deployedFiles: List<CompileOutput>) {
        deployedFiles.forEach {
            when (it.type) {
                CompileOutput.Type.Dex -> {
                    it.file.copyToBaseDir(it.baseDir, dexDeployedDir)
                }
                CompileOutput.Type.Res -> {
                    val resDeployedDir = getDeployStorageDir(it.apkPath, CompileOutput.Type.Res)
                    it.file.copyToBaseDir(it.baseDir, resDeployedDir)
                }
                CompileOutput.Type.Asset -> {
                    val assetDeployedDir = getDeployStorageDir(it.apkPath, CompileOutput.Type.Asset)
                    it.file.copyToBaseDir(it.baseDir, assetDeployedDir)
                }
                CompileOutput.Type.NativeLib -> {
                    // no-op, it has already updated to APK
                }
                else -> {
                    logger.debug("Unknown output type: ${it.type} for file: ${it.file}")
                }
            }
        }
    }

    fun getDeployedData(): List<CompileOutput>? {
        if (!hasBeenFullCompiled) {
            return null
        }

        val dexFiles = dexDeployedDir.listFilesRecursively().mapNotNull {
            if (it.extension != "dex") {
                return@mapNotNull null
            }
            CompileOutput(CompileOutput.Type.Dex, it, dexDeployedDir)
        }

        val overlayFiles = mutableListOf<CompileOutput>()
        val assetFiles = mutableListOf<CompileOutput>()

        val apkFileUnits = apkInfosCache?.flatMap { it.files }
        apkFileUnits?.forEach { apkFileUnit ->
            val apkPath = apkFileUnit.apkFile.path
            val resDir = getDeployStorageDir(apkPath, CompileOutput.Type.Res)
            val subOverlayFiles = resDir.listFilesRecursively().map {
                CompileOutput(CompileOutput.Type.Res, it, resDir, apkPath)
            }
            overlayFiles.addAll(subOverlayFiles)
            val assetsDir = getDeployStorageDir(apkPath, CompileOutput.Type.Asset)
            val subAssetFiles = assetsDir.listFilesRecursively().map {
                CompileOutput(CompileOutput.Type.Asset, it, assetsDir, apkPath)
            }
            assetFiles.addAll(subAssetFiles)
        }
        return dexFiles + overlayFiles + assetFiles
    }

    private fun getDeployStorageDir(apkPath: String?, type: CompileOutput.Type): File {
        val baseDirName = if (type == CompileOutput.Type.Asset) {
            "asset"
        } else {
            "res"
        }
        return File(deployedDir, getDeployResDirName(apkPath, baseDirName))
    }

    private fun getDeployResDirName(apkPath: String?, baseDirName: String): String {
        if (apkPath == null) {
            return baseDirName
        }
        val isBaseApk = apkInfosCache
            ?.flatMap { it.files }
            ?.find { it.apkFile.path == apkPath }
            ?.isBaseApk
        if (isBaseApk == true) {
            return baseDirName
        }
        return baseDirName + "_" + ApkFileUnit.getUniqueKey(apkPath)
    }
}
