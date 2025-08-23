package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.project.Project
import com.jcraft.jsch.*
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient.Companion.parseDiffSet
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.PrintStream

class RemoteGradleCompileClient(
    private val project: Project,
    private val isRemoteWindows: Boolean = false,
    private val logger: com.intellij.openapi.diagnostic.Logger = JuggLogger.getInstance(project, "RemoteGradleCompileClient"),
) : IGradleCompileClient {

    private var session: Session? = null
    private var channel: Channel? = null
    private var inputStream: InputStream? = null
    private var juggGradleCompileOptions: JuggGradleCompileOptions? = null

    override var terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

    private val cmdExecutor = CmdExecutor(logger, terminalOutputListener)

    private var finalPasswordOrKey: String = ""
    private var keyPathList = mutableListOf<String>()
    private var isUseKey: Boolean = false // currently no use

    override fun login(juggGradleCompileOptions: JuggGradleCompileOptions) {
        if ((this.juggGradleCompileOptions == juggGradleCompileOptions) && (session?.isConnected == true) && channel != null) {
            printToStreamInfo("${juggGradleCompileOptions.remoteSshIp} already login")
            return
        }

        dispose()

        finalPasswordOrKey = juggGradleCompileOptions.remoteSshPassword
        isUseKey = false
        keyPathList = mutableListOf()
        convertToAbsoluteKeyPathIfSpecific(finalPasswordOrKey)?.let {
            logger.debug("found key path in user input: $it")
            keyPathList.add(it)
        }
        keyPathList.addAll(searchAvailableKeys())
        keyPathList = keyPathList.distinct().toMutableList()

        // if key path is empty and password is empty, show dialog to input password
        if (keyPathList.isEmpty() && finalPasswordOrKey.isEmpty()) {
            finalPasswordOrKey = showDialogAndGetPasswordOrKey(extraTips = "and keys not found in .ssh")
        }

        // first, if finalPasswordOrKey is not empty, try without extra keyPathList
        if (finalPasswordOrKey.isNotEmpty()) {
            try {
                val keyList = mutableListOf<String>()
                convertToAbsoluteKeyPathIfSpecific(finalPasswordOrKey)?.let {
                    keyList.add(it)
                }
                doLogin(juggGradleCompileOptions, keyList, finalPasswordOrKey)
                logger.debug("login success with password")
                return
            } catch (e: Exception) {
                logger.debug("login failed with password", e)
            }
        }

        // login failed or finalPasswordOrKey is empty, try with extra keyPathList
        try {
            doLogin(juggGradleCompileOptions, keyPathList, finalPasswordOrKey)
        } catch (e: Exception) {
            if (keyPathList.isNotEmpty() && finalPasswordOrKey.isEmpty()) {
                // use ssh key failed and password is empty, show dialog to input password
                finalPasswordOrKey = showDialogAndGetPasswordOrKey(extraTips = "and login is failed")
                convertToAbsoluteKeyPathIfSpecific(finalPasswordOrKey)?.let {
                    logger.debug("found key path in user input2: $it")
                    keyPathList.add(it)
                }
                try {
                    doLogin(juggGradleCompileOptions, keyPathList, finalPasswordOrKey)
                } catch (e: Exception) {
                    // login failed
                    onLoginFailed(e)
                }
            } else {
                // login failed
                onLoginFailed(e)
            }
        }
    }

    private fun onLoginFailed(e: Exception) {
        inputStream = null
        channel = null
        session = null
        printToStreamError("RemoteClient login failed", e)
        throw JuggException.loginToRemoteFailed("Please check your login info.")
    }

    private fun showDialogAndGetPasswordOrKey(extraTips: String): String {
        return PlatformApi.showUserAndPasswordInputDialog(
            "SSH Password or Key Path",
            subTitle = "<html>You will see this because [SSH password or key path] is empty $extraTips.</html>",
            isPassword = true,
        ) ?: throw JuggException.loginToRemoteFailed("User canceled.")
    }

    private fun doLogin(juggGradleCompileOptions: JuggGradleCompileOptions, keyPathList: List<String>, password: String) {
        val jsch = JSch()
        keyPathList.filter {
            File(it).exists()
        }.forEach {
            jsch.addIdentitySafe(it)
        }
        JSch.setLogger(JschLogger())
        val session = jsch.getSession(
            juggGradleCompileOptions.remoteSshUser,
            juggGradleCompileOptions.remoteSshIp,
            juggGradleCompileOptions.remoteSshPort)
        if (juggGradleCompileOptions.httpProxyIp.isNotEmpty() &&
            juggGradleCompileOptions.httpProxyPort != 0) {
            session.setProxy(ProxyHTTP(juggGradleCompileOptions.httpProxyIp, juggGradleCompileOptions.httpProxyPort))
        }
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("Charset", "UTF-8")

        // fix some servers do not return "ssh-rsa" algorithms
        val algorithms = session.getConfig("PubkeyAcceptedAlgorithms").split(',') + "ssh-rsa"
        session.setConfig("PubkeyAcceptedAlgorithms", algorithms.joinToString(","))
        session.connect()

        val channel = session.openChannel("shell")
        this.inputStream = BufferedInputStream(channel.inputStream)
        channel.connect()

        this.session = session
        this.channel = channel
        this.juggGradleCompileOptions = juggGradleCompileOptions
        logger.debug("login success, isUseKey: $isUseKey")
    }

    private fun convertToAbsoluteKeyPathIfSpecific(passwordOrKey: String): String? {
        if (passwordOrKey.isEmpty()) {
            return null
        }
        if (!File(passwordOrKey).isAbsolute) {
            // maybe it's a key path
            val tryKeyPath = File(passwordOrKey).toHomeAbsolutePath()
            if (File(tryKeyPath).isFile) {
                return tryKeyPath
            }
        }
        return null
    }

    private fun searchAvailableKeys(): List<String> {
        val availableKeys = mutableSetOf<String>()

        val homeDir = System.getProperty("user.home")
        val sshDir = File(homeDir, ".ssh")
        if (!sshDir.exists()) {
            return emptyList()
        }
        val keyFiles = sshDir.listFiles { _, name ->
            !name.endsWith(".pub")
        }
        val ignoreKeyFiles = setOf("config", "known_hosts", "known_hosts.old")
        if (keyFiles != null && keyFiles.isNotEmpty()) {
            val keysInSshDir = keyFiles
                .filter { it.name !in ignoreKeyFiles }
                .map { it.absolutePath }
            logger.debug("found keys in .ssh dir: $keysInSshDir")
            availableKeys.addAll(keysInSshDir)
        }

        val sshConfigFile = File(sshDir, "config")
        if (sshConfigFile.exists()) {
            val sshConfigLines = sshConfigFile.readLines()
            val keysInConfig = sshConfigLines.mapNotNull { line ->
                if (line.contains("IdentityFile")) {
                    val keyPath = line.substringAfter("IdentityFile").trim()
                    return@mapNotNull File(keyPath).toHomeAbsolutePath()
                }
                return@mapNotNull null
            }
            logger.debug("found keys in .ssh/config: $keysInConfig")
            availableKeys.addAll(keysInConfig)
        }

        val filteredAvailableKeys = availableKeys.filter {
            if (!File(it).exists()) {
                logger.debug("ignore key $it, file not exists")
                return@filter false
            }
            if (!File(it).canRead()) {
                logger.debug("ignore key $it, file can't read")
                return@filter false
            }
            if (!File(it).readText().startsWith("-")) {
                logger.debug("ignore key $it, file not starts with -")
                return@filter false
            }
            return@filter true
        }

        return filteredAvailableKeys
    }

    private fun File.toHomeAbsolutePath(): String {
        if (isAbsolute) {
            return absolutePath
        }
        val homeDir = System.getProperty("user.home")
        return File(homeDir, path.replace("~/", "")).absolutePath
    }

    private fun JSch.addIdentitySafe(keyPath: String) {
        try {
            addIdentity(keyPath)
            logger.debug("addIdentity success, keyPath $keyPath")
        } catch (e: JSchException) {
            logger.debug("addIdentity failed, keyPath $keyPath is invalid. error: ${e.message}")
        }
    }

    override fun compileAndFetchResult(isOnlyFetchResult: Boolean): GradleCompileResult {
        val (channel, gradleCompileSettings) = checkLoginOnStart()

        if (gradleCompileSettings.syncMode.isRsync) {
            RsyncCompatibleHelper.init(logger)
        }
        if (!isOnlyFetchResult) {
            // 1. sync source
            val syncFileResult = syncSourceFile(channel, gradleCompileSettings)
            if (!syncFileResult.isSuccess) {
                return syncFileResult
            }

            // 2. compile
            val compileProjectCommand = CompileProjectCommand(
                gradleCompileSettings.compileCommand,
                gradleCompileSettings.remoteProjectPath,
                gradleCompileSettings.initGradleFileRelativePath,
            )
            val compileProjectResult = invoke(channel, compileProjectCommand)
            if (compileProjectResult != 0) {
                printToStreamErrorIfCanceled("Compile project failed, please check the error message.")
                return GradleCompileResult.failed(isCanceled, failedReason = "Compile project failed")
            }
        }

        // 3. find and fetch apk
        val lookingApkPaths = gradleCompileSettings.outputApkName.split(";")
        val findApks = mutableListOf<File>()
        val failedApkPaths = mutableListOf<String>()
        lookingApkPaths.forEachIndexed { index, apkPath ->
            val apkFile = findApk(index, apkPath, channel, gradleCompileSettings)
            if (apkFile != null) {
                findApks.add(apkFile)
            } else {
                failedApkPaths.add(apkPath)
            }
        }

        if (failedApkPaths.isNotEmpty()) {
            printToStreamError("Can't find apks in $failedApkPaths in ${project.basePath}, " +
                    "total: \"${gradleCompileSettings.outputApkName}\"")
            return GradleCompileResult.failed(isCanceled, "Can't find apk in $failedApkPaths")
        } else {
            logger.debug("Find apk: $findApks")
        }

        return GradleCompileResult.success(findApks)
    }

    private fun findApk(index: Int, outputApkName: String, channel: Channel, gradleCompileSettings: JuggGradleCompileOptions): File? {
        // find apk path
        val findOutputCommand = FindOutputCommand(gradleCompileSettings.remoteProjectPath, outputApkName)
        val findOutputResult = invoke(channel, findOutputCommand)
        if (findOutputResult != 0) {
            printToStreamErrorIfCanceled("Find APK failed, please check your sync client is opened.")
            return null
        }
        val apkPath = findOutputCommand.apkPath
        if (apkPath == null) {
            printToStreamErrorIfCanceled("Find APK failed, please check your apk name is correct.")
            return null
        }

        // fetch apk
        val remoteSeparator = if (isRemoteWindows) '\\' else '/'
        val fetchOutputCommand = if (gradleCompileSettings.syncMode.isRsync) {
            val absoluteApkPath = gradleCompileSettings.remoteProjectRsyncPath + remoteSeparator + "${index}_${apkPath}"
            RsyncFetchOutputCommand(
                finalPasswordOrKey,
                gradleCompileSettings.remoteSshPort,
                keyPathList,
                absoluteApkPath,
                gradleCompileSettings.remoteToLocalProjectRsyncPath,
            )
        } else {
            val absoluteApkPath = gradleCompileSettings.remoteProjectPath + remoteSeparator + apkPath
            FetchOutputCommand(
                absoluteApkPath,
                gradleCompileSettings.remoteToLocalProjectIftPath,
            )
        }
        val fetchOutputResult = invoke(channel, fetchOutputCommand)
        if (fetchOutputResult != 0) {
            printToStreamErrorIfCanceled("Fetch output from remote to local failed, please check your sync client is opened.")
            return null
        }

        val apkFileName = apkPath.lastIndexOf(remoteSeparator).let {
            if (it == -1) {
                apkPath
            } else {
                apkPath.substring(it + 1)
            }
        }
        val apkFile = File(gradleCompileSettings.remoteToLocalProjectSyncPath)
            .findFilesRecursively(apkFileName)
        if (apkFile == null) {
            printToStreamErrorIfCanceled("find apk name with pattern '${gradleCompileSettings.outputApkName}' " +
                    "in ${gradleCompileSettings.remoteToLocalProjectSyncPath} failed, " +
                    "please check your 'Remote to local sync path' in configuration is correct.")
            return null
        }

        return apkFile
    }

    private fun checkLoginOnStart(): Pair<Channel, JuggGradleCompileOptions> {
        isCanceled = false
        val channel = channel
        val gradleCompileSettings = juggGradleCompileOptions
        if (channel == null || gradleCompileSettings == null) {
            throw JuggInternalException.notLoginYet()
        }
        return channel to gradleCompileSettings
    }

    private fun syncSourceFile(channel: Channel, gradleCompileSettings: JuggGradleCompileOptions): GradleCompileResult {
        val mkDirCommand = MkDirCommand(gradleCompileSettings.remoteSyncRootPath)
        val mkDirResult = invoke(channel, mkDirCommand)
        if (mkDirResult != 0) {
            printToStreamErrorIfCanceled("Make dir failed, please check your sync client is opened.")
            return GradleCompileResult.failed(isCanceled, failedReason = "Make dir failed")
        }

        val syncFileCommand = if (gradleCompileSettings.syncMode.isRsync) {
            RsyncSyncFileCommand(
                finalPasswordOrKey,
                gradleCompileSettings.remoteSshPort,
                keyPathList,
                gradleCompileSettings.localSyncRsyncPath,
                gradleCompileSettings.remoteSyncRootRsyncPath,
                gradleCompileSettings.remoteProjectSyncRelativePath,
            )
        } else {
            SyncFileCommand(
                gradleCompileSettings.localSyncIftPath,
                gradleCompileSettings.remoteSyncRootPath,
                gradleCompileSettings.remoteProjectSyncRelativePath,
            )
        }
        val syncFileResult = invoke(channel, syncFileCommand)
        if (syncFileResult == IGradleCompileClient.Error.ERROR_NEED_LOGIN_IFT_USER ||
            syncFileResult == IGradleCompileClient.Error.ERROR_NEED_LOGIN_IFT_PASSWORD) {
            printToStreamErrorIfCanceled("[Jugg] iFt needs login but was canceled by user.")
            return GradleCompileResult.failed(isCanceled, failedReason = "iFt needs login")
        } else if (syncFileResult != 0) {
            printToStreamErrorIfCanceled("Sync file from local to remote failed, please check your sync client is opened.")
            return GradleCompileResult.failed(isCanceled, failedReason = "Sync file from local to remote failed")
        }

        return GradleCompileResult.success(listOf(File("")))
    }

    override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): File? {
        val (channel, gradleCompileSettings) = checkLoginOnStart()

        if (gradleCompileSettings.syncMode.isRsync) {
            RsyncCompatibleHelper.init(logger)
        }

        val fetchClasspathCommand = if (gradleCompileSettings.syncMode.isRsync) {
            RsyncFetchClasspathCommand(
                finalPasswordOrKey,
                gradleCompileSettings.remoteSshPort,
                keyPathList,
                gradleCompileSettings.remoteSyncRootRsyncPath,
                gradleCompileSettings.remoteToLocalRootRsyncPath,
                buildDirs,
            )
        } else {
            FetchClasspathCommand(
                gradleCompileSettings.remoteSyncRootPath,
                gradleCompileSettings.remoteToLocalRootIftPath,
                buildDirs,
            )
        }
        val fetchClasspathResult = invoke(channel, fetchClasspathCommand)
        if (fetchClasspathResult != 0) {
            printToStreamErrorIfCanceled("Fetch classpath failed, please check your sync client is opened.")
            return null
        }
        return File(gradleCompileSettings.remoteToLocalSyncClasspathPath)
    }

    override fun fetchLibraryChanges(incDeployTimes: Int): DependencyDiffResultSet? {
        val (channel, gradleCompileSettings) = checkLoginOnStart()

        // 1. sync source
        val syncFileResult = syncSourceFile(channel, gradleCompileSettings)
        if (!syncFileResult.isSuccess) {
            return null
        }

        // 2. run library diff
        val diffLibraryChangesCommand = DiffLibraryChangesCommand(
            gradleCompileSettings.remoteProjectPath,
            gradleCompileSettings.initGradleFileRelativePath,
            incDeployTimes,
        )
        val compileProjectResult = invoke(channel, diffLibraryChangesCommand)
        if (compileProjectResult != 0) {
            printToStreamErrorIfCanceled("Diff library changes failed, please check the error message.")
            return null
        }

        // 3. fetch result
        JuggPathManager(File(gradleCompileSettings.remoteToLocalSyncClasspathPath)).remoteDiffDir.deleteRecursively()

        val fetchChangedLibraryCommand = if (gradleCompileSettings.syncMode.isRsync) {
            RsyncFetchChangedLibraryCommand(
                finalPasswordOrKey,
                gradleCompileSettings.remoteSshPort,
                keyPathList,
                gradleCompileSettings.remoteSyncRootRsyncPath,
                gradleCompileSettings.remoteToLocalRootRsyncPath,
            )
        } else {
            FetchChangedLibraryCommand(
                gradleCompileSettings.remoteSyncRootPath,
                gradleCompileSettings.remoteToLocalRootIftPath,
            )
        }
        val fetchChangedLibraryResult = invoke(channel, fetchChangedLibraryCommand)
        if (fetchChangedLibraryResult != 0) {
            printToStreamErrorIfCanceled("Fetch library changes failed, please check the error message.")
            return null
        }

        val syncDirJuggPathManager = JuggPathManager(File(gradleCompileSettings.remoteToLocalSyncClasspathPath))
        return parseDiffSet(syncDirJuggPathManager, logger)
    }


    @Volatile
    private var isCanceled = false

    override fun cancelAction(isByUser: Boolean) {
        if (isByUser) {
            printToStreamInfo("[Jugg] user cancel")
        }
        val channel = channel ?: run {
            logger.debug("cancelAction but not login, exit")
            return
        }
        val commander = PrintStream(channel.outputStream, true)
        commander.print(String(byteArrayOf(0x03))) // control c
        commander.flush()
        // iFt/rsync needs control c twice
        commander.print(String(byteArrayOf(0x03))) // control c
        commander.flush()
        cmdExecutor.release()
        isCanceled = true
    }

    private fun invoke(channel: Channel, command: ISshCommand): Int {
        printToStreamInfo("[Jugg] ${command::class.simpleName} exec start")

        command.beforeInvokeCommand()
        val result = if (command is RsyncCommand) {
            // invoke at local and using expect login into ssh
            cmdExecutor.terminalOutputListener = terminalOutputListener
            val result = cmdExecutor.invoke(command)
            if (!isCanceled && result == IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED) {
                logger.warn("process exit without print result, behavior may incorrect")
                IGradleCompileClient.Error.SUCCESS
            } else {
                result
            }
        } else {
            remoteInvoke(channel, command)
        }

        printToStreamInfo("[Jugg] ${command::class.simpleName} exec finished with result: $result")
        return result
    }

    private fun remoteInvoke(channel: Channel, command: ISshCommand): Int {
        val commander = PrintStream(channel.outputStream, false)
        val commandString = command.getCommand(isNeedSetChineseLanguage = true, isWindows = false)
        logger.debug("Jsch invoke command: $commandString")
        commander.printlnCompat(commandString)
        commander.flush()

        val inputStream = inputStream ?: run {
            logger.warn("InputStream is null, current state is unexpected, exit.")
            return IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
        }
        val buffer = StringBuilder()
        val bufferedInputStream = BufferedInputStream(inputStream)
        val result: Int
        var lastInterruptCode: Int = IGradleCompileClient.Error.SUCCESS // avoid popup dialog on every chat entered
        whileRoot@while (true) {
            buffer.setLength(0)
            var line: String
            while (true) {
                val code = bufferedInputStream.read()
                if (code == '\n'.code || code == '\r'.code || code == -1) {
                    line = String(buffer.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                    break
                } else {
                    buffer.append(code.toChar())
                }
                val interruptCode = command.shouldInterrupted(code, buffer)
                if (interruptCode != null && lastInterruptCode != interruptCode) {
                    if (interruptCode == IGradleCompileClient.Error.ERROR_NEED_LOGIN_IFT_USER ||
                        interruptCode == IGradleCompileClient.Error.ERROR_NEED_LOGIN_IFT_PASSWORD) {
                        lastInterruptCode = interruptCode
                        val content = "iFt ${buffer.toString().replace(":", "")}"
                        val output = PlatformApi.showUserAndPasswordInputDialog(content,
                            isPassword = interruptCode == IGradleCompileClient.Error.ERROR_NEED_LOGIN_IFT_PASSWORD)
                        if (output == null) {
                            // user canceled
                            result = interruptCode
                            break@whileRoot
                        }
                        commander.printlnCompat(output)
                        commander.flush()
                    } else {
                        result = interruptCode
                        break@whileRoot
                    }
                }
            }
            if (line.isNotEmpty()) {
                printToStream(line)

                val output = command.getInput(line)
                if (output != null) {
                    logger.debug("output: $output")
                    commander.printlnCompat(output)
                    commander.flush()
                }
                val currentResult = command.hasFinishWithResult(line)
                if (currentResult != null) {
                    result = currentResult
                    break
                }
            }

            if (channel.isClosed) {
                printToStream("[Jugg] exit-status: " + channel.exitStatus)
                result = IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
                break
            }
        }

        return result
    }

    private fun PrintStream.printlnCompat(line: String) {
        print(line)
        if (isRemoteWindows) {
            print("\r\n")
        } else {
            print("\n")
        }
    }

    private fun printToStream(line: String) {
        terminalOutputListener.onOutput(line)
        logger.debug(line)
    }

    private fun printToStreamInfo(line: String) {
        terminalOutputListener.onOutput(line, isNeedPrint = false)
        logger.info(line)
    }

    private fun printToStreamError(line: String, e: Exception? = null) {
        terminalOutputListener.onOutput(line, isNeedPrint = false)
        logger.warn(line, e)
    }

    private fun printToStreamErrorIfCanceled(line: String, e: Exception? = null) {
        if (isCanceled) {
            return
        }
        terminalOutputListener.onOutput(line, isNeedPrint = false)
        return printToStreamError(line, e)
    }

    override fun dispose() {
        JSch.setLogger(null)
        inputStream?.close()
        channel?.disconnect()
        session?.disconnect()
        inputStream = null
        channel = null
        session = null

        cmdExecutor.release()
    }

    inner class JschLogger : Logger {

        override fun isEnabled(level: Int): Boolean {
            return true
        }

        override fun log(level: Int, message: String) {
            val levelMessage = name(level) + ": " + message
            if (level >= Logger.WARN) {
                printToStreamError(levelMessage)
            } else {
                if (message.contains("succeed") && message.contains("publickey")) {
                    isUseKey = true
                }
                printToStream(levelMessage)
            }
        }

        private fun name(level: Int): String {
            return when (level) {
                Logger.DEBUG -> "DEBUG"
                Logger.INFO -> "INFO"
                Logger.WARN -> "WARN"
                Logger.ERROR -> "ERROR"
                Logger.FATAL -> "FATAL"
                else -> "UNKNOWN"
            }
        }
    }
}
