package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import java.io.File
import kotlin.jvm.Throws

data class GradleCompileResult(
    val isSuccess: Boolean,
    val isCanceled: Boolean,
    val compileOutputFile: File,
    val failedReason: String? = null,
) {
    companion object {
        fun failed(isCanceled: Boolean, failedReason: String) = GradleCompileResult(
            isSuccess = false,
            isCanceled = isCanceled,
            compileOutputFile = File(""),
            failedReason = failedReason,
        )

        fun success(outputFile: File) = GradleCompileResult(
            isSuccess = true,
            isCanceled = false,
            compileOutputFile = outputFile,
        )
    }
}

interface IGradleCompileClient : Disposable {

    var terminalOutputListener: TerminalOutputListener

    @Throws(JuggException::class)
    fun login(juggGradleCompileOptions: JuggGradleCompileOptions)

    fun compileAndFetchResult(isOnlyFetchResult: Boolean = false) : GradleCompileResult

    /**
     * @return root of classpath relative to project root directory
     */
    fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): File?

    fun fetchLibraryChanges(currentBuildChecksum: String, lastBuildChecksum: String): DependencyDiffResult?

    fun cancelAction(isByUser: Boolean)

    interface TerminalOutputListener {
        fun onOutput(line: String, isNeedPrint: Boolean = true)
        fun onOutputErr(line: String)

        companion object {
            val DEFAULT: TerminalOutputListener = object : TerminalOutputListener {
                override fun onOutput(line: String, isNeedPrint: Boolean) {
                    if (isNeedPrint) {
                        println(line)
                    }
                }
                override fun onOutputErr(line: String) {
                    System.err.println(line)
                }
            }

            val IDLE : TerminalOutputListener = object : TerminalOutputListener {
                override fun onOutput(line: String, isNeedPrint: Boolean) = Unit
                override fun onOutputErr(line: String) = Unit
            }
        }
    }

    object Error {
        const val SUCCESS = 0
        const val ERROR_NO_RESULT = -1000
        const val ERROR_FAILED = -1001
        const val ERROR_CANCELED = -1002
        const val RESULT_CHANNEL_CLOSED = -1003
        const val ERROR_NEED_LOGIN_IFT_USER = -1004
        const val ERROR_NEED_LOGIN_IFT_PASSWORD = -1005
    }
}