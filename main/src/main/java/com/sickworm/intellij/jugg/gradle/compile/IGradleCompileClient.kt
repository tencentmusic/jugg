package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import java.io.File
import kotlin.jvm.Throws

/**
 * GradleCompileResult represents one Gradle compile attempt result, including success/cancel state, output APKs, and failure reason.
 * Data Contract: [failed] uses [compileOutputFile] = [File("")] as a placeholder value; callers must branch by [isSuccess] before consuming output paths.
 */
data class GradleCompileResult(
    val isSuccess: Boolean,
    val isCanceled: Boolean,
    val compileOutputFile: List<File>,
    val failedReason: String? = null,
) {
    companion object {
        fun failed(isCanceled: Boolean, failedReason: String) = GradleCompileResult(
            isSuccess = false,
            isCanceled = isCanceled,
            compileOutputFile = listOf(File("")),
            failedReason = failedReason,
        )

        fun success(outputFile: List<File>) = GradleCompileResult(
            isSuccess = true,
            isCanceled = false,
            compileOutputFile = outputFile,
        )
    }
}

/**
 * IGradleCompileClient defines a unified local/remote Gradle compile contract, including login, compile, output fetching, classpath fetching, and cancellation.
 * Data Contract: [login] must complete before compile/fetch calls; [cancelAction] should be safe to invoke at any time.
 */
interface IGradleCompileClient : Disposable {

    var terminalOutputListener: TerminalOutputListener

    @Throws(JuggException::class)
    fun login(juggGradleCompileOptions: JuggGradleCompileOptions)

    fun compileAndFetchResult(isOnlyFetchResult: Boolean = false) : GradleCompileResult

    /**
     * @return root of classpath relative to project root directory
     */
    fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): File?

    fun fetchLibraryChanges(incDeployTimes: Int): DependencyDiffResultSet?

    fun cancelAction(isByUser: Boolean)

    /**
     * TerminalOutputListener abstracts stdout/stderr/progress callbacks during Gradle command execution.
     */
    interface TerminalOutputListener {

        val possibleErrorLog: List<String> get() = emptyList()

        fun onOutput(line: String, isNeedPrint: Boolean = true)
        fun onOutputErr(line: String)
        fun updateIndicatorWithTime(newText: String? = null) = Unit

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

    /**
     * Error centralizes normalized result/error codes returned by Gradle compile clients.
     */
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
