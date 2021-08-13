package com.sickworm.intellij.aidp.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.*
import com.sickworm.intellij.aidp.aapt2.Aapt2DaemonInvoker
import com.sickworm.intellij.aidp.compiler.*
import java.io.File

class ArscCompiler(
    apkFile: File,
    androidJar: File,
    private val logger: Logger,
): ICompiler {

    override val supportedTypes = listOf(CompileFile.Type.Flat)

    private val aapt2Invoker = Aapt2DaemonInvoker(logger)

    init {
        load(apkFile, androidJar)
    }

    /**
     * load res from apk and android.jar before inc link
     *
     * @return R.java
     */
    // TODO using CompileContext to load
    fun load(apkFile: File, androidJar: File): CompileOutput? {
        logger.debug("loadRes start")

        val command = """
            |inclink
            |--load
            |-o no_need_output_path_on_load
            |-I $androidJar
            |--manifest no_need_manifest_on_load
            |$apkFile
        """.trimMargin().replace("\n", " ")

        val result = aapt2Invoker.invoke(command)
        if (!result.isSuccess) {
            logger.warn("aapt2 load failed, error msg: ${result.errorOutput}")
            return null
        }
        logger.debug("loadRes end")
        // TODO output R.java
        return null
    }

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)
        checkOutputDirIsEmpty(task)

        if (!task.outputDir.exists()) {
            task.outputDir.mkdirs()
        }

        val flatFiles = task.files.map { it.file }
        val result = incLinkCompile(flatFiles, task.outputDir)

        if (result.isEmpty()) {
            return CompileResult(task, task.files.map {
                val error = CompileError(it, listOf(0L to "makeResApk failed"))
                Result.failure(error)
            }, emptyList())
        }

        return CompileResult(
            task,
            task.files.map { Result.success(it) },
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