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

class MkDirCommand(
    path: String,
) : BaseSshCommand() {

    override val baseCommand: String = """mkdir -p $path"""

}

class SyncFileCommand(
    localProjectIftPath: String,
    remoteProjectPath: String,
) : IftSyncCommand() {

    override val baseCommand: String = """ft sync -s $localProjectIftPath --get $remoteProjectPath -a "$rsyncArguments" """

    companion object {

        const val rsyncArguments = "-av --delete --exclude='build/' --exclude='local.properties' --exclude='.gradle/' --exclude='.idea/'  --exclude='buildSrc/.gradle/' --exclude='*.iml' --exclude='.git/objects/'"
    }
}

class CompileProjectCommand(
    compileCommand: String,
    projectPath: String,
) : BaseSshCommand() {

    override val baseCommand: String = """cd $projectPath && $compileCommand --console=plain"""
}

class FindOutputCommand(
    remoteProjectPath: String,
    outputApkName: String
) : BaseSshCommand() {

    var apkPath: String? = null
        private set

    override val baseCommand: String = "cd $remoteProjectPath && find_apk=${'$'}(find -name \"$outputApkName\" -print -quit) && echo \"\n$APK_ECHO${'$'}find_apk\n\""

    override fun getInput(terminalOutputLine: String): String? {
        if (terminalOutputLine.contains(APK_ECHO)) {
            apkPath = terminalOutputLine.substring(APK_ECHO.length)
        }
        return super.getInput(terminalOutputLine)
    }

    companion object {
        private const val APK_ECHO = "(Jugg) find apk result: "
    }
}

class FetchOutputCommand(
    outputApkPath: String,
    remoteToLocalClasspathPath: String,
) : IftSyncCommand() {

    override val baseCommand: String = """ft sync -s $remoteToLocalClasspathPath/ --put $outputApkPath"""

}

class FetchClasspathCommand(
    private val remoteProjectPath: String,
    private val remoteToLocalClasspathPath: String,
    private val modules: List<ModuleBuildPathInfo>,
) : IftSyncCommand() {

    private var rsyncArguments = ""

    override val baseCommand: String get() = """ft sync -s $remoteToLocalClasspathPath --put $remoteProjectPath -a "$rsyncArguments" """

    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        rsyncArguments = getRsyncArguments(modules, isWindows)
        return super.getCommand(isNeedSetChineseLanguage, isWindows)
    }

    companion object {

        fun getRsyncArguments(modules: List<ModuleBuildPathInfo>, isWindows: Boolean): String {
            val includeClasspathFilter = modules
                .flatMap { it.allBuildPathRelative }
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
            return "-av --delete --prune-empty-dirs --include='*/' $includeClasspathFilter --exclude='*'"
        }
    }
}


class SyncLocalClasspathCommand(
    private val sourcePath: File,
    private val destPath: File,
    private val modules: List<ModuleBuildPathInfo>,
) : BaseSshCommand() {

    private var includeClasspathFilter = ""

    override val baseCommand: String get() = """rsync ${sourcePath.absolutePath} ${destPath.absolutePath} -av --delete --prune-empty-dirs --include='*/' --exclude='build/jugg/**' $includeClasspathFilter --exclude='*'"""

    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        includeClasspathFilter = modules
            .flatMap { pathInfo ->
                pathInfo.allBuildPathRelative.map {
                    var path = it.path
                    val rootPath = pathInfo.moduleRootDir.relativeTo(sourcePath).parentFile?.path
                        ?.substringBefore(File.separatorChar) ?: ""
                    if (rootPath.isNotEmpty()) {
                        path = "$rootPath/**/$path"
                    }

                    val platformSeparator = File.separatorChar
                    val remoteSeparator = if (isWindows) '\\' else '/'
                    path = path.replace(platformSeparator, remoteSeparator)

                    if (it.extension.isNotEmpty()) "--include='$path'"
                    else "--include='$path/**'"
                }
            }
            .toSet()
            .joinToString(" ")

        return super.getCommand(isNeedSetChineseLanguage, isWindows)
    }
}
