package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.copyResource
import com.sickworm.intellij.jugg.compiler.isMac
import com.sickworm.intellij.jugg.gradle.compile.SyncFileCommand.Companion.getRsyncArguments
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File


/**
 * Base rsync command with SSH argument assembly and password-safe logging.
 */
abstract class RsyncCommand(val password: String?, remoteSshPort: Int, keyPathList: List<String>): BaseSshCommand() {

    private val keyPathArguments = if (keyPathList.isEmpty()) "" else keyPathList.joinToString(" ") { "-i $it" }

    protected val sshArguments = "-e '${getSshPathArg(password)}ssh -p $remoteSshPort -o StrictHostKeyChecking=accept-new $keyPathArguments'"

    override fun getInput(terminalOutputLine: String): String? {
        if (terminalOutputLine.contains("Are you sure you want to continue connecting")) {
            return "yes"
        }
        return super.getInput(terminalOutputLine)
    }

    override fun getPrintSafeCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        if (password == null) {
            return getCommand(isNeedSetChineseLanguage, isWindows)
        }
        val originCommand = getCommand(isNeedSetChineseLanguage, isWindows)
        return originCommand.replace(password, "******")
    }

    fun isNeedEnterPassword(): Boolean {
        return (password != null) && !isUseSshpass
    }

    companion object {

        private val isArch64 = System.getProperty("os.arch") == "aarch64"
        private val isUseSshpass = isMac && isArch64 // expect will be stuck on macOS arm chip

        fun getSshPathArg(password: String?): String {
            if (!isUseSshpass) {
                // use old way: expect
                return ""
            }

            if (password.isNullOrEmpty()) {
                return ""
            }
            if (File(password).exists() && File(password).isAbsolute) {
                // it's a ssh key
                return ""
            }
            return "${getSshPassPath()} -p $password "
        }

        private fun getSshPassPath(): String {
            return copyResource("/tools/darwin/sshpass-aarch64-15").path
        }

        fun getRsyncPath(): String {
            return copyResource("/tools/darwin/rsync-3.4.1").path
        }
    }
}

/**
 * Uploads project files to remote workspace through rsync.
 */
class RsyncSyncFileCommand(
    password: String?,
    remoteSshPort: Int,
    keyPathList: List<String>,
    localProjectIftPath: String,
    remoteProjectPath: String,
    remoteProjectSyncRelativePath: String,
) : RsyncCommand(password, remoteSshPort, keyPathList) {

    private val rsyncArguments = getRsyncArguments(remoteProjectSyncRelativePath)
    override val baseCommand: String = """${RsyncCompatibleHelper.rsyncPath} $sshArguments $rsyncArguments $localProjectIftPath $remoteProjectPath"""
}

/**
 * Downloads remote APK output files to local classpath directory.
 */
class RsyncFetchOutputCommand(
    password: String?,
    remoteSshPort: Int,
    keyPathList: List<String>,
    outputApkPath: String,
    remoteToLocalClasspathPath: String,
) : RsyncCommand(password, remoteSshPort, keyPathList) {

    override val baseCommand: String = """mkdir -p $remoteToLocalClasspathPath && ${RsyncCompatibleHelper.rsyncPath} $sshArguments $outputApkPath $remoteToLocalClasspathPath"""

}

/**
 * Downloads selected remote build outputs (classes/resources/manifests) by include filters.
 */
open class RsyncFetchClasspathCommand(
    password: String?,
    remoteSshPort: Int,
    keyPathList: List<String>,
    private val remoteProjectPath: String,
    private val remoteToLocalClasspathPath: String,
    private val modules: List<ModuleBuildPathInfo>,
    private val additionalFetchPath: List<String> = emptyList(),
    private val isNeedDeleteArg: Boolean = true,
) : RsyncCommand(password, remoteSshPort, keyPathList) {

    private var rsyncArguments = ""

    override val baseCommand: String get() = """mkdir -p $remoteToLocalClasspathPath && ${RsyncCompatibleHelper.rsyncPath} $sshArguments $rsyncArguments $remoteProjectPath $remoteToLocalClasspathPath"""

    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        rsyncArguments = FetchClasspathCommand.getRsyncArguments(modules, isWindows, additionalFetchPath, isNeedDeleteArg)
        return super.getCommand(isNeedSetChineseLanguage, isWindows)
    }
}

/**
 * Downloads only changed library artifacts from remote workspace.
 */
class RsyncFetchChangedLibraryCommand(
    password: String?,
    remoteSshPort: Int,
    keyPathList: List<String>,
    remoteProjectPath: String,
    remoteToLocalClasspathPath: String,
): RsyncFetchClasspathCommand(
    password,
    remoteSshPort,
    keyPathList,
    remoteProjectPath,
    remoteToLocalClasspathPath,
    emptyList(),
    listOf(JuggPathManager.RSYNC_FETCH_DIFF_DIR_ARGUMENTS),
    isNeedDeleteArg = false,
)
