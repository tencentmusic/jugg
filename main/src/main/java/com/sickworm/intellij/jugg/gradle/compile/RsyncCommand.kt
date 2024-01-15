package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions


abstract class RsyncCommand(val options: JuggGradleCompileOptions): BaseSshCommand() {

    protected val sshArguments = "-e 'ssh -p ${options.remoteSshPort}'"

}

class RsyncSyncFileCommand(
    options: JuggGradleCompileOptions,
    localProjectIftPath: String,
    remoteProjectPath: String,
) : RsyncCommand(options) {

    override val baseCommand: String = """rsync $sshArguments ${SyncFileCommand.rsyncArguments} $localProjectIftPath $remoteProjectPath"""
}

class RsyncFetchOutputCommand(
    options: JuggGradleCompileOptions,
    outputApkPath: String,
    remoteToLocalClasspathPath: String,
) : RsyncCommand(options) {

    override val baseCommand: String = """mkdir -p $remoteToLocalClasspathPath && rsync $sshArguments $outputApkPath $remoteToLocalClasspathPath"""

}

class RsyncFetchClasspathCommand(
    options: JuggGradleCompileOptions,
    private val remoteProjectPath: String,
    private val remoteToLocalClasspathPath: String,
    private val modules: List<ModuleBuildPathInfo>,
) : RsyncCommand(options) {

    private var rsyncArguments = ""

    override val baseCommand: String get() = """mkdir -p $remoteToLocalClasspathPath && rsync $sshArguments $rsyncArguments $remoteProjectPath $remoteToLocalClasspathPath"""

    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        rsyncArguments = FetchClasspathCommand.getRsyncArguments(modules, isWindows)
        return super.getCommand(isNeedSetChineseLanguage, isWindows)
    }
}