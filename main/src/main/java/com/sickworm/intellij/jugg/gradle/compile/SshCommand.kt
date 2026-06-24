package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.gradle.script.GradleApplicationInjector
import com.sickworm.intellij.jugg.gradle.script.GradleProjectInfoReaderManager
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File
import kotlin.math.max

/**
 * Base command for `ft sync` flows with iFT-specific prompt handling and result normalization.
 */
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

/**
 * Creates a remote directory before upload/download commands.
 */
class MkDirCommand(
    path: String,
) : BaseSshCommand() {

    override val baseCommand: String = """mkdir -p $path"""

}

/**
 * Pushes project files to the remote workspace with rsync include/exclude rules.
 */
class SyncFileCommand(
    localProjectIftPath: String,
    remoteProjectPath: String,
    remoteProjectSyncRelativePath: String,
    excludePatterns: List<String> = emptyList(),
) : IftSyncCommand() {

    private val rsyncArguments = getRsyncArguments(remoteProjectSyncRelativePath, excludePatterns)
    override val baseCommand: String = """ft sync -s $localProjectIftPath --get $remoteProjectPath -a "$rsyncArguments" """

    companion object {

        fun getRsyncArguments(projectRelativePath: String, excludePatterns: List<String> = emptyList()): String {
            var buildDirPath = "/$projectRelativePath/build"
            if (buildDirPath.startsWith("//")) {
                buildDirPath = buildDirPath.substring(1)
            }
            var dotGradlePath = "/$projectRelativePath/.gradle"
            if (dotGradlePath.startsWith("//")) {
                dotGradlePath = dotGradlePath.substring(1)
            }

            val configDirArguments = JuggPathManager.RSYNC_PUSH_CONFIG_DIR_ARGUMENTS
                .replace("--include='/.gradle", "--include='$dotGradlePath")
                .replace("--exclude='/.gradle", "--exclude='$dotGradlePath")
                .replace("--include='/build", "--include='$buildDirPath")
                .replace("--exclude='/build", "--exclude='$buildDirPath")
            val userExcludeArguments = buildExcludeArguments(projectRelativePath, excludePatterns)
            return "-av --delete $configDirArguments $userExcludeArguments --exclude='build/' --exclude='local.properties' --exclude='.idea/' --exclude='*.iml' --exclude='.git/objects/' --exclude='.git/modules/' --exclude='.cxx/'"
        }

        private fun buildExcludeArguments(projectRelativePath: String, excludePatterns: List<String>): String {
            val normalizedProjectPath = projectRelativePath.replace('\\', '/').trim('/')
            val prefix = if (normalizedProjectPath.isEmpty()) "/" else "/$normalizedProjectPath/"
            return excludePatterns.joinToString(" ") { pattern ->
                val normalizedPattern = pattern.replace('\\', '/').trimStart('/')
                "--exclude='$prefix$normalizedPattern'"
            }
        }
    }
}

/**
 * Runs remote Gradle compile command and injects Jugg-specific init/config parameters.
 */
open class CompileProjectCommand(
    private val compileCommand: String,
    private val projectPath: String,
    private val initGradleFileRelativePath: String,
    private val localProjectPath: String = projectPath,
    private val logger: Logger? = null,
    private val buildTarget: BuildTarget = BuildTarget.APP,
    private val libraryTestApkGradleTasks: List<String> = emptyList(),
) : BaseSshCommand() {

    val isNormalGradleCommand: Boolean = isNormalGradleCommand(compileCommand)

    private val injectParam = if (JuggSettings.isEnableInjectGradleCompile) {
        // -Pjugg.projectDir passes the IDE project dir so the Gradle script writes to the correct
        // location when the Gradle root dir differs from the IDE project dir (e.g. android/ subdir).
        // Quoted to handle paths with spaces.
        "-I ${initGradleFileRelativePath.replace("\\", "/")} " +
        "-P${GradleApplicationInjector.PARAM_ENABLE}=${JuggSettings.finalIsEnableCompatibleDeploymentMode} " +
        "\"-Pjugg.projectDir=$projectPath\""
    } else {
        ""
    }

    private val finalCompileCommand: String = run {
        var suffix = ""
        if (isNormalGradleCommand) {
            if (!compileCommand.contains("--console")) {
                suffix += " " + "--console=plain"
            }
            suffix += ConfigurationCacheCompatHelper.getDisableArgsIfEnabled(
                File(localProjectPath), compileCommand, logger)
            suffix += buildTarget.gradlePropertyArgument()
            suffix += libraryTestTasksGradlePropertyArgument()
            suffix += " $injectParam"
        }
        return@run "$compileCommand$suffix"
    }

    override val baseCommand: String = "cd $projectPath && $finalCompileCommand"

    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        val command = super.getCommand(isNeedSetChineseLanguage, isWindows)
        if (isWindows) {
            // some Windows version can not switch disk by cd, here we do some compatible things
            if (projectPath.matches("[A-Za-z]+:.*".toRegex())) {
                val gotoDiskCmd = projectPath.substringBefore(":") + ":"
                return """$gotoDiskCmd && $command"""
            }
        }

        return command
    }

    private fun BuildTarget.gradlePropertyArgument(): String {
        return when (this) {
            BuildTarget.APP -> ""
            BuildTarget.ANDROID_TEST -> " -Pjugg.buildTarget=ANDROID_TEST"
        }
    }

    private fun libraryTestTasksGradlePropertyArgument(): String {
        if (buildTarget != BuildTarget.ANDROID_TEST || libraryTestApkGradleTasks.isEmpty()) {
            return ""
        }
        val tasks = libraryTestApkGradleTasks.joinToString(";")
        return " ${quoteGradleProperty(GradleProjectInfoReaderManager.PARAM_LIBRARY_TEST_TASKS, tasks)}"
    }

    private fun quoteGradleProperty(name: String, value: String): String {
        val escapedValue = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
        return "\"-P$name=$escapedValue\""
    }

    companion object {

        fun isNormalGradleCommand(compileCommand: String): Boolean {
            return compileCommand.matches(Regex(""".*(gradle|gradlew|gradle\.bat|gradlew\.bat)\s+[\w-_ :=.]+"""))
        }
    }
}

