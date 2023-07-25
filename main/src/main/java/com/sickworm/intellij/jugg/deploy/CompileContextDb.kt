package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkInfo
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.IntellijLibraryConfigParser
import java.io.File
import java.lang.reflect.Type

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
    private val moduleBuildPathJsonFile = File(dbDir, "module_builds.json")
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
        apkInfoFile.parentFile?.mkdirs()
        apkInfoFile.writeText(ApkInfoSerializer().serialize(apkInfos), Charsets.UTF_8)

        // save module info
        moduleBuildPathJsonFile.delete()
        val moduleBuildPathText = GsonBuilder().setPrettyPrinting().create().toJson(modules)
        moduleBuildPathJsonFile.writeText(moduleBuildPathText, Charsets.UTF_8)

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
            apkInfos,
            modules.mapValues { it.value.buildPathInfo },
            thirdPartyDependencies ?: emptyList()
        )
    }

    fun getCompileBuildPathInfoFromDb(): CompileContextInfo? {
        if (!hasBeenFullCompiled) {
            logger.debug("No compile context db found")
            return null
        }

        val apkInfos = ApkInfoSerializer().deserialize(apkInfoFile.readText())
        val moduleBuildPathText = moduleBuildPathJsonFile.readText(Charsets.UTF_8)
        val moduleBuilds = if (moduleBuildPathText.isEmpty()) {
            emptyMap()
        } else {
            val type: Type = object : TypeToken<Map<String, ModuleBuildPathInfo>>() {}.type
            Gson().fromJson(moduleBuildPathText, type) as Map<String, ModuleBuildPathInfo>
        }

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
