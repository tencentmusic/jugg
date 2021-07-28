package com.sickworm.intellij.aidp.aapt2

import com.sickworm.intellij.aidp.isWindows
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

class Aapt2Invoker(val androidBuildTools: File) {

    fun invoke(params: String): Result {
        val aapt2Name = if (isWindows) "aapt2.exe" else "aapt2"
        val aapt2Cmd = "${androidBuildTools.absolutePath}/$aapt2Name"
        val command = "$aapt2Cmd $params"
        println(command)
        val process = Runtime.getRuntime().exec(command)
        val output = readOutput(process.inputStream)
        val errorOutput = readOutput(process.errorStream)
        process.waitFor()
        return Result(output, errorOutput)
    }

    private fun readOutput(stream: InputStream): String {
        val ins = BufferedReader(InputStreamReader(stream))
        val stringBuilder = StringBuilder()
        while (true) {
            val line = ins.readLine() ?: break
            stringBuilder.append(line)
            stringBuilder.append('\n')
        }
        ins.close()
        return stringBuilder.toString()
    }

    class Result(
        val output: String,
        val errorOutput: String,
    ) {
        val isSuccess: Boolean get() = errorOutput.isEmpty()
    }
}