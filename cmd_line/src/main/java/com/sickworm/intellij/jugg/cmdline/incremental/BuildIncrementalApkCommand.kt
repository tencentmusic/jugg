package com.sickworm.intellij.jugg.cmdline.incremental

import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.cmdline.logger.CmdLineLogger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.ui.TooManyChangesConfirmResult
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.change.ChangedFile
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.io.File

class BuildIncrementalApkCommand(private val params: Params) {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val pathManager = JuggPathManager(params.sourceProjectDir, params.baseBuildJuggRootDir)
    private val dirtyFlag = File(pathManager.juggRootDir, ".dirty")
    private val logger = CmdLineLogger.init("BuildIncrementalApkCommand", pathManager.logDir, params.logLevel)

    init {
        CmdLineLogger.linkLegacyLogDir("BuildIncrementalApkCommand", pathManager.legacyLogDir)
    }

    private val contextManager = CmdLineContextManager(pathManager, coroutineScope, logger)

    fun run(): Boolean {
        try {
            logger.info("Init compile context...")
            logger.debug("BuildIncrementalApkCommand params: $params")
            TimeLogger.start("Init compile context")
            checkDirty(consume = false)
            if (params.changedFiles.isEmpty()) {
                exportBaseApks()
                logger.info("No changed files, exported baseline APKs.")
                return true
            }
            contextManager.init()
            contextManager.registerConstRefSourceDirs()
            val changedFiles = getChangedFiles()
            TimeLogger.end("Init compile context", logger)
            checkDirty(consume = true)
            contextManager.deployFileManager.addChangedFile(changedFiles)
            val compilerHelper = getCompilerHelper()

            val compileResult = compile(compilerHelper, changedFiles)
            if (!compileResult.isSuccess) {
                logger.warn("Compile failed, exit.")
                return false
            }
            logger.info("Compile success.")
            updateApk(contextManager.compileContext, compileResult.incrementalCompileResult!!)
            logger.info("Update apk success.")
            if (params.skipSign) {
                logger.info("APK signing skipped; re-sign and verify before use: ${params.outputApkDir.absolutePath}")
            }
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

    private fun checkDirty(consume: Boolean) {
        if (dirtyFlag.exists()) {
            throw IncrementalException("Argument 'baseBuildJuggRootDir' invalid, $dirtyFlag exists, which means directory was compiled before.")
        }
        if (consume) {
            dirtyFlag.parentFile.mkdirs()
            dirtyFlag.createNewFile()
        }
    }

    private fun exportBaseApks() {
        val baseline = CompileContextDb(pathManager.juggRootDir, pathManager.compileContextDbDir, logger)
            .getCompileBuildPathInfoFromDb()
            ?: throw IncrementalException("Base build APK metadata is unavailable.")
        copyApks(baseline.apkInfos)
    }

    private fun getCompilerHelper(): IncrementalCompilerHelper {
        val juggCompiler = JuggCompiler(contextManager.compileContext, contextManager.disposer)
        contextManager.customCompilerManager.setCustomCompilerJars(params.customCompilerJars)
        contextManager.customCompilerManager.init(contextManager.compileContext)

        return IncrementalCompilerHelper(
            juggCompiler,
            pathManager,
            contextManager.deployStateManager,
            contextManager.deployFileManager,
            contextManager.fileChangesHandler,
            contextManager.dependencyMissingResolver,
            logger
        )
    }

    private fun getChangedFiles(): List<ChangedFile> {
        val changedFiles = params.changedFiles
        val roots = (listOf(params.sourceProjectDir) + contextManager.compileContext.modules.values.map {
            it.moduleRootDir
        }).map { it.canonicalFile.toPath() }
        changedFiles.forEach {
            if (!it.exists()) {
                throw IncrementalException("Argument 'changedFiles' file not exists: ${it.path}")
            }
            if (roots.none { root -> it.canonicalFile.toPath().startsWith(root) }) {
                throw IncrementalException("Argument 'changedFiles' file is outside project modules: ${it.path}, 'sourceProjectDir': ${params.sourceProjectDir}")
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

        return changedCompileFiles
    }

    private fun updateApk(context: ICompileContext, compileResult: CompileResult) {
        val (isSuccess, failedReason) = IncrementalDeployHelper(context, logger)
            .updateApk(context.apkInfos, compileResult.outputs.map { it.toDeployItem() }, skipSign = params.skipSign)
        if (!isSuccess) {
            throw IncrementalException("Update apk failed, reason: $failedReason")
        }
        copyApks(context.apkInfos)
    }

    private fun copyApks(apkInfos: List<ApkInfo>) {
        params.outputApkDir.mkdirs()
        apkInfos.forEach { apkInfo ->
            apkInfo.files.forEach { apkFileUnit ->
                val apkFile = apkFileUnit.apkFile
                val outputApkFile = File(params.outputApkDir, apkFile.name)
                apkFile.copyTo(outputApkFile, true)
                if (!outputApkFile.exists() || outputApkFile.length() == 0L) {
                    throw IncrementalException("Copy apk failed, apk file not exists: ${apkFile.absolutePath}")
                }
            }
        }
    }

    private fun compile(compilerHelper: IncrementalCompilerHelper, changedFiles: List<ChangedFile>): CompileTaskResult {
        // CI merges the DEX output itself and accepts large change sets without changing user settings.
        val uiHandler = object : CompileUiHandler by CompileUiHandler.DEFAULT {
            override fun confirmTooManyChanges(info: TooManyChangesInfo) = TooManyChangesConfirmResult.CONTINUE
        }
        val compileTaskResult = compilerHelper.compile(changedFiles, uiHandler, uiHandler.createCompileStatusHolder())
        if (!compileTaskResult.isSuccess) {
            return compileTaskResult // return directly
        }

        // The last compile result only contains outputs from its own round.
        val stagedResult = compileTaskResult.incrementalCompileResult!!.copy(
            outputs = contextManager.deployFileManager.getStagingFiles(),
        )
        val dexOutputDir = File(pathManager.stagingDir, "merged_dex")
        val mergedIncrementalCompileResult = compilerHelper.mergeDex(stagedResult, dexOutputDir)
            ?: return CompileTaskResult.incrementalFailed(isCanFallback = false, failedReason = "Merge dex failed")
        return CompileTaskResult.incrementalSuccess(mergedIncrementalCompileResult)
    }

    companion object {

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
