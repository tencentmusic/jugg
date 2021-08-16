package com.sickworm.intellij.jugg.compiler.overlay

import com.sickworm.intellij.jugg.*
import com.sickworm.intellij.jugg.aapt2.Aapt2DaemonInvoker
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.listFilesRecursively
import java.io.File

class ArscCompiler(
    context: ICompileContext
): BaseCompiler(context) {

    override val supportedTypes = listOf(CompileFile.Type.Flat)

    override val isNeedOutputDirEmpty = true

    private val aapt2Invoker = Aapt2DaemonInvoker(logger)

    private var hasLoaded = false

    private val canCompile: Boolean get() {
        val apks = context.apks
        return apks.size == 1 && apks.first().file.exists() && context.androidJar.exists()
    }

    override fun checkContextCanCompile(task: CompileTask) {
        if (!canCompile) {
            throw AidpInternalException.contextInvalidToCompileArsc()
        }
        if (!hasLoaded) {
            loadTable()
        }
    }

    override fun onContextUpdate() {
        // TODO handle reload
        if (hasLoaded) {
            return
        }
        loadTable()
    }

    private fun loadTable(): Boolean {
        if (!canCompile) {
            return false
        }

        logger.debug("onContextUpdate load res start")
        val command = """
            |inclink
            |--load
            |-o no_need_output_path_on_load
            |-I ${context.androidJar}
            |--manifest no_need_manifest_on_load
            |${context.apkFile}
        """.trimMargin().replace("\n", " ")

        val result = aapt2Invoker.invoke(command)
        if (!result.isSuccess) {
            logger.warn("aapt2 load failed, error msg: ${result.errorOutput}")
            return false
        }
        logger.debug("onContextUpdate load res end")
        hasLoaded = true
        return true
    }

    override fun doCompile(task: CompileTask): CompileResult {
        val flatFiles = task.files.map { it.file }
        val result = incLinkCompile(flatFiles, task.outputDir)

        if (result.isEmpty()) {
            return CompileResult(task, task.files.map {
                val error = CompileError(it, listOf(0L to "makeResApk failed"))
                com.sickworm.intellij.jugg.compiler.Result.failure(error)
            }, emptyList())
        }

        return CompileResult(
            task,
            task.files.map { com.sickworm.intellij.jugg.compiler.Result.success(it) },
            result
        )
    }

    private fun incLinkCompile(flatFiles: List<File>, outputDir: File): List<CompileOutput> {
        val rFileDir = File(outputDir, "rjava")
        val overlayDir = File(outputDir, "overlays")
        rFileDir.mkdirs()
        overlayDir.mkdirs()

        val flatFilesArg = flatFiles.joinToString(separator = "\n") { it.absolutePath }
        val commandArg = """
            |inclink
            |-o $overlayDir
            |--output-to-dir
            |--java $rFileDir
            |--manifest no_support_manifest_yet
        """.trimMargin().replace("\n", " ")
        val command = "$commandArg $flatFilesArg"

        val result = aapt2Invoker.invoke(command)
        if (!result.isSuccess) {
            logger.warn("aapt2 invoke failed, error msg: ${result.errorOutput}")
            return emptyList()
        }

        val rFiles = rFileDir.listFilesRecursively().map {
            CompileOutput(CompileOutput.Type.Java, it, rFileDir)
        }
        val overlays = overlayDir.listFilesRecursively().map {
            CompileOutput(CompileOutput.Type.Overlay, it, overlayDir)
        }
        return rFiles + overlays
    }
}

const val ARSC_FILE_NAME = "resources.arsc"