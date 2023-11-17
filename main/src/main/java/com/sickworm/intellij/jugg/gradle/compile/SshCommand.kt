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
        if (terminalOutputLine == "Online Devices:") {
            return "2"
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

    override fun shouldInterrupted(currentChar: Int, buffer: StringBuilder): Int? {
        if (currentChar != ':'.code) {
            return null
        }
        if (buffer.startsWith("Username:")) {
            return IGradleCompileClient.Error.ERROR_NEED_LOGIN_IFT_USER
        }
        if (buffer.startsWith("Pin+Token:")) {
            return IGradleCompileClient.Error.ERROR_NEED_LOGIN_IFT_PASSWORD
        }
        return null
    }
}

class SyncFileCommand(
    localProjectIftPath: String,
    remoteProjectPath: String,
) : IftSyncCommand() {

    override val baseCommand: String = """mkdir -p $remoteProjectPath && ft sync -s $localProjectIftPath --get $remoteProjectPath -a "-av --delete  --exclude='build/' --exclude='local.properties' --exclude='.gradle/' --exclude='.idea/'  --exclude='buildSrc/.gradle/' --exclude='*.iml' --exclude='.git/objects/'" """

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
    private val remoteProjectPath: String,
    private val remoteToLocalClasspathPath: String,
    private val modules: List<ModuleBuildPathInfo>,
) : IftSyncCommand() {

    private var includeClasspathFilter = ""

    override val baseCommand: String get() = """\
        |ft sync -s $remoteToLocalClasspathPath --put $remoteProjectPath -a \
        |"-av --delete --prune-empty-dirs --include='*/' \
        |$includeClasspathFilter \
        |--exclude='*'" \
        |"""
        .trimMargin()

    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        includeClasspathFilter = modules
            .flatMap { it.allClassPathRelative }
            .toSet()
            .map {
                val platformSeparator = File.separatorChar
                val remoteSeparator = if (isWindows) '\\' else '/'
                it.path.replace(platformSeparator, remoteSeparator) to it.extension
            }
            .joinToString(" ") { (path, extension) ->
                if (extension.isNotEmpty()) "--include='$path'"
                else "--include='$path/**'"
            }

        return super.getCommand(isNeedSetChineseLanguage, isWindows)
    }
}

