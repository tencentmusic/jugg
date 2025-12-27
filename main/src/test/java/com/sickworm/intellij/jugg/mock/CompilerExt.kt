package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.compiler.CompileError
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.file
import kotlin.test.assertEquals
import kotlin.test.assertTrue

fun assertCompileResult(task: CompileTask,
                        result: CompileResult,
                        outputFileMapper: OutputFileMapper
) {
    result.printCompileErrors()

    assertEquals(task, result.task)
    assertTrue(result.isAllSuccess)
    assertEquals(task.files.size, result.details.size)

    val exceptsOutput = mutableSetOf<CompileOutput>()
    result.details.forEach { detail ->
        assertTrue(detail.isSuccess)
        assertTrue(detail.file.file.exists() && detail.file.file.length() > 0)
        val expectOutput = outputFileMapper(detail.file)
        expectOutput.forEach { relativeOutput ->
            val output = result.outputs.find { it.file.absolutePath == relativeOutput.file.absolutePath }
            assertEquals(relativeOutput, output)
            assertTrue(output!!.file.exists())
            assertTrue(output.file.length() > 0)
        }
        exceptsOutput.addAll(expectOutput)
    }

    val exceptedOutputWithoutXml = result.outputs.filter { !it.relativeFile.startsWith("res") }
    val outputWithoutXml = result.outputs.filter { !it.relativeFile.startsWith("res") }
    assertEquals(exceptedOutputWithoutXml.size, outputWithoutXml.size)
}

fun CompileResult.printCompileErrors() {
    details.forEach {
        it.printCompileError()
    }
}

fun Result<CompileFile, CompileError>.printCompileError() {
    if (isFailed) {
        println("assertCompileResult error count: ${getFailure().errors.size}")
        println("assertCompileResult error messages:\n ${getFailure().errorMessages}")
    }
}
