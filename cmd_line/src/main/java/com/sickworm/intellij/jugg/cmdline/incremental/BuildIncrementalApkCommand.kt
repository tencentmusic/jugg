package com.sickworm.intellij.jugg.cmdline.incremental

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.apk.ApkFileModifier
import com.sickworm.intellij.jugg.cmdline.logger.CmdLineLogger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.CompileContextDb
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployHistoryManager
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.toDeployItem
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.BaseCompileContext
import com.sickworm.intellij.jugg.project.FileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File

class BuildIncrementalApkCommand(private val params: Params) {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val baseBuildPathManager = JuggPathManager(params.baseBuildProjectDir)
    private val logger = run {
        CmdLineLogger.init(JuggPathManager(params.sourceProjectDir).logDir, params.logLevel)
        CmdLineLogger.logger
    }

    private val dirtyFlag = File(baseBuildPathManager.juggRootDir, ".dirty")

    fun run(): Boolean {
        try {
            logger.info("Init compile context...")
            TimeLogger.start("Init compile context")
            checkDirty()
            val contextConverter = getContextConverter()
            val compiler = getCompiler(contextConverter.sourceContext)
            val compileTask = getCompileTask(contextConverter.fileChangesHandler)
            TimeLogger.end("Init compile context", logger)

            val compileResult = compiler.compile(compileTask)
            if (!compileResult.isAllSuccess) {
                logger.warn("Compile failed, exit.")
                return false
            }
            logger.info("Compile success.")
            updateApk(contextConverter.sourceContext, compileResult)
            logger.info("Compile apk success.")
            return true
        } catch (e: IncrementalException) {
            logger.warn("Compile failed", e)
            logger.warn("Compile failed, reason: ${e.message}")
            return false
        } catch (e: Throwable) {
            logger.warn("Compile failed unexpected", e)
            logger.warn("Compile got unexpected error: ${e.message}")
            return false
        }
    }

    private fun checkDirty() {
        if (dirtyFlag.exists()) {
            throw IncrementalException("Argument 'baseBuildProjectDir' invalid, $dirtyFlag exists, which means directory was compiled before.")
        }
        dirtyFlag.parentFile.mkdirs()
        dirtyFlag.createNewFile()
    }

    private fun getContextConverter(): ContextConverter {
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
            dbDir = baseBuildPathManager.compileContextDbDir,
            logger = logger,
        )
        val compileContextInfo = compileContextDb.getCompileBuildPathInfoFromDb()
            ?: throw IncrementalException("Argument 'baseBuildProjectDir' invalid, can get compile history in it.")
        if (compileContextInfo.apkInfos.isEmpty()) {
            throw IncrementalException("Argument 'baseBuildProjectDir' invalid, can not found apk infos in it.")
        }

        val baseContext = BaseCompileContext(
            logger = logger,
            androidHome = androidHome,
            tempCompileDir = File(baseBuildPathManager.compileRootDir, "compiled"),
            tempModuleDir = File(baseBuildPathManager.compileRootDir, "temp_module"),
            modules = getProjectInfo().modules,
            projectDir = baseBuildPathManager.projectDir,
            deployFileManager = DeployFileManager(
                logger,
                baseBuildPathManager.tmpDir,
                baseBuildPathManager.databaseDir,
                coroutineScope,
            ),
            deployHistoryManager = DeployHistoryManager(
                baseBuildPathManager,
                FileChangesHandler(
                    baseBuildPathManager.projectDir,
                    baseBuildPathManager.juggRootDir,
                    logger,
                ),
                logger,
            ),
            incrementalDataDir = File(baseBuildPathManager.compileRootDir, "incremental"),
            cmdCompileEnv = cmdCompileEnv,
            apkInfos = compileContextInfo.apkInfos,
            scene = ICompileContext.Scene.INCREMENTAL_APK
        )
        return ContextConverter(baseContext, params.baseBuildProjectDir, params.sourceProjectDir, coroutineScope, logger)
    }

    private fun getProjectInfo(): JuggProjectInfo {
        val gradleProjectInfoFile = baseBuildPathManager.gradleProjectInfoFile
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

    private fun getCompiler(context: ICompileContext): ICompiler {
        val idleDisposer = object : Disposable {
            override fun dispose() = Unit
        }
        val juggServer = JuggServer(baseBuildPathManager.projectDir.name, baseBuildPathManager, coroutineScope, logger)
        val customCompilerManager = CustomCompilerManager(baseBuildPathManager.projectDir, baseBuildPathManager.customCompilerDir, juggServer, logger)
        return JuggCompiler(context, idleDisposer, customCompilerManager::getCustomCompilers)
    }

    private fun getCompileTask(fileChangesHandler: FileChangesHandler): CompileTask {
        val changedFiles = params.changedFiles // changed files comes from source project dir
        if (changedFiles.isEmpty()) {
            throw IncrementalException("Argument 'changedFiles' is empty.")
        }
        changedFiles.forEach {
            if (!it.exists()) {
                throw IncrementalException("Argument 'changedFiles' file not exists: ${it.path}")
            }
            if (!it.isChild(params.sourceProjectDir)) {
                throw IncrementalException("Argument 'changedFiles' file is not in source project dir: ${it.path}, 'sourceProjectDir': ${params.sourceProjectDir}")
            }
        }

        // build source dir FileChangesHandler
        val changedCompileFiles = fileChangesHandler.filter(changedFiles)
        if (changedCompileFiles.size != changedFiles.size) {
            throw IncrementalException("Files check failed, not all files are compilable. " +
                    "changedFiles:\n${changedFiles.joinToString("\n", prefix = "    ") { it.path }}" +
                    "compileFiles:\n${changedCompileFiles.joinToString("\n", prefix = "    ") { it.file.path }}"
            )
        }
        changedCompileFiles.forEach {
            if (it.type == CompileFile.Type.BuildFile) {
                throw IncrementalException("Argument 'changedFiles' contains build file: ${it.file.path}")
            }
        }

        val compileFiles: List<CompileFile> = changedCompileFiles.map {
            CompileFile(it.type, it.file, it.baseDir, it.module)
        }
        return CompileTask(compileFiles, baseBuildPathManager.stagingDir, CompileStatusHolder.DEFAULT)
    }

    private fun updateApk(context: ICompileContext, compileResult: CompileResult) {
        val baseApk = context.apkInfos.firstOrNull { it.baseApk != null }?.baseApk?.apkFile
        val allApks = context.apkInfos.flatMap { it.files }.map { it.apkFile }
        if (baseApk == null) {
            throw IncrementalException("Can not found base APK, all APKs: $allApks")
        }
        if (allApks.isEmpty()) {
            throw IncrementalException("Can not found any APK in base build project dir: ${context.projectDir.absolutePath}")
        }

        val signingConfig = context.signingConfig
        if (signingConfig == null || signingConfig.isInvalid) {
            throw IncrementalException("Unable to update APK, signing config not found.")
        }

        allApks.forEach { apkFile ->
            val isBaseApk = apkFile == baseApk
            val modifier = ApkFileModifier(apkFile, signingConfig, context.androidHome, logger, context.cmdCompileEnv)
            val deployItems = mutableListOf<DeployItem>()
            compileResult.outputs.forEach {
                val isBaseOutput = it.apkPath == null || it.apkPath == DeployItem.FLAG_CLASS || it.apkPath == DeployItem.FLAG_BASE_APK
                if ((isBaseApk && isBaseOutput) || (it.apkPath == apkFile.path)) {
                    val deployItem = it.toDeployItem(prefix = ".jugg/")
                    deployItems.add(deployItem)
                }
            }
            logger.debug("Update apk: $apkFile\nDeploy items:\n${deployItems.joinToString("\n", "    ") { it.name }}\n")
            if (deployItems.isNotEmpty()) {
                deployItems.forEach {
                    modifier.addFile(it.name, it.content)
                }
                try {
                    modifier.insertAndResign()
                } catch (e: Exception) {
                    throw IncrementalException("Update apk failed: ${apkFile.absolutePath}", e)
                }
            }
            val outputApkFile = File(params.outputApkDir, apkFile.name)
            apkFile.copyTo(outputApkFile, true)
            if (!outputApkFile.exists() || outputApkFile.length() == 0L) {
                throw IncrementalException("Copy apk failed, apk file not exists: ${apkFile.absolutePath}")
            }
            logger.info("Update apk success, output: ${outputApkFile.absolutePath}")
        }
    }

    companion object {
        fun run(args: Array<String>): Boolean {
            val params = ParamsParser().parse(args)
            if (params == null) {
                CmdLineLogger.stdLogger.warn("Parse params invalid, exit.")
                return false
            }
            return BuildIncrementalApkCommand(params).run()
        }
    }
}