package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.gradle.compile.SyncFileCommand.Companion.getRsyncArguments
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.project.JuggPathManager


abstract class RsyncCommand(val options: JuggGradleCompileOptions, keyPathList: List<String>): BaseSshCommand() {

    private val keyPathArguments = if (keyPathList.isEmpty()) "" else keyPathList.joinToString { "-i $it" }

    protected val sshArguments = "-e 'ssh -p ${options.remoteSshPort} $keyPathArguments'"

    override fun getInput(terminalOutputLine: String): String? {
        if (terminalOutputLine.contains("Are you sure you want to continue connecting")) {
            return "yes"
        }
        return super.getInput(terminalOutputLine)
    }
}

class RsyncSyncFileCommand(
    options: JuggGradleCompileOptions,
    keyPathList: List<String>,
    localProjectIftPath: String,
    remoteProjectPath: String,
    remoteProjectSyncRelativePath: String,
) : RsyncCommand(options, keyPathList) {

    private val rsyncArguments = getRsyncArguments(remoteProjectSyncRelativePath)
    override val baseCommand: String = """rsync $sshArguments $rsyncArguments $localProjectIftPath $remoteProjectPath"""
}

class RsyncFetchOutputCommand(
    options: JuggGradleCompileOptions,
    keyPathList: List<String>,
    outputApkPath: String,
    remoteToLocalClasspathPath: String,
) : RsyncCommand(options, keyPathList) {

    override val baseCommand: String = """mkdir -p $remoteToLocalClasspathPath && rsync $sshArguments $outputApkPath $remoteToLocalClasspathPath"""

}

open class RsyncFetchClasspathCommand(
    options: JuggGradleCompileOptions,
    keyPathList: List<String>,
    private val remoteProjectPath: String,
    private val remoteToLocalClasspathPath: String,
    private val modules: List<ModuleBuildPathInfo>,
    private val additionalFetchPath: List<String> = emptyList(),
    private val isNeedDeleteArg: Boolean = true,
) : RsyncCommand(options, keyPathList) {

    private var rsyncArguments = ""

    override val baseCommand: String get() = """mkdir -p $remoteToLocalClasspathPath && rsync $sshArguments $rsyncArguments $remoteProjectPath $remoteToLocalClasspathPath"""

    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        rsyncArguments = FetchClasspathCommand.getRsyncArguments(modules, isWindows, additionalFetchPath, isNeedDeleteArg)
        return super.getCommand(isNeedSetChineseLanguage, isWindows)
    }
}

class RsyncFetchChangedLibraryCommand(
    options: JuggGradleCompileOptions,
    keyPathList: List<String>,
    remoteProjectPath: String,
    remoteToLocalClasspathPath: String,
): RsyncFetchClasspathCommand(
    options,
    keyPathList,
    remoteProjectPath,
    remoteToLocalClasspathPath,
    emptyList(),
    listOf(JuggPathManager.RSYNC_FETCH_DIFF_DIR_ARGUMENTS),
    isNeedDeleteArg = false,
)