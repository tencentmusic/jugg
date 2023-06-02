package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import java.io.File

abstract class IftSyncCommand : BaseSshCommand() {

    /**
     * Store the real result of command due to iFt won't give correct exit code.
     * Command will listen to the output. e.g.:
     * when run success -> will get "task done"
     * when run failed -> will get something like: "set device failed: no device online"
     */
    private var iftResult: Int = IGradleCompileClient.Error.ERROR_NO_RESULT

    override fun getInput(terminalOutputLine: String): String? {
        if (terminalOutputLine.contains("task done")) {
            if (iftResult == IGradleCompileClient.Error.ERROR_NO_RESULT) {
                iftResult = IGradleCompileClient.Error.SUCCESS
            }
        }
        if (terminalOutputLine.contains("run rsync failed:") || terminalOutputLine.contains("set device failed:")) {
            iftResult = IGradleCompileClient.Error.ERROR_FAILED
        }
        if (terminalOutputLine == "Login With User:") {
            return "1"
        }
        return null
    }

    override fun hasFinishWithResult(terminalOutputLine: String): Int? {
        if (super.hasFinishWithResult(terminalOutputLine) != null) {
            // reach end, return correct exit code
            return iftResult
        }
        if (terminalOutputLine.contains("rsync error:")) {
            // canceled by user, in this case super.hasFinishWithResult will never be non-null (no idea why)
            iftResult = IGradleCompileClient.Error.ERROR_CANCELED
            return iftResult
        }
        return null
    }

}

class SyncFileCommand(
    localProjectIftPath: String,
    remoteProjectPath: String,
) : IftSyncCommand() {

    override val baseCommand: String = """ft sync -s $localProjectIftPath --get $remoteProjectPath -a "-av --delete  --exclude='build/' --exclude='imagebus/log/' --exclude='imagebus/mapping/' --exclude='local.properties' --exclude='.gradle/' --exclude='.idea/'  --exclude='buildSrc/.gradle/' --exclude='*.iml' --exclude='.git/objects/'" """

}

class CompileProjectCommand(
    compileCommand: String,
    projectPath: String,
) : BaseSshCommand() {

    override val baseCommand: String = """cd $projectPath && $compileCommand --console=plain"""
}

class FetchOutputCommand(
    outputApkName: String,
    remoteToLocalClasspathPath: String,
) : IftSyncCommand() {

    override val baseCommand: String = """\
find_apk=${'$'}(find -name "$outputApkName" -print -quit) && \
ft sync -s $remoteToLocalClasspathPath/ --put ${'$'}find_apk \
"""

}

class FetchClasspathCommand(
    remoteProjectPath: String,
    remoteToLocalClasspathPath: String,
    modules: List<ModuleBuildPathInfo>,
) : IftSyncCommand() {

    private val includeClasspathFilter = modules
        .flatMap { it.allClassPathRelative }
        .toSet()
        .joinToString(" ") {
            if (it.extension.isNotEmpty()) "--include='$it'"
            else "--include='$it/**'"
        }

    override val baseCommand: String = """\
        |ft sync -s $remoteToLocalClasspathPath --put $remoteProjectPath -a \
        |"-av --delete --prune-empty-dirs --include='*/' \
        |$includeClasspathFilter \
        |--exclude='*'" \
        |"""
        .trimMargin()

}

