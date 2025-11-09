package com.sickworm.intellij.jugg.cmdline.incremental

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.IDependencyMissingResolver
import com.sickworm.intellij.jugg.compiler.changeBaseDir
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.project.BaseCompileContext
import com.sickworm.intellij.jugg.project.FileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Convert base build context to source compile context
 */
class CmdLineContextManager(
    private val pathManager:JuggPathManager,
    coroutineScope: CoroutineScope,
    private val logger: Logger,
) {

    val disposer = object : Disposable {
        override fun dispose() = Unit
    }

    val fileChangesHandler = FileChangesHandler(
        pathManager.projectDir,
        pathManager.juggRootDir,
        logger,
    )

    val deployFileManager = DeployFileManager(
        pathManager.projectDir,
        logger,
        pathManager.tmpDir,
        pathManager.databaseDir,
        coroutineScope,
    )

    val deployStateManager = object : IDeployStateManager {
        override fun updateDeployState(): JuggDeployState {
            return JuggDeployState.READY
        }
    }

    val dependencyMissingResolver = object : IDependencyMissingResolver {
        override fun resolve(compileResult: CompileResult): Boolean {
            return false
        }
    }

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
            juggRootDir = pathManager.juggRootDir,
            dbDir = pathManager.compileContextDbDir,
            logger = logger,
        )
        val compileContextInfo = compileContextDb.getCompileBuildPathInfoFromDb()
            ?: throw IncrementalException("Argument 'baseBuildJuggRootDir' invalid, can get compile history in it.")
        if (compileContextInfo.apkInfos.isEmpty()) {
            throw IncrementalException("Argument 'baseBuildJuggRootDir' invalid, can not found apk infos in it.")
        }

        val historyProjectDir = deployHistoryManager.historyProjectDir
            ?: throw IncrementalException("Can not found history project dir, please run 'base' command first.")

        fun File.convertSourceBaseDir(): File {
            if (!this.isChild(historyProjectDir)) {
                return this
            }
            return changeBaseDir(historyProjectDir, pathManager.projectDir)
        }
        fun List<File>.convertSourceBaseDir(): List<File> {
            return this.map {
                it.convertSourceBaseDir()
            }
        }

        val historyJuggRootDir = File(historyProjectDir, "build/jugg")
        fun File.convertBuildBaseDir(): File {
            if (!this.isChild(historyJuggRootDir)) {
                return this
            }
            return changeBaseDir(historyJuggRootDir, pathManager.juggRootDir)
        }
        fun List<LibraryDependency>.convertBuildBaseDir(): List<LibraryDependency> {
            return this.map {
                it.copy(file = it.file.convertBuildBaseDir())
            }
        }
        fun List<File>.convertBuildBaseDir(): List<File> {
            return this.map {
                it.convertBuildBaseDir()
            }
        }

        val modules = getProjectInfo().modules.mapValues { (_, baseModule) ->
            baseModule.copy(
                moduleRootDir = baseModule.moduleRootDir.convertSourceBaseDir(),
                projectRootDir = baseModule.projectRootDir.convertSourceBaseDir(),
                sourceDirs = baseModule.sourceDirs.convertSourceBaseDir(),
                resourceDirs = baseModule.resourceDirs.convertSourceBaseDir(),
                assetsDirs = baseModule.assetsDirs.convertSourceBaseDir(),
                manifestFile = baseModule.manifestFile?.convertSourceBaseDir(),
                buildPathInfo = baseModule.buildPathInfo.copy(
                    projectRootDir = baseModule.buildPathInfo.projectRootDir.convertBuildBaseDir(),
                    moduleRootDir = baseModule.buildPathInfo.moduleRootDir.convertBuildBaseDir(),
                ),
                signingConfigs = baseModule.signingConfigs?.map {
                    it.copy(
                        keystore = it.keystore?.convertSourceBaseDir(),
                    )
                },
                libraryDependencies = baseModule.libraryDependencies.convertBuildBaseDir(),
                runtimeLibraryDependencies = baseModule.runtimeLibraryDependencies.convertBuildBaseDir(),
                annotationProcessorDependencies = baseModule.libraryDependencies.convertBuildBaseDir(),
                kaptDependencies = baseModule.kaptDependencies.convertBuildBaseDir(),
                kotlinPlugins = baseModule.kotlinPlugins?.convertBuildBaseDir(),
                kotlinExtensions = baseModule.kotlinExtensions?.convertBuildBaseDir(),
                coreLibraryDesugaring = baseModule.coreLibraryDesugaring?.convertBuildBaseDir(),
                kspDependencies = baseModule.kspDependencies?.convertBuildBaseDir(),
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
        deployFileManager.init(baseContext.apkInfos, emptyList(), null)
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