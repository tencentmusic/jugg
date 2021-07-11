package com.sickworm.intellij.aidp.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.*
import java.io.File
import java.util.zip.ZipFile

class AssetCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Overlay)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        // just copy
        val outputs = mutableListOf<CompileOutput>()
        val details = mutableListOf<Result<CompileFile, CompileError>>()
        task.files.forEach {
            if (!it.file.exists()) {
                val errorMessage = "${it.file.absolutePath} not exists"
                val result = CompileError(it, listOf(0L to errorMessage))
                details.add(Result.failure(result))
                return@forEach
            }

            val destFile = it.file.changeBaseDir(it.baseDir, task.outputDir)
            try {
                it.file.copyTo(destFile, overwrite = true)
                outputs.add(CompileOutput(destFile, task.outputDir, CompileOutput.Type.Overlay))
                details.add(Result.success(it))
            } catch (e: Exception) {
                val errorMessage = "move file ${it.file.absolutePath} to ${destFile.absolutePath} failed, e: $e"
                logger.warn(errorMessage)
                val result = CompileError(it, listOf(0L to errorMessage))
                details.add(Result.failure(result))
            }
        }
        return CompileResult(task, details, outputs)
    }
}

class ResCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Res)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        if (!task.outputDir.exists()) {
            task.outputDir.mkdirs()
        }

        val outputDir = task.outputDir.absolutePath
        val filesString = task.files.map {
            it.file.absolutePath
        }.joinToString(" ")

        val aapt2Cmd = "D:\\Android\\sdk\\build-tools\\30.0.3\\aapt2.exe"
        val command = "$aapt2Cmd compile -o $outputDir $filesString"
        println(command)
        val process = Runtime.getRuntime().exec(command)
        process.readOutput(logger)
        process.waitFor()

        val detailsAndOutputs = task.files.map {
            val folderName = it.file.parentFile!!.name
            val extension = if (folderName.startsWith("values")) "arsc"
            else it.file.extension
            val fileName = "${folderName}_${it.file.nameWithoutExtension}.$extension.flat"
            val outputFile = File(task.outputDir, fileName)
            val output = CompileOutput(outputFile, task.outputDir, CompileOutput.Type.Flat)
            val detail: Result<CompileFile, CompileError> =
                if (outputFile.exists() && outputFile.length() > 0) {
                    Result.success(it)
                } else {
                    Result.failure(CompileError(it, listOf(0L to "compile flat failed")))
                }

            return@map detail to output
        }

        return CompileResult(
            task,
            detailsAndOutputs.map { it.first} ,
            detailsAndOutputs.filter { it.first.isSuccess }.map { it.second }
        )
    }
}

class ArscCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.FlatDir)

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

        val apkFile = makeResApk(resJar, task.outputDir)
        resJar.delete()

        val arscFile = getArsc(apkFile, task.outputDir)
        apkFile.delete()

        if (arscFile == null) {
            return CompileResult(task, task.files.map {
                val error = CompileError(it, listOf(0L to "getArsc failed"))
                Result.failure(error)
            }, emptyList())
        }

        return CompileResult(
            task,
            task.files.map { Result.success(it) },
            listOf(CompileOutput(arscFile, task.outputDir, CompileOutput.Type.Overlay))
        )
    }

    private fun makeResApk(resJar: File, outputDir: File): File {
        val outputApk = "${outputDir.absolutePath}\\res.apk"
        // TODO task
        val manifest = File("src\\test\\assets\\android\\build\\intermediates\\merged_manifests\\debug\\AndroidManifest.xml").absolutePath
        val androidJar = "D:\\Android\\sdk\\platforms\\android-30\\android.jar"
        val aapt2Cmd = "D:\\Android\\sdk\\build-tools\\30.0.3\\aapt2.exe"
        val command = "$aapt2Cmd link -o $outputApk -I $androidJar --manifest $manifest ${resJar.absolutePath}"
        println(command)
        val process = Runtime.getRuntime().exec(command)
        process.readOutput(logger)
        process.waitFor()

        return File(outputApk)
    }

    private fun getArsc(apkFile: File, outputDir: File): File? {
        try {
            ZipFile(apkFile).use { zipFile ->
                val entry = zipFile.getEntry("resources.arsc")
                if (entry == null) {
                    logger.warn("can not found resources.arsc in apk file")
                    return null
                }
                val arscFile = File(outputDir, entry.name)
                zipFile.getInputStream(entry).use { ins ->
                    arscFile.outputStream().use { ous ->
                        ins.copyTo(ous)
                    }
                }
                return arscFile
            }
        } catch (e: Exception) {
            logger.warn("getArsc failed", e)
            return null
        }
    }
}