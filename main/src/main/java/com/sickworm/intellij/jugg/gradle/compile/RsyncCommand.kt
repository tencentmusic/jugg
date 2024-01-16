package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions


abstract class RsyncCommand(val options: JuggGradleCompileOptions, specificKeyPath: String?): BaseSshCommand() {

    private val keyPath = if (specificKeyPath.isNullOrEmpty()) "" else "-i $specificKeyPath"

    protected val sshArguments = "-e 'ssh -p ${options.remoteSshPort} $keyPath'"

    override fun getInput(terminalOutputLine: String): String? {
        if (terminalOutputLine.contains("Are you sure you want to continue connecting")) {
            return "yes"
        }
        return super.getInput(terminalOutputLine)
    }
}

class RsyncSyncFileCommand(
    options: JuggGradleCompileOptions,
    specificKeyPath: String?,
    localProjectIftPath: String,
    remoteProjectPath: String,
) : RsyncCommand(options, specificKeyPath) {

    override val baseCommand: String = """rsync $sshArguments ${SyncFileCommand.rsyncArguments} $localProjectIftPath $remoteProjectPath"""
}

class RsyncFetchOutputCommand(
    options: JuggGradleCompileOptions,
    specificKeyPath: String?,
    outputApkPath: String,
    remoteToLocalClasspathPath: String,
) : RsyncCommand(options, specificKeyPath) {

    override val baseCommand: String = """mkdir -p $remoteToLocalClasspathPath && rsync $sshArguments $outputApkPath $remoteToLocalClasspathPath"""

}

class RsyncFetchClasspathCommand(
    options: JuggGradleCompileOptions,
    specificKeyPath: String?,
    private val remoteProjectPath: String,
    private val remoteToLocalClasspathPath: String,
    private val modules: List<ModuleBuildPathInfo>,
) : RsyncCommand(options, specificKeyPath) {

    private var rsyncArguments = ""

    override val baseCommand: String get() = """mkdir -p $remoteToLocalClasspathPath && rsync $sshArguments $rsyncArguments $remoteProjectPath $remoteToLocalClasspathPath"""

    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        rsyncArguments = FetchClasspathCommand.getRsyncArguments(modules, isWindows)
        return super.getCommand(isNeedSetChineseLanguage, isWindows)
    }
}