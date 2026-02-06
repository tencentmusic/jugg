package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.context
import java.io.File
import java.lang.IllegalStateException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("MemberVisibilityCanBePrivate")
object CompileHelper {

    val outputDir = File(buildDir, "output")
    val javaOutputDir = File(outputDir, "java")
    val dexOutputDir = File(outputDir, "classes")
    val layoutOutputDir = File(outputDir, "overlays")
    val xmlOutputDir = File(outputDir, "res")

    fun makeTask(vararg files: File): CompileTask {
        return CompileTask(
            files.map {
                CompileFile(
                    CompileFile.Type.Resource,
                    it,
                    it.parentFile.parentFile,
                    context.modules.values.first()
                )
            },
            outputDir,
            CompileStatusHolder.DEFAULT,
        )
    }

    fun checkOutputFiles(compileResult: CompileResult, expect: List<String>, isAllMatch: Boolean = false) {
        try {
            doCheckOutputFiles(compileResult, expect, isAllMatch)
        } catch (e: Throwable) {
            val allOutput = compileResult.outputs.joinToString("\n") { it.file.absolutePath }
            System.err.println("check failed, all output files:\n$allOutput")
            throw e
        }
    }

    fun doCheckOutputFiles(compileResult: CompileResult, expect: List<String>, isAllMatch: Boolean = false) {
        expect.forEach { expectFilePath ->
            val extension = File(expectFilePath).extension
            val outputDir = when (extension) {
                "java" -> javaOutputDir
                "dex" -> dexOutputDir
                "xml", "arsc" -> layoutOutputDir
                else -> throw IllegalStateException("not support type")
            }
            val outputType = when (extension) {
                "java" -> CompileOutput.Type.Java
                "dex" -> CompileOutput.Type.Dex
                "xml", "arsc" -> CompileOutput.Type.Res
                else -> throw IllegalStateException("not support type")
            }
            val expectFile = File(outputDir, expectFilePath)
            val outputFile = compileResult.outputs.find { it.file == expectFile }
            assertTrue(outputFile != null, "File $expectFile does not exist in output")
            assertTrue(outputFile.file.exists(), "File $expectFile does not exist")
            assertEquals(outputDir, outputFile.baseDir, "File $expectFile is not in correct baseDir")
            assertEquals(outputType, outputFile.type, "File $expectFile has incorrect type")
        }
        if (isAllMatch) {
            assertEquals(expect.size, compileResult.outputs.size, "Expect ${expect.size} files, but got ${compileResult.outputs.size}")
        }
    }

}