package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.project.Project
import com.jcraft.jsch.*
import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.BufferedInputStream
import java.io.File
import java.io.PrintStream

class RemoteGradleCompileClient(
    project: Project,
    private val isRemoteWindows: Boolean = false,
    private val logger: com.intellij.openapi.diagnostic.Logger = JuggLogger.getInstance(project, "RemoteGradleCompileClient"),
) : IGradleCompileClient {

    private var session: Session? = null
    private var channel: Channel? = null
    private var juggGradleCompileOptions: JuggGradleCompileOptions? = null

    override var terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

    private val cmdExecutor = CmdExecutor(terminalOutputListener, logger)

    private var finalPassword: String = ""
    private var isUseKey: Boolean = false // currently no use

    override fun login(juggGradleCompileOptions: JuggGradleCompileOptions) {
        if ((this.juggGradleCompileOptions == juggGradleCompileOptions) && (session?.isConnected == true) && channel != null) {
            printToStreamInfo("${juggGradleCompileOptions.remoteSshIp} already login")
            return
        }

        dispose()

        finalPassword = juggGradleCompileOptions.remoteSshPassword
        if (finalPassword.isEmpty()) {
            val enteredPassword = UserAndPasswordInputDialog.showAndGetResult(
                "SSH Password or Key Path",
                subTitle = "<html>You will see this because password is empty in run configuration.<br/>Jugg will try to use SSH key to login if keep empty.</html>",
                isPassword = true,
            ) ?: throw JuggException.loginToRemoteFailed("User canceled.")

            finalPassword = enteredPassword
        }

        isUseKey = false
        val keyPathList = mutableListOf<String>()
        convertToAbsoluteKeyPathIfSpecific(finalPassword)?.let {
            logger.debug("found key path in user input: $it")
            keyPathList.add(it)
        }
        keyPathList.addAll(searchAvailableKeys())

        try {
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
            session.setPassword(finalPassword)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("Charset", "UTF-8")
            session.connect()
            val channel = session.openChannel("shell")
            channel.connect()

            this.session = session
            this.channel = channel
            this.juggGradleCompileOptions = juggGradleCompileOptions
            logger.debug("login success, isUseKey: $isUseKey")
        } catch (e: JSchException) {
            printToStreamError("RemoteClient login failed", e)
            throw JuggException.loginToRemoteFailed("Please check your login info.")
        }
    }

    private fun convertToAbsoluteKeyPathIfSpecific(passwordOrKey: String): String? {
        if (!File(passwordOrKey).isAbsolute) {
            // maybe it's a key path
            val tryKeyPath = File(passwordOrKey).toHomeAbsolutePath()
            if (File(tryKeyPath).exists()) {
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

        return availableKeys.toList()
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
        } catch (e: JSchException) {
            logger.warn("addIdentity failed, keyPath $keyPath is invalid. error: ${e.message}")
        }
    }

    override fun compileAndFetchResult(isOnlyFetchResult: Boolean): GradleCompileResult {
        isCanceled = false
        val channel = channel
        val gradleCompileSettings = juggGradleCompileOptions
        if (channel == null || gradleCompileSettings == null) {
            throw JuggInternalException.notLoginYet()
        }

        if (!isOnlyFetchResult) {
            // 1. mkdir
            val mkDirCommand = MkDirCommand(gradleCompileSettings.remoteSyncRootPath)
            val mkDirResult = invoke(channel, mkDirCommand)
            if (mkDirResult != 0) {
                printToStreamErrorIfCanceled("Make dir failed, please check your sync client is opened.")
                return GradleCompileResult.failed(isCanceled, failedReason = "Make dir failed")
            }

            // 2. sync source file
            val syncFileCommand = if (gradleCompileSettings.syncMode.isRsync) {
                RsyncSyncFileCommand(
                    gradleCompileSettings,
                    convertToAbsoluteKeyPathIfSpecific(finalPassword),
                    gradleCompileSettings.localSyncRsyncPath,
                    gradleCompileSettings.remoteSyncRootRsyncPath,
                )
            } else {
                SyncFileCommand(
                    gradleCompileSettings.localSyncIftPath,
                    gradleCompileSettings.remoteSyncRootPath,
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

            // 3. compile
            val compileProjectCommand = CompileProjectCommand(gradleCompileSettings.compileCommand, gradleCompileSettings.remoteProjectPath)
            val compileProjectResult = invoke(channel, compileProjectCommand)
            if (compileProjectResult != 0) {
                printToStreamErrorIfCanceled("Compile project failed, please check the error message.")
                return GradleCompileResult.failed(isCanceled, failedReason = "Compile project failed")
            }
        }

        // 4. find apk
        val findOutputCommand = FindOutputCommand(gradleCompileSettings.remoteProjectPath, gradleCompileSettings.outputApkName)
        val findOutputResult = invoke(channel, findOutputCommand)
        if (findOutputResult != 0) {
            printToStreamErrorIfCanceled("Find APK failed, please check your sync client is opened.")
            return GradleCompileResult.failed(isCanceled, failedReason = "Find output failed")
        }
        val apkPath = findOutputCommand.apkPath
        if (apkPath == null) {
            printToStreamErrorIfCanceled("Find APK failed, please check your apk name is correct.")
            return GradleCompileResult.failed(isCanceled, failedReason = "Find output failed")
        }

        // fetch apk
        val remoteSeparator = if (isRemoteWindows) '\\' else '/'
        val fetchOutputCommand = if (gradleCompileSettings.syncMode.isRsync) {
            val absoluteApkPath = gradleCompileSettings.remoteProjectRsyncPath + remoteSeparator + apkPath
            RsyncFetchOutputCommand(
                gradleCompileSettings,
                convertToAbsoluteKeyPathIfSpecific(finalPassword),
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
            return GradleCompileResult.failed(isCanceled, failedReason = "Fetch output from remote to local failed")
        }

        val apkFile = File(gradleCompileSettings.remoteToLocalProjectSyncPath)
            .findFilesRecursively(gradleCompileSettings.outputApkName)
        if (apkFile == null) {
            printToStreamErrorIfCanceled("find apk name with pattern '${gradleCompileSettings.outputApkName}' " +
                    "in ${gradleCompileSettings.remoteToLocalProjectSyncPath} failed, " +
                    "please check your 'Remote to local sync path' in configuration is correct.")
            return GradleCompileResult.failed(isCanceled, failedReason = "find apk name")
        }
        return GradleCompileResult.success(apkFile)
    }

    override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): File? {
        isCanceled = false
        val channel = channel
        val gradleCompileSettings = juggGradleCompileOptions
        if (channel == null || gradleCompileSettings == null) {
            throw JuggInternalException.notLoginYet()
        }

        val fetchClasspathCommand = if (gradleCompileSettings.syncMode.isRsync) {
            RsyncFetchClasspathCommand(
                gradleCompileSettings,
                convertToAbsoluteKeyPathIfSpecific(finalPassword),
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
        cmdExecutor.release()
        isCanceled = true
    }

    private fun invoke(channel: Channel, command: ISshCommand): Int {
        printToStreamInfo("[Jugg] ${command::class.simpleName} exec start")

        command.beforeInvokeCommand()
        val result = if (command is RsyncCommand) {
            // invoke at local and using expect login into ssh
            cmdExecutor.terminalOutputListener = terminalOutputListener
            cmdExecutor.invoke(command, sshLoginPassword = finalPassword)
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

        val buffer = StringBuilder()
        val bufferedInputStream = BufferedInputStream(channel.inputStream)
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
                        val output = UserAndPasswordInputDialog.showAndGetResult(content,
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
        session?.disconnect()
        channel?.disconnect()
        cmdExecutor.release()
        session = null
        channel = null
    }

    inner class JschLogger : Logger {

        override fun isEnabled(level: Int): Boolean {
            return true
        }

        override fun log(level: Int, message: String) {
            val levelMessage = name(level) + ": " + message
            if (level >= Logger.WARN) {
                terminalOutputListener.onOutputErr(levelMessage)
            } else {
                if (message.contains("succeed") && message.contains("publickey")) {
                    isUseKey = true
                }
                terminalOutputListener.onOutput(levelMessage)
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