/**
 * Locates one APK output path on the remote machine and captures it from terminal output.
 */
class FindOutputCommand(
    remoteProjectPath: String,
    outputApkNameOrPath: String
) : BaseSshCommand() {

    var apkPath: String? = null
        private set

    val findPath = run {
        val unixIndex = outputApkNameOrPath.lastIndexOf('/')
        val windowsIndex = outputApkNameOrPath.lastIndexOf('\\')
        val index = max(unixIndex, windowsIndex)
        if (index == -1) {
            ""
        } else {
            outputApkNameOrPath.substring(0, index + 1)
        }
    }

    val findName = outputApkNameOrPath.substring(findPath.length)

    override val baseCommand: String = "cd $remoteProjectPath && find_apk=${'$'}(find $findPath -name \"$findName\" -print -quit) && echo \"\n$APK_ECHO${'$'}find_apk\n\""

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

/**
 * Downloads one APK artifact from remote workspace to local output directory.
 */
class FetchOutputCommand(
    outputApkPath: String,
    remoteToLocalClasspathPath: String,
) : IftSyncCommand() {

    override val baseCommand: String = """ft sync -s $remoteToLocalClasspathPath/ --put $outputApkPath"""

}

/**
 * Downloads selected build outputs (classpath/resources/manifests) from remote workspace.
 */
open class FetchClasspathCommand(
    private val remoteProjectPath: String,
    private val remoteToLocalClasspathPath: String,
    private val modules: List<ModuleBuildPathInfo>,
    private val additionalFetchPath: List<String> = emptyList(),
    private val isNeedDeleteArg: Boolean = true
) : IftSyncCommand() {

    private var rsyncArguments = ""

    override val baseCommand: String get() = """ft sync -s $remoteToLocalClasspathPath --put $remoteProjectPath -a "$rsyncArguments" """

    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        rsyncArguments = getRsyncArguments(modules, isWindows, additionalFetchPath, isNeedDeleteArg)
        return super.getCommand(isNeedSetChineseLanguage, isWindows)
    }

    companion object {

        fun getRsyncArguments(modules: List<ModuleBuildPathInfo>, isWindows: Boolean, additionalPath: List<String> = emptyList(), isNeedDeleteArg: Boolean = true): String {
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
            val deleteParam = if (isNeedDeleteArg) "--delete --delete-excluded" else ""
            return "-av $deleteParam --prune-empty-dirs --include='*/' ${additionalPath.joinToString(" ")} $includeClasspathFilter --exclude='*'"
        }
    }
}


/**
 * Syncs build-output directories between local roots using rsync-compatible include filters.
 */
class SyncLocalClasspathCommand(
    private val sourcePath: File,
    private val destPath: File,
    private val modules: List<ModuleBuildPathInfo>,
    private val isEnableLog: Boolean = true,
) : BaseSshCommand() {

    private var includeClasspathFilter = ""

    override val baseCommand: String get() = """${RsyncCompatibleHelper.rsyncPath} ${sourcePath.absolutePath} ${destPath.absolutePath} -av --delete --delete-excluded --prune-empty-dirs --include='*/' --exclude='build/jugg/**' $includeClasspathFilter --exclude='*'"""

    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        includeClasspathFilter = modules
            .flatMap { pathInfo ->
                pathInfo.allBuildPathRelative.map {
                    var path = it.path
                    if (sourcePath.absolutePath != pathInfo.projectRootDir.absolutePath) {
                        // multiple projects sync or modules outside project root
                        val rootPath = pathInfo.moduleRootDir.relativeTo(sourcePath).parentFile?.path
                            ?.substringBefore(File.separatorChar) ?: ""
                        if (rootPath.isNotEmpty()) {
                            path = "$rootPath/**/$path"
                        }
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

    override fun isCanOutput(line: String, isError: Boolean): Boolean {
        return isEnableLog
    }
}

/**
 * Runs a dry-run Gradle command to collect dependency-diff metadata for incremental deploy.
 */
class DiffLibraryChangesCommand(
    projectPath: String,
    initGradleFileRelativePath: String,
    incDeployTimes: Int,
    localProjectPath: String = projectPath,
    buildTarget: BuildTarget,
) : CompileProjectCommand(
    "./gradlew --dry-run" +
            " -P${GradleProjectInfoReaderManager.PARAM_DIFF_MODE}=true" +
            " -P${GradleProjectInfoReaderManager.PARAM_INC_DEPLOY_TIMES}=$incDeployTimes",
    projectPath,
    initGradleFileRelativePath,
    localProjectPath = localProjectPath,
    buildTarget = buildTarget,
)

/**
 * Fetches only changed dependency artifacts from remote workspace.
 */
class FetchChangedLibraryCommand(
    remoteProjectPath: String,
    remoteToLocalClasspathPath: String,
): FetchClasspathCommand(
    remoteProjectPath,
    remoteToLocalClasspathPath,
    emptyList(),
    listOf(JuggPathManager.RSYNC_FETCH_DIFF_DIR_ARGUMENTS),
    isNeedDeleteArg = false,
)
