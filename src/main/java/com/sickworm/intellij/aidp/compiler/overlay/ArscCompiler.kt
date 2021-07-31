package com.sickworm.intellij.aidp.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.*
import com.sickworm.intellij.aidp.aapt2.Aapt2DaemonInvoker
import com.sickworm.intellij.aidp.compiler.*
import com.sickworm.intellij.aidp.compiler.source.JarFileMaker
import java.io.File

class ArscCompiler(
    private val stableIds: File,
    private val manifest: File,
    private val androidJar: File,
    androidBuildTools: File,
    private val logger: Logger,
): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.FlatDir)

    private val aapt2Invoker = Aapt2DaemonInvoker(androidBuildTools, logger)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        if (task.files.size != 1 || !task.files.first().file.isDirectory) {
            throw AidpInternalException.arscCompileFileNotDirectory()
        }
        val inputDir = task.files.first().file

        if (!task.outputDir.exists()) {
            task.outputDir.mkdirs()
        }
        val resJar = File(task.outputDir, "res.jar")
        JarFileMaker().jar(inputDir, resJar)

        val (apkFile, rJavaFile) = makeResApk(resJar, task.outputDir)
        resJar.delete()

        if (!apkFile.exists() || !rJavaFile.exists()) {
            return CompileResult(task, task.files.map {
                val error = CompileError(it, listOf(0L to "makeResApk failed"))
                Result.failure(error)
            }, emptyList())
        }

        return CompileResult(
            task,
            task.files.map { Result.success(it) },
            listOf(
                CompileOutput(CompileOutput.Type.Overlay, apkFile, task.outputDir),
                CompileOutput(CompileOutput.Type.Java, rJavaFile, task.outputDir),
            )
        )
    }

    private fun makeResApk(resJar: File, outputDir: File): Pair<File, File> {
        val outputApk = "${outputDir.absolutePath}/res.apk"
        val stableIdFileArg = if (stableIds.exists()) {
            "--stable-ids $stableIds"
        } else {
            ""
        }
        val newStableIdFile = File("${stableIds.absolutePath}.out")
        val emitIdArg = "--emit-ids ${newStableIdFile.absolutePath}"
        val rFileDir = File(outputDir, "rjava")
        val rFileArg = "--java $rFileDir"
        val manifestArg = "--manifest ${manifest.absolutePath}"
        val command = "link -o $outputApk -I $androidJar $stableIdFileArg $emitIdArg $rFileArg $manifestArg ${resJar.absolutePath}"
        println(command)
        // TODO check result
        aapt2Invoker.invoke(command)

        val process = Runtime.getRuntime().exec(command)
        process.readOutput(logger)
        process.waitFor()

        if (newStableIdFile.exists()) {
            stableIds.delete()
            newStableIdFile.renameTo(stableIds)
        }

        val rFiles = rFileDir.listFilesRecursively()
        if (rFiles.isEmpty()) {
            throw AidpException.compileResApkFailed()
        }

        return File(outputApk) to rFiles[0]
    }
}

const val ARSC_FILE_NAME = "resources.arsc"