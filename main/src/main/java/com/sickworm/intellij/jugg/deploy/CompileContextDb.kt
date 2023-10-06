package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import java.io.File

/**
 * Manage compile context build files, e.g. apk, classpath, etc.
 */
class CompileContextDb(
    private val dbDir: File,
    private val projectDir: File,
    private val logger: Logger,
) {

    private val completeFlagFile = File(dbDir, "complete_flag")
    private val apkDirFile = File(dbDir, "apks")
    private val apkInfoFile = File(apkDirFile, "apks.json")
    private val moduleBuildPathDatFile = File(dbDir, "module_builds.dat")
    private val deployedDir = File(dbDir, "deployed")
    private val dexDeployedDir = File(deployedDir, "classes")
    private val resDeployedDir = File(deployedDir, "res")
    private val assetDeployedDir = File(deployedDir, "asset")

    val hasBeenFullCompiled: Boolean get() = completeFlagFile.exists()

    fun saveCompileContext(
        apkInfos: List<ApkInfo>,
        modules: Map<String, ModuleInfo>
    ): CompileContextInfo {
        // remove complete flag first
        completeFlagFile.delete()

        dbDir.deleteRecursively()

        // save apk info
        apkInfoFile.parentFile?.mkdirs()
        apkInfoFile.writeText(ApkInfoSerializer().serialize(apkInfos), Charsets.UTF_8)

        // save module info
        ProjectInfoSerializer(moduleBuildPathDatFile, logger).save(modules)

        // WOW! We have done!
        completeFlagFile.createNewFile()

        return CompileContextInfo(
            apkInfos,
            modules.mapValues { it.value.buildPathInfo },
        )
    }

    fun getCompileBuildPathInfoFromDb(): CompileContextInfo? {
        if (!hasBeenFullCompiled) {
            logger.debug("No compile context db found")
            return null
        }

        val apkInfos = ApkInfoSerializer().deserialize(apkInfoFile.readText())
        if (apkInfos.isEmpty()) {
            logger.warn("Failed to load apk info from db")
            completeFlagFile.delete()
            return null
        }
        val moduleBuilds = ProjectInfoSerializer(moduleBuildPathDatFile, logger).load()
        if (moduleBuilds.isNullOrEmpty()) {
            logger.warn("Failed to load module build path info from db")
            completeFlagFile.delete()
            return null
        }
        val moduleBuildPathInfos = moduleBuilds.mapValues { it.value.buildPathInfo }

        return CompileContextInfo(apkInfos, moduleBuildPathInfos)
    }

    fun updateDeployedData(deployedFiles: List<CompileOutput>) {
        deployedFiles.forEach {
            when (it.type) {
                CompileOutput.Type.Dex -> {
                    it.file.copyToBaseDir(it.baseDir, dexDeployedDir)
                }
                CompileOutput.Type.Res -> {
                    it.file.copyToBaseDir(it.baseDir, resDeployedDir)
                }
                CompileOutput.Type.Asset -> {
                    it.file.copyToBaseDir(it.baseDir, assetDeployedDir)
                }
                else -> {
                    logger.warn("Unknown output type: ${it.type} for file: ${it.file}")
                }
            }
        }
    }

    fun getDeployedData(): List<CompileOutput>? {
        if (!hasBeenFullCompiled) {
            return null
        }

        val dexFiles = dexDeployedDir.listFilesRecursively().map {
            CompileOutput(CompileOutput.Type.Dex, it, dexDeployedDir)
        }
        val overlayFiles = resDeployedDir.listFilesRecursively().map {
            CompileOutput(CompileOutput.Type.Res, it, resDeployedDir)
        }
        val assetFiles = assetDeployedDir.listFilesRecursively().map {
            CompileOutput(CompileOutput.Type.Asset, it, assetDeployedDir)
        }
        return dexFiles + overlayFiles + assetFiles
    }
}
