package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkFileUnit
import com.android.tools.idea.run.ApkInfo
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.IntellijLibraryConfigParser
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
    private val thirdPartiesDirFile = File(dbDir, "third_parties")
    private val moduleBuildPathDirFile = File(dbDir, "module_builds")
    private val deployedDir = File(dbDir, "deployed")
    private val dexDeployedDir = File(deployedDir, "classes")
    private val overlayDeployedDir = File(deployedDir, "overlays")

    val hasBeenFullCompiled: Boolean get() = completeFlagFile.exists()

    fun copyFullCompileOutput(
        apkInfos: List<ApkInfo>,
        modules: Map<String, ModuleInfo>
    ): CompileContextInfo {
        completeFlagFile.delete()
        dbDir.deleteRecursively()

        // save apk info
        apkInfos.forEach {
            val copyApkFile = File(apkDirFile, it.file.name)
            copyApkFile.parentFile?.mkdirs()
            it.file.copyTo(copyApkFile)
        }
        val copyApks = apkInfos.map { ApkInfo(it.files, it.applicationId) }
        apkInfoFile.parentFile?.mkdirs()
        apkInfoFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(copyApks))

        // save module info
        val copyModuleBuilds = modules.mapValues { (moduleName, moduleInfo) ->
            val copyModuleBuildPathFile = File(moduleBuildPathDirFile, moduleName)
            val copyModuleBuildPathInfo = ModuleBuildPathInfo(copyModuleBuildPathFile)
            moduleInfo.buildPathInfo.javaClassPath.let {
                if (it.exists()) {
                    it.parentFile?.mkdirs()
                    it.copyRecursively(copyModuleBuildPathInfo.javaClassPath)
                }
            }
            moduleInfo.buildPathInfo.rFilePath.let {
                if (it.exists()) {
                    it.parentFile?.mkdirs()
                    it.copyTo(copyModuleBuildPathInfo.rFilePath)
                }
            }
            moduleInfo.buildPathInfo.kotlinClassPath.let {
                if (it.exists()) {
                    it.parentFile?.mkdirs()
                    it.copyRecursively(copyModuleBuildPathInfo.kotlinClassPath)
                }
            }
            return@mapValues copyModuleBuildPathInfo
        }

        // save third party lib info
        // TODO auto update when file changes
        // TODO try Class.forName("com.android.tools.idea.AndroidProjectModelUtils").declaredMethods[3].invoke(Class.forName("com.android.tools.idea.AndroidProjectModelUtils"), project)
        val thirdPartyDependenciesDir = File("$projectDir/.idea/libraries")
        val thirdPartyDependencies = IntellijLibraryConfigParser(thirdPartyDependenciesDir, projectDir.absolutePath).parse()
        if (thirdPartyDependencies.isNullOrEmpty()) {
            logger.error("No third party lib found")
        }
        val copyThirdPartyDependencies = thirdPartyDependencies?.mapNotNull {
            val file = File(it)
            if (!file.exists()) {
                return@mapNotNull null
            }
            val copyFile = File(thirdPartiesDirFile, file.path)
            copyFile.parentFile?.mkdirs()
            file.copyTo(copyFile)
            return@mapNotNull copyFile.path
        } ?: emptyList()

        completeFlagFile.createNewFile()

        return CompileContextInfo(
            copyApks,
            copyModuleBuilds,
            copyThirdPartyDependencies
        )
    }

    fun getCompileBuildPathInfoFromDb(): CompileContextInfo? {
        if (!hasBeenFullCompiled) {
            logger.warn("No compile context db found")
            return null
        }

        val apkInfos = ApkInfoSerializer().deserialize(apkInfoFile.readText())
        val moduleBuilds = moduleBuildPathDirFile.listFiles()?.associate {
            it.name to ModuleBuildPathInfo(it)
        }?: emptyMap()
        val thirdPartyDependencies = thirdPartiesDirFile.listFilesRecursively().map { it.path }

        return CompileContextInfo(apkInfos, moduleBuilds, thirdPartyDependencies)
    }

    fun updateDeployedData(deployedFiles: List<CompileOutput>) {
        deployedFiles.forEach {
            when (it.type) {
                CompileOutput.Type.Dex -> {
                    it.file.copyToBaseDir(it.baseDir, dexDeployedDir)
                }
                CompileOutput.Type.Overlay -> {
                    it.file.copyToBaseDir(it.baseDir, overlayDeployedDir)
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
        val overlayFiles = overlayDeployedDir.listFilesRecursively().map {
            CompileOutput(CompileOutput.Type.Overlay, it, overlayDeployedDir)
        }
        return dexFiles + overlayFiles
    }
}

class ApkInfoSerializer {

    fun serialize(apks: List<ApkInfo>): String {
        return GsonBuilder().setPrettyPrinting().create().toJson(apks)
    }

    fun deserialize(json: String): List<ApkInfo> {
        val apkInfos = Gson().fromJson(json, Array<ApkInfo>::class.java)
        return apkInfos.map { apkInfo ->
            // resolve file absolute path not equals after deserialize
            ApkInfo(apkInfo.files.map {
                ApkFileUnit(it.moduleName, File(it.apkFile.path))
            }, apkInfo.applicationId)
        }
    }
}