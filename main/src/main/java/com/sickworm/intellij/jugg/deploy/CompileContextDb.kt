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
    private val thirdPartiesJsonFile = File(dbDir, "third_parties.json")
    private val moduleBuildPathDirFile = File(dbDir, "module_builds")
    private val deployedDir = File(dbDir, "deployed")
    private val dexDeployedDir = File(deployedDir, "classes")
    private val overlayDeployedDir = File(deployedDir, "overlays")

    val hasBeenFullCompiled: Boolean get() = completeFlagFile.exists()

    fun copyFullCompileOutput(
        apkInfos: List<ApkInfo>,
        modules: Map<String, ModuleInfo>
    ): CompileContextInfo {
        // remove complete flag first
        completeFlagFile.delete()

        dbDir.deleteRecursively()

        // save apk info
        apkInfos.forEach {
            val copyApkFile = File(apkDirFile, it.files.first().apkFile.name)
            copyApkFile.parentFile?.mkdirs()
            it.files.first().apkFile.copyTo(copyApkFile)
        }
        val copyApks = apkInfos.map { ApkInfo(it.files, it.applicationId) }
        apkInfoFile.parentFile?.mkdirs()
        apkInfoFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(copyApks))

        // save module info
        val copyModuleBuilds = modules.mapValues { (moduleName, moduleInfo) ->
            val copyModuleBuildPathFile = File(moduleBuildPathDirFile, moduleName)
            val copyModuleBuildPathInfo = ModuleBuildPathInfo(projectDir, copyModuleBuildPathFile)
            logger.debug("copy module $moduleName, from ${moduleInfo.buildPathInfo.buildDir} to ${copyModuleBuildPathInfo.buildDir}")
            moduleInfo.buildPathInfo.javaClassPath.let {
                if (it.exists()) {
//                    logger.debug("copy dir $it to ${copyModuleBuildPathInfo.javaClassPath}")
                    it.parentFile?.mkdirs()
                    it.copyRecursively(copyModuleBuildPathInfo.javaClassPath)
                }
            }
            moduleInfo.buildPathInfo.rFilePath.let {
                if (it.exists()) {
//                    logger.debug("copy file $it to ${copyModuleBuildPathInfo.rFilePath}")
                    it.parentFile?.mkdirs()
                    it.copyTo(copyModuleBuildPathInfo.rFilePath)
                }
            }
            moduleInfo.buildPathInfo.kotlinClassPath.let {
                if (it.exists()) {
//                    logger.debug("copy dir $it to ${copyModuleBuildPathInfo.kotlinClassPath}")
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

        thirdPartiesJsonFile.delete()
        if (thirdPartyDependencies.isNullOrEmpty()) {
            logger.error("No third party lib found")
        } else {
            val text = GsonBuilder().setPrettyPrinting().create().toJson(thirdPartyDependencies)
            thirdPartiesJsonFile.writeText(text, Charsets.UTF_8)
        }

        // WOW! We have done!
        completeFlagFile.createNewFile()

        return CompileContextInfo(
            copyApks,
            copyModuleBuilds,
            thirdPartyDependencies ?: emptyList()
        )
    }

    fun getCompileBuildPathInfoFromDb(): CompileContextInfo? {
        if (!hasBeenFullCompiled) {
            logger.debug("No compile context db found")
            return null
        }

        val apkInfos = ApkInfoSerializer().deserialize(apkInfoFile.readText())
        val moduleBuilds = moduleBuildPathDirFile.listFiles()?.associate {
            it.name to ModuleBuildPathInfo(projectDir, it)
        }?: emptyMap()

        val thirdPartyDependenciesText = thirdPartiesJsonFile.readText(Charsets.UTF_8)
        val thirdPartyDependencies = if (thirdPartyDependenciesText.isEmpty()) {
            emptyList()
        } else {
            Gson().fromJson(thirdPartyDependenciesText, List::class.java).map { it.toString() }
        }

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
        // TODO use public constructor to avoid Exception when structure changed
        val apkInfos = Gson().fromJson(json, Array<ApkInfo>::class.java)
        return apkInfos.map { apkInfo ->
            // resolve file absolute path not equals after deserialize
            ApkInfo(apkInfo.files.map {
                ApkFileUnit(it.moduleName, File(it.apkFile.path))
            }, apkInfo.applicationId)
        }
    }
}