package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.GradleCompileSettings
import com.sickworm.intellij.jugg.project.JuggException
import java.io.File
import kotlin.jvm.Throws

data class GradleCompileResult(
    val isSuccess: Boolean,
    val isCanceled: Boolean,
    val compileOutputFile: File,
) {
    companion object {
        fun failed(isCanceled: Boolean) = GradleCompileResult(
            isSuccess = false,
            isCanceled = isCanceled,
            compileOutputFile = File(""),
        )

        fun success(outputDir: File) = GradleCompileResult(
            isSuccess = true,
            isCanceled = false,
            compileOutputFile = outputDir,
        )
    }
}

interface IGradleCompileClient : Disposable {

    var terminalOutputListener: TerminalOutputListener

    @Throws(JuggException::class)
    fun login(gradleCompileSettings: GradleCompileSettings)

    fun compileAndFetchResult() : GradleCompileResult

    fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): Boolean

    fun cancelAction()

    interface TerminalOutputListener {
        fun onOutput(line: String)
        fun onOutputErr(line: String)

        companion object {
            val DEFAULT: TerminalOutputListener = object : TerminalOutputListener {
                override fun onOutput(line: String) {
                    println(line)
                }
                override fun onOutputErr(line: String) {
                    System.err.println(line)
                }
            }
        }
    }

    object Error {
        const val SUCCESS = 0
        const val ERROR_NO_RESULT = -1000
        const val ERROR_FAILED = -1001
        const val ERROR_CANCELED = -1002
        const val RESULT_CHANNEL_CLOSED = -1003
    }
}