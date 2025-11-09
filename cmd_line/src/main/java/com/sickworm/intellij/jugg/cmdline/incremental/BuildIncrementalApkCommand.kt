package com.sickworm.intellij.jugg.cmdline.incremental

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.apk.ApkFileModifier
import com.sickworm.intellij.jugg.cmdline.logger.CmdLineLogger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.io.File

class BuildIncrementalApkCommand(private val params: Params) {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val pathManager = JuggPathManager(params.sourceProjectDir, params.baseBuildJuggRootDir)
    private val dirtyFlag = File(pathManager.juggRootDir, ".dirty")
    private val logger = CmdLineLogger.init("BuildIncrementalApkCommand", pathManager.logDir, params.logLevel)
    private val contextManager = CmdLineContextManager(pathManager, coroutineScope, logger)

    fun run(): Boolean {
        try {
            logger.info("Init compile context...")
            TimeLogger.start("Init compile context")
            checkDirty()
            contextManager.init()
            val compiler = getCompiler()
            val compileTask = getCompileTask()
            TimeLogger.end("Init compile context", logger)

            val compileResult = compiler.compile(compileTask)
            if (!compileResult.isAllSuccess) {
                logger.warn("Compile failed, exit.")
                return false
            }
            logger.info("Compile success.")
            updateApk(contextManager.compileContext, compileResult)
            logger.info("Update apk success.")
            return true
        } catch (e: IncrementalException) {
            logger.warn("Compile failed", e)
            logger.warn("Compile failed, reason: ${e.message}")
            return false
        } catch (e: Throwable) {
            logger.warn("Compile failed unexpected", e)
            logger.warn("Compile got unexpected error: ${e.message}")
            return false
        } finally {
            coroutineScope.cancel()
            Disposer.dispose(contextManager.disposer)
            CmdLineLogger.release("BuildIncrementalApkCommand")
        }
    }

    private fun checkDirty() {
        if (dirtyFlag.exists()) {
            throw IncrementalException("Argument 'baseBuildJuggRootDir' invalid, $dirtyFlag exists, which means directory was compiled before.")
        }
        dirtyFlag.parentFile.mkdirs()
        dirtyFlag.createNewFile()
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

    private fun getCompiler(): ICompiler {
        val juggServer = JuggServer(pathManager.projectDir.name, pathManager, coroutineScope, logger)
        val customCompilerManager = CustomCompilerManager(pathManager.projectDir, pathManager.customCompilerDir, juggServer, logger)
        return JuggCompiler(contextManager.compileContext, contextManager.disposer, customCompilerManager::getCustomCompilers)
    }

    private fun getCompileTask(): CompileTask {
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
        val changedCompileFiles = contextManager.fileChangesHandler.filter(changedFiles)
        if (changedCompileFiles.size != changedFiles.size) {
            throw IncrementalException("Files check failed, not all files are compilable." +
                    "\nchangedFiles:\n${changedFiles.joinToString("\n", prefix = "    ") { it.path }}" +
                    "\ncompileFiles:\n${changedCompileFiles.joinToString("\n", prefix = "    ") { it.file.path }}"
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
        return CompileTask(compileFiles, pathManager.stagingDir, CompileStatusHolder.DEFAULT)
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
                    if (it.type == CompileOutput.Type.Dex) {
                        // put in INCREMENTAL_DATA_PATH
                        val deployItem = it.toDeployItem(deployName = INCREMENTAL_DATA_PATH + it.deployItemName + ".dex")
                        deployItems.add(deployItem)
                    } else {
                        // override
                        val deployItem = it.toDeployItem()
                        deployItems.add(deployItem)
                    }
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
            params.outputApkDir.deleteRecursively()
            params.outputApkDir.mkdirs()
            val outputApkFile = File(params.outputApkDir, apkFile.name)
            apkFile.copyTo(outputApkFile, true)
            if (!outputApkFile.exists() || outputApkFile.length() == 0L) {
                throw IncrementalException("Copy apk failed, apk file not exists: ${apkFile.absolutePath}")
            }
            logger.info("Update apk success, output: ${outputApkFile.absolutePath}")
        }
    }

    companion object {

        private const val INCREMENTAL_DATA_PATH = "assets/jugg_/"

        fun run(args: Array<String>): Boolean {
            try {
                val params = ParamsParser().parse(args)
                return BuildIncrementalApkCommand(params).run()
            } catch (e: IncrementalException) {
                CmdLineLogger.stdLogger.warn("Parse params invalid, reason: ${e.message}")
                CmdLineLogger.stdLogger.warn("Parse params invalid, exit.")
                return false
            } catch (e : Throwable) {
                CmdLineLogger.stdLogger.warn("Parse params got unexpected error:", e)
                return false
            }
        }
    }
}