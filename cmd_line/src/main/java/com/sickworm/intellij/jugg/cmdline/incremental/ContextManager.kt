package com.sickworm.intellij.jugg.cmdline.incremental

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.changeBaseDir
import com.sickworm.intellij.jugg.deploy.CompileContextDb
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployHistoryManager
import com.sickworm.intellij.jugg.project.BaseCompileContext
import com.sickworm.intellij.jugg.project.FileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Convert base build context to source compile context
 */
class ContextManager(
    private val pathManager:JuggPathManager,
    coroutineScope: CoroutineScope,
    private val logger: Logger,
) {

    val fileChangesHandler = FileChangesHandler(
        pathManager.projectDir,
        pathManager.juggRootDir,
        logger,
    )

    private val deployFileManager = DeployFileManager(
        logger,
        pathManager.tmpDir,
        pathManager.databaseDir,
        coroutineScope,
    )

    private val deployHistoryManager = DeployHistoryManager(pathManager, fileChangesHandler, logger)

    lateinit var compileContext: ICompileContext

    fun init(): ICompileContext {
        compileContext = createCompileContext()
        return compileContext
    }

    private fun createCompileContext(): ICompileContext {
        val envValue = System.getenv("ANDROID_HOME")
            ?: throw IncrementalException("Environment variable ANDROID_HOME is not set.")
        val androidHome = File(envValue)
        if (!androidHome.exists()) {
            throw IncrementalException("Environment variable ANDROID_HOME($androidHome) not exists.")
        }

        val cmdCompileEnv = System.getenv().entries
            .map {
                "${it.key}=${it.value}"
            }
            .toMutableList()

        val compileContextDb = CompileContextDb(
            dbDir = pathManager.compileContextDbDir,
            logger = logger,
        )
        val compileContextInfo = compileContextDb.getCompileBuildPathInfoFromDb()
            ?: throw IncrementalException("Argument 'baseBuildProjectDir' invalid, can get compile history in it.")
        if (compileContextInfo.apkInfos.isEmpty()) {
            throw IncrementalException("Argument 'baseBuildProjectDir' invalid, can not found apk infos in it.")
        }

        val historyProjectDir = deployHistoryManager.historyProjectDir
            ?: throw IncrementalException("Can not found history project dir, please run 'base' command first.")
        fun File.baseToSource(): File {
            return changeBaseDir(historyProjectDir, pathManager.projectDir)
        }

        val modules = getProjectInfo().modules.mapValues { (_, baseModule) ->
            baseModule.copy(
                moduleRootDir = baseModule.moduleRootDir.baseToSource(),
                projectRootDir = baseModule.projectRootDir.baseToSource(),
                sourceDirs = baseModule.sourceDirs.map { it.baseToSource() },
                resourceDirs = baseModule.resourceDirs.map { it.baseToSource() },
                assetsDirs = baseModule.assetsDirs.map { it.baseToSource() },
                manifestFile = baseModule.manifestFile?.baseToSource(),
                buildPathInfo = baseModule.buildPathInfo, // use base directly
                signingConfigs = baseModule.signingConfigs?.map {
                    it.copy(
                        keystore = it.keystore?.baseToSource(),
                    )
                },
            )
        }

        val baseContext = BaseCompileContext(
            logger = logger,
            androidHome = androidHome,
            tempCompileDir = File(pathManager.compileRootDir, "compiled"),
            tempModuleDir = File(pathManager.compileRootDir, "temp_module"),
            modules = modules,
            projectDir = pathManager.projectDir,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            incrementalDataDir = File(pathManager.compileRootDir, "incremental"),
            cmdCompileEnv = cmdCompileEnv,
            apkInfos = compileContextInfo.apkInfos,
            scene = ICompileContext.Scene.INCREMENTAL_APK
        )

        fileChangesHandler.init(baseContext)
        return baseContext
    }

    private fun getProjectInfo(): JuggProjectInfo {
        val gradleProjectInfoFile = pathManager.gradleProjectInfoFile
        if (!gradleProjectInfoFile.exists()) {
            throw IncrementalException("Gradle project info file not exists: ${gradleProjectInfoFile.absolutePath}")
        }
        val gradleProjectInfo = ProjectInfoSerializer(gradleProjectInfoFile, logger).load()
            ?: throw IncrementalException("Gradle project info file invalid: ${gradleProjectInfoFile.absolutePath}")
        if (gradleProjectInfo.modules.isEmpty()) {
            throw IncrementalException("Gradle project info file invalid: ${gradleProjectInfoFile.absolutePath}")
        }
        return gradleProjectInfo
    }

}