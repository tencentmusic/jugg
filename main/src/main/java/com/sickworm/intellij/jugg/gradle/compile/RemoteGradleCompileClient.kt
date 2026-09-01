package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.project.Project
import com.jcraft.jsch.*
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient.Companion.parseDiffSet
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.JuggInternalException
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicLong

private val REMOTE_ENV_VALUE_LOG_ALLOWLIST = listOf(
    "JAVA_HOME",
    "ANDROID_HOME",
    "ANDROID_SDK_ROOT",
    "GRADLE_USER_HOME",
)

/** Builds a credential-safe summary without changing the environment sent to the remote shell. */
internal fun summarizeRemoteEnvironmentVariables(environmentVariables: String): String {
    val variables = environmentVariables.split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=') }
    val parts = REMOTE_ENV_VALUE_LOG_ALLOWLIST.mapNotNull { name ->
        variables[name]?.let { "$name=$it" }
    }.toMutableList()
    if (variables.containsKey("PATH")) {
        parts += "PATH=(configured)"
    }
    val otherNames = variables.keys
        .filterNot { it in REMOTE_ENV_VALUE_LOG_ALLOWLIST || it == "PATH" }
        .sorted()
    if (otherNames.isNotEmpty()) {
        parts += "otherVariables=$otherNames"
        parts += "otherCount=${otherNames.size}"
    }
    return "Remote environment: ${parts.joinToString(", ")}"
}

/**
 * RemoteGradleCompileClient manages SSH-based Gradle compilation and synchronizes build outputs/dependency changes back to local.
 * Collaboration: Establishes SSH sessions via JSch, executes remote commands through [CmdExecutor]/[SshCommand], and synchronizes artifacts with [RsyncCommand] plus local deserialization helpers in [LocalGradleCompileClient].
 * Data Contract: [login] initializes/refreshes SSH channel state before build commands; authentication falls back across password and discovered key paths; login failures reset session fields and throw [JuggException.loginToRemoteFailed].
 */
class RemoteGradleCompileClient(
    private val projectDir: File,
    private val isRemoteWindows: Boolean = false,
    private val logger: com.intellij.openapi.diagnostic.Logger,
) : IGradleCompileClient {

    constructor(
        project: Project,
        isRemoteWindows: Boolean = false,
        logger: com.intellij.openapi.diagnostic.Logger = JuggLogger.getInstance(project, "RemoteGradleCompileClient"),
    ) : this(File(project.basePath!!), isRemoteWindows, logger)

    @Volatile
    private var session: Session? = null
    @Volatile
    private var channel: Channel? = null
    private var inputStream: InputStream? = null
    private var shellInputStream: BufferedInputStream? = null
    private var juggGradleCompileOptions: JuggGradleCompileOptions? = null
    private var remoteEnvironmentPrefix: String = ""

    override var terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

    private val cmdExecutor = CmdExecutor(logger, terminalOutputListener)

    private var finalPasswordOrKey: String = ""
    private var keyPathList = mutableListOf<String>()
    private var isUseKey: Boolean = false // currently no use
    private val remoteCommandCounter = AtomicLong(0L)
    @Volatile
    private var currentRemoteCommandName: String? = null
    @Volatile
    private var currentRemoteCommandId: Long? = null
    private var cancelCtrlCAttempts = 0
    private var lastCancelCtrlCAtMs = Long.MIN_VALUE
    private var isTerminalOutputLoggingEnabled = true

    override fun login(juggGradleCompileOptions: JuggGradleCompileOptions) {
        isTerminalOutputLoggingEnabled = true
        login(juggGradleCompileOptions, resetCanceled = true, connectTimeoutMs = null)
    }

    private fun login(
        juggGradleCompileOptions: JuggGradleCompileOptions,
        resetCanceled: Boolean,
        connectTimeoutMs: Int?,
    ) {
        logger.debug("[Jugg] remote login starts with fresh shell, reuse disabled")
        dispose()
        if (resetCanceled) {
            isCanceled = false
        }
        resetCancelCtrlCState()
        currentRemoteCommandName = null
        currentRemoteCommandId = null

        finalPasswordOrKey = juggGradleCompileOptions.remoteSshPassword
        isUseKey = false
        keyPathList = mutableListOf()
        val specificKeyPath = convertToAbsoluteKeyPathIfSpecific(finalPasswordOrKey)?.also {
            logger.debug("found key path in user input: $it")
        }
        val shouldSearchKeysLazily = finalPasswordOrKey.isNotEmpty()
        if (!shouldSearchKeysLazily) {
            keyPathList.addAll(searchAvailableKeys())
            keyPathList = keyPathList.distinct().toMutableList()
        }

        // if key path is empty and password is empty, show dialog to input password
        if (keyPathList.isEmpty() && finalPasswordOrKey.isEmpty()) {
            throwIfCanceled()
            finalPasswordOrKey = showDialogAndGetPasswordOrKey(extraTips = "and keys not found in .ssh")
            throwIfCanceled()
        }

        // first, if finalPasswordOrKey is not empty, try without extra keyPathList
        if (finalPasswordOrKey.isNotEmpty()) {
            try {
                val keyList = mutableListOf<String>()
                convertToAbsoluteKeyPathIfSpecific(finalPasswordOrKey)?.let {
                    keyList.add(it)
                }
                doLogin(juggGradleCompileOptions, keyList, finalPasswordOrKey, connectTimeoutMs)
                logger.debug("login success with password")
                keyPathList = mutableListOf() // won't use keyPathList in laster rsync
                return
            } catch (e: Exception) {
                if (isCanceled) throw e
                logger.debug("login failed with password", e)
            }
        }

        // login failed or finalPasswordOrKey is empty, try with extra keyPathList
        if (shouldSearchKeysLazily) {
            specificKeyPath?.let { keyPathList.add(it) }
            keyPathList.addAll(searchAvailableKeys())
            keyPathList = keyPathList.distinct().toMutableList()
        }
        try {
            doLogin(juggGradleCompileOptions, keyPathList, finalPasswordOrKey, connectTimeoutMs)
        } catch (e: Exception) {
            if (isCanceled) throw e
            if (keyPathList.isNotEmpty() && finalPasswordOrKey.isEmpty()) {
                // use ssh key failed and password is empty, show dialog to input password
                throwIfCanceled()
                finalPasswordOrKey = showDialogAndGetPasswordOrKey(extraTips = "and login is failed")
                throwIfCanceled()
                convertToAbsoluteKeyPathIfSpecific(finalPasswordOrKey)?.let {
                    logger.debug("found key path in user input2: $it")
                    keyPathList.add(it)
                }
                try {
                    doLogin(juggGradleCompileOptions, keyPathList, finalPasswordOrKey, connectTimeoutMs)
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
        closeRemoteConnection()
        printToStreamError("RemoteClient login failed", e)
        throw JuggException.loginToRemoteFailed("Please check your login info.")
    }

    /**
     * Build export prefix prepended to each remote shell command.
     */
    private fun updateRemoteEnvironmentPrefix(environmentVariables: String) {
        remoteEnvironmentPrefix = ""
        if (environmentVariables.isEmpty()) {
            return
        }
        val envVars = environmentVariables.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
        if (envVars.isEmpty()) {
            logger.debug("No valid environment variables to set")
            return
        }
        remoteEnvironmentPrefix = envVars.joinToString(" ; ") { "export $it" } + " ; "
        logger.debug(summarizeRemoteEnvironmentVariables(environmentVariables))
    }

    private fun showDialogAndGetPasswordOrKey(extraTips: String): String {
        return PlatformApi.showUserAndPasswordInputDialog(
            "SSH Password or Key Path",
            subTitle = "<html>You will see this because [SSH password or key path] is empty $extraTips.</html>",
            isPassword = true,
        ) ?: throw JuggException.loginToRemoteFailed("User canceled.")
    }

    private fun doLogin(
        juggGradleCompileOptions: JuggGradleCompileOptions,
        keyPathList: List<String>,
        password: String,
        connectTimeoutMs: Int?,
    ) {
        throwIfCanceled()
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
            logger.debug("[Jugg] JSch session uses HTTP proxy ${juggGradleCompileOptions.httpProxyIp}:${juggGradleCompileOptions.httpProxyPort}")
        }
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("Charset", "UTF-8")

        // fix some servers do not return "ssh-rsa" algorithms
        val algorithms = session.getConfig("PubkeyAcceptedAlgorithms").split(',') + "ssh-rsa"
        session.setConfig("PubkeyAcceptedAlgorithms", algorithms.joinToString(","))
        throwIfCanceled()
        try {
            this.session = session
            if (connectTimeoutMs == null) {
                session.connect()
            } else {
                session.connect(connectTimeoutMs)
            }
            throwIfCanceled()
            this.juggGradleCompileOptions = juggGradleCompileOptions
            updateRemoteEnvironmentPrefix(juggGradleCompileOptions.environmentVariables)
            openShellChannel()
            if (!waitShellReady()) {
                throw JuggException.loginToRemoteFailed("Remote shell is not ready, terminal handshake may have failed.")
            }
            if (!disableShellEcho()) {
                throw JuggException.loginToRemoteFailed("Remote shell echo could not be disabled safely.")
            }
        } catch (e: Throwable) {
            if (this.session === session) {
                closeRemoteConnection()
            } else {
                session.disconnect()
            }
            throw e
        }
        logger.debug("login success, isUseKey: $isUseKey")
    }

    /**
     * Executes one user-provided command in the configured remote project directory.
     * The caller owns this client's lifecycle and may cancel it through [cancelAction].
     */
    fun executeRemoteCommand(juggGradleCompileOptions: JuggGradleCompileOptions, command: String): Int {
        require(command.isNotBlank()) { "Remote command must not be blank." }
        isTerminalOutputLoggingEnabled = false
        if (isCanceled) {
            return IGradleCompileClient.Error.ERROR_CANCELED
        }
        try {
            login(
                juggGradleCompileOptions,
                resetCanceled = false,
                connectTimeoutMs = REMOTE_COMMAND_CONNECT_TIMEOUT_MS,
            )
        } catch (e: Throwable) {
            if (isCanceled) {
                return IGradleCompileClient.Error.ERROR_CANCELED
            }
            throw e
        }
        if (isCanceled) {
            return IGradleCompileClient.Error.ERROR_CANCELED
        }
        val remoteCommand = RemoteUserCommand(juggGradleCompileOptions.remoteProjectPath, command.trim())
        return invoke(remoteCommand, noOutputTimeoutMs = null)
    }

    private fun openShellChannel() {
        val session = session ?: throw JuggInternalException.notLoginYet()
        val shell = session.openChannel("shell") as ChannelShell
        shell.setPtyType("xterm-256color")
        shell.setPtySize(120, 80, 640, 480)
        // JSch requires getInputStream() before connect(), otherwise early shell output may be lost.
        this.inputStream = shell.inputStream
        this.shellInputStream = BufferedInputStream(shell.inputStream)
        shell.connect()
        this.channel = shell
        logger.debug("[Jugg] JSch shell channel opened with PTY xterm-256color")
    }

    /**
     * Wait until remote shell accepts commands. Drain init output, respond to PTY queries, verify with probe echo retries.
     */
    private fun waitShellReady(): Boolean {
        val channel = channel ?: return false
        val input = shellInputStream ?: return false
        val commander = PrintStream(channel.outputStream, false)
        logger.debug("[Jugg] waiting for remote shell ready probe...")

        val deadlineMs = System.currentTimeMillis() + 30_000
        val rawBuffer = StringBuilder()
        var lastProbeTime = 0L
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadlineMs && !isCanceled) {
            val now = System.currentTimeMillis()
            val shouldProbe = now - startTime >= INITIAL_SHELL_DRAIN_MS &&
                (lastProbeTime == 0L || now - lastProbeTime >= SHELL_READY_PROBE_RETRY_MS)
            if (shouldProbe) {
                commander.print("\n")
                commander.printlnCompat(JschShellTerminalHelper.SHELL_READY_PROBE_COMMAND)
                commander.flush()
                lastProbeTime = now
                logger.debug("[Jugg] shell ready probe sent")
            }

            val chunkDeadline = minOf(deadlineMs, now + 200)
            if (pollShellInput(
                input = input,
                commander = commander,
                rawBuffer = rawBuffer,
                deadlineMs = chunkDeadline,
                command = null,
            ) { line -> JschShellTerminalHelper.parseShellReadyResult(line) == 0 }.isMatched) {
                logger.debug("[Jugg] remote shell ready probe succeeded")
                return true
            }
        }
        logger.warn("[Jugg] remote shell ready probe timeout, tail: ${JschShellTerminalHelper.stripAnsi(rawBuffer.toString()).trim()}")
        return false
    }

    private fun disableShellEcho(): Boolean {
        val channel = channel ?: return false
        val input = shellInputStream ?: return false
        val commander = PrintStream(channel.outputStream, false)
        val rawBuffer = StringBuilder()
        commander.printlnCompat(JschShellTerminalHelper.DISABLE_SHELL_ECHO_COMMAND)
        commander.flush()
        val result = pollShellInput(
            input = input,
            commander = commander,
            rawBuffer = rawBuffer,
            deadlineMs = System.currentTimeMillis() + 5_000,
            command = null,
        ) { line -> JschShellTerminalHelper.parseShellEchoDisabledResult(line) == 0 }
        if (!result.isMatched) {
            logger.warn("[Jugg] failed to disable remote shell echo")
        }
        return result.isMatched
    }

    private fun respondTerminalQueries(rawBuffer: StringBuilder, commander: PrintStream) {
        while (true) {
            val response = JschShellTerminalHelper.tryRespondTerminalQuery(rawBuffer) ?: break
            logger.debug("[Jugg] shell terminal query response sent")
            commander.print(response)
            commander.flush()
        }
    }

    /**
     * Poll shell output, auto-respond to PTY queries, optionally wait for a matching line.
     */
    private fun pollShellInput(
        input: BufferedInputStream,
        commander: PrintStream,
        rawBuffer: StringBuilder,
        deadlineMs: Long,
        command: ISshCommand?,
        commandId: Long? = null,
        noOutputTimeoutMs: Long? = null,
        onLineMatched: (String) -> Boolean,
    ): PollShellInputResult {
        var lastInterruptCode = IGradleCompileClient.Error.SUCCESS
        val startMs = System.currentTimeMillis()
        var sawOutput = false
        while (System.currentTimeMillis() < deadlineMs) {
            val currentChannel = channel
            if (currentChannel == null || !currentChannel.isConnected || currentChannel.isClosed) {
                return PollShellInputResult(isMatched = false, isNoOutputTimeout = false)
            }
            if (isCanceled) {
                if (command == null) {
                    return PollShellInputResult(isMatched = false, isNoOutputTimeout = false)
                }
                sendRemoteCtrlCIfDue(command::class.simpleName, commandId)
                if (isCancelCtrlCExhausted()) {
                    logger.info("[Jugg][cmd-$commandId] ${command::class.simpleName} did not exit after " +
                        "$cancelCtrlCAttempts Ctrl+C attempts, disconnect shell")
                    try {
                        channel?.disconnect()
                    } catch (e: Exception) {
                        logger.debug("cancelAction disconnect channel failed", e)
                    }
                    return PollShellInputResult(
                        isMatched = false,
                        isNoOutputTimeout = false,
                        isCancelAttemptsExhausted = true,
                    )
                }
            }
            respondTerminalQueries(rawBuffer, commander)
            if (input.available() > 0) {
                sawOutput = true
                val code = input.read()
                if (code == -1) {
                    return PollShellInputResult(isMatched = false, isNoOutputTimeout = false)
                }
                if (code == '\n'.code || code == '\r'.code) {
                    val line = decodeTerminalLine(rawBuffer)
                    if (line.isNotEmpty()) {
                        val isMatched = onLineMatched(line)
                        val isCanOutput = command == null || command.isCanOutput(line, false)
                        if (isCanOutput) {
                            printToStream(line)
                        }
                        if (isMatched) {
                            return PollShellInputResult(isMatched = true, isNoOutputTimeout = false)
                        }
                        if (command != null && isCanOutput) {
                            val output = command.getInput(line)
                            if (output != null) {
                                logger.debug("output: $output")
                                commander.printlnCompat(output)
                                commander.flush()
                            }
                        }
                    }
                } else {
                    rawBuffer.append(code.toChar())
                    respondTerminalQueries(rawBuffer, commander)
                    if (command != null) {
                        val interruptCode = command.shouldInterrupted(code, rawBuffer)
                        if (interruptCode != null && lastInterruptCode != interruptCode) {
                            if (interruptCode == IGradleCompileClient.Error.ERROR_NEED_LOGIN_IFT_USER ||
                                interruptCode == IGradleCompileClient.Error.ERROR_NEED_LOGIN_IFT_PASSWORD) {
                                lastInterruptCode = interruptCode
                                val content = "iFt ${rawBuffer.toString().replace(":", "")}"
                                val output = PlatformApi.showUserAndPasswordInputDialog(
                                    content,
                                    isPassword = interruptCode == IGradleCompileClient.Error.ERROR_NEED_LOGIN_IFT_PASSWORD,
                                )
                                if (output == null) {
                                    return PollShellInputResult(isMatched = false, isNoOutputTimeout = false)
                                }
                                commander.printlnCompat(output)
                                commander.flush()
                            }
                        }
                    }
                }
            } else {
                if (!sawOutput && noOutputTimeoutMs != null && System.currentTimeMillis() - startMs >= noOutputTimeoutMs) {
                    return PollShellInputResult(isMatched = false, isNoOutputTimeout = true)
                }
                Thread.sleep(50)
            }
        }
        return PollShellInputResult(isMatched = false, isNoOutputTimeout = false)
    }

    private fun decodeTerminalLine(buffer: StringBuilder): String {
        val line = String(buffer.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        buffer.setLength(0)
        return line
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
            try {
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
            } catch (e: Exception) {
                logger.debug("ignore key $it, error: ${e.message}")
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
        val gradleCompileSettings = checkLoginOnStart()

        if (gradleCompileSettings.syncMode.isRsync) {
            RsyncCompatibleHelper.init(logger)
        }
        if (!isOnlyFetchResult) {
            // 1. sync source
            val syncFileResult = syncSourceFile(gradleCompileSettings)
            if (!syncFileResult.isSuccess) {
                return syncFileResult
            }

            // 2. compile
            val compileProjectCommand = CompileProjectCommand(
                gradleCompileSettings.compileCommand,
                gradleCompileSettings.remoteProjectPath,
                gradleCompileSettings.remoteInitGradleFilePath,
                localProjectPath = projectDir.path,
                logger = logger,
                buildTarget = gradleCompileSettings.buildTarget,
                libraryTestApkGradleTasks = gradleCompileSettings.libraryTestApkGradleTasks,
            )
            val compileProjectResult = invoke(compileProjectCommand)
            if (compileProjectResult != 0) {
                printToStreamErrorIfCanceled("Compile project failed, please check the error message.")
                return GradleCompileResult.failed(isCanceled, failedReason = "Compile project failed")
            }
        }

        // 3. find and fetch apk
        val lookupPlan = ApkLookupPlanner.build(gradleCompileSettings)
        val lookingApkPaths = lookupPlan.requiredPatterns
        val findApks = mutableListOf<RemoteApk>()
        val failedApkPaths = mutableListOf<String>()
        val shouldIndexAppApk = lookingApkPaths.size > 1 || gradleCompileSettings.buildTarget == BuildTarget.ANDROID_TEST
        lookingApkPaths.forEachIndexed { index, apkPath ->
            val finalIndex = if (shouldIndexAppApk) index else -1
            val apkFile = findApk(finalIndex, apkPath, gradleCompileSettings)
            if (apkFile != null) {
                findApks.add(apkFile)
            } else {
                failedApkPaths.add(apkPath)
            }
        }
        findAndroidTestApks(findApks, gradleCompileSettings).let { testApks ->
            findApks.addAll(testApks)
            if (gradleCompileSettings.buildTarget == BuildTarget.ANDROID_TEST && testApks.isEmpty()) {
                failedApkPaths.add("androidTest APK for ${gradleCompileSettings.outputApkName}")
            }
        }
        findApks.addAll(findOptionalLibraryTestApks(findApks.size, lookupPlan.optionalLibraryTestPatterns, gradleCompileSettings))

        if (failedApkPaths.isNotEmpty()) {
            printToStreamError("Can't find apks in $failedApkPaths in ${projectDir.path}, " +
                    "total: \"${gradleCompileSettings.outputApkName}\"")
            return GradleCompileResult.failed(isCanceled, "Can't find apk in $failedApkPaths")
        } else {
            logger.debug("Find apk: ${findApks.map { it.localFile }}")
        }

        FetchedApkCleaner.clean(
            gradleCompileSettings.localClasspathStoragePath.apkDir,
            findApks.map { it.localFile },
        )
        return GradleCompileResult.success(findApks.map { it.localFile })
    }

    private fun findApk(
        index: Int,
        outputApkName: String,
        gradleCompileSettings: JuggGradleCompileOptions,
        isRequired: Boolean = true,
        reportMissing: Boolean = true,
    ): RemoteApk? {
        // find apk path
        val findOutputCommand = FindOutputCommand(gradleCompileSettings.remoteProjectPath, outputApkName)
        val findOutputResult = invoke(findOutputCommand)
        if (findOutputResult != 0) {
            reportFindApkFailure(isRequired, reportMissing, "Find APK failed, please check your sync client is opened.")
            return null
        }
        val apkPath = findOutputCommand.apkPath
        if (!ApkLookupPlanner.isFoundRemoteApkPath(apkPath)) {
            reportFindApkFailure(isRequired, reportMissing, "Find APK failed, please check your apk name is correct.")
            return null
        }
        val foundApkPath = apkPath!!

        // fetch apk
        val remoteSeparator = if (isRemoteWindows) '\\' else '/'
        val fetchOutputCommand = if (gradleCompileSettings.syncMode.isRsync) {
            val absoluteApkPath = gradleCompileSettings.remoteProjectRsyncPath + remoteSeparator + foundApkPath
            RsyncFetchOutputCommand(
                finalPasswordOrKey,
                gradleCompileSettings.remoteSshPort,
                keyPathList,
                absoluteApkPath,
                gradleCompileSettings.remoteToLocalProjectRsyncPath,
            )
        } else {
            val absoluteApkPath = gradleCompileSettings.remoteProjectPath + remoteSeparator + foundApkPath
            FetchOutputCommand(
                absoluteApkPath,
                gradleCompileSettings.remoteToLocalProjectIftPath,
            )
        }
        val fetchOutputResult = invoke(fetchOutputCommand)
        if (fetchOutputResult != 0) {
            reportFindApkFailure(isRequired, reportMissing, "Fetch output from remote to local failed, please check your sync client is opened.")
            return null
        }

        val apkFileName = foundApkPath.lastIndexOf(remoteSeparator).let {
            if (it == -1) {
                foundApkPath
            } else {
                foundApkPath.substring(it + 1)
            }
        }
        val apkFiles = File(gradleCompileSettings.remoteToLocalProjectSyncPath)
            .findFilesRecursively(apkFileName)

        if (apkFiles.isNullOrEmpty()) {
            reportFindApkFailure(isRequired, reportMissing, "find apk name with pattern '$outputApkName' " +
                    "in ${gradleCompileSettings.remoteToLocalProjectSyncPath} failed, " +
                    "please check your 'Remote to local sync path' in configuration is correct.")
            return null
        }

        // find arm64-v8a -> find universal -> get first
        val apkFile = apkFiles.find { it.name.contains("-arm64-v8a-") }
            ?: apkFiles.find { it.name.contains("-universal-") }
            ?: apkFiles[0]
        logger.debug("Find apks ${apkFiles.size}: ${apkFiles.map { it.absolutePath }}, result: $apkFile")

        if (index >= 0) {
            val indexApkFile = apkFile.parentFile.resolve("${index}_${apkFileName}")
            apkFile.renameTo(indexApkFile)
            return RemoteApk(foundApkPath, indexApkFile)
        }

        return RemoteApk(foundApkPath, apkFile)
    }

    private fun reportFindApkFailure(isRequired: Boolean, reportMissing: Boolean, message: String) {
        if (!reportMissing) {
            return
        }
        if (isRequired) {
            printToStreamErrorIfCanceled(message)
        } else {
            logger.warn("Optional library test APK not found: $message")
        }
    }

    private fun findOptionalLibraryTestApks(
        startIndex: Int,
        patterns: List<String>,
        gradleCompileSettings: JuggGradleCompileOptions,
    ): List<RemoteApk> {
        return patterns.mapIndexedNotNull { index, pattern ->
            findApk(startIndex + index, pattern, gradleCompileSettings, isRequired = false)
        }
    }

    private fun findAndroidTestApks(
        appApks: List<RemoteApk>,
        gradleCompileSettings: JuggGradleCompileOptions,
    ): List<RemoteApk> {
        if (gradleCompileSettings.buildTarget != BuildTarget.ANDROID_TEST) {
            return emptyList()
        }
        return appApks.mapIndexedNotNull { index, appApk ->
            val testApkPatterns = LocalGradleCompileClient.deriveAndroidTestApkPatterns(appApk.remotePath)
            if (testApkPatterns.isEmpty()) {
                logger.warn("AndroidTest mode: cannot derive test APK pattern from ${appApk.remotePath}")
                return@mapIndexedNotNull null
            }
            testApkPatterns.firstNotNullOfOrNull { pattern ->
                findApk(
                    appApks.size + index,
                    pattern,
                    gradleCompileSettings,
                    reportMissing = pattern == testApkPatterns.last(),
                )
            }
        }
    }

    private data class RemoteApk(val remotePath: String, val localFile: File)

    private fun checkLoginOnStart(): JuggGradleCompileOptions {
        val gradleCompileSettings = juggGradleCompileOptions
        if (session?.isConnected != true || channel?.isConnected != true || gradleCompileSettings == null) {
            throw JuggInternalException.notLoginYet()
        }
        return gradleCompileSettings
    }

    private fun syncSourceFile(gradleCompileSettings: JuggGradleCompileOptions): GradleCompileResult {
        val mkDirCommand = MkDirCommand(gradleCompileSettings.remoteSyncRootPath)
        val mkDirResult = invoke(mkDirCommand)
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
                gradleCompileSettings.effectiveRemoteSyncExcludePatterns,
            )
        } else {
            SyncFileCommand(
                gradleCompileSettings.localSyncIftPath,
                gradleCompileSettings.remoteSyncRootPath,
                gradleCompileSettings.remoteProjectSyncRelativePath,
                gradleCompileSettings.effectiveRemoteSyncExcludePatterns,
            )
        }
        val syncFileResult = invoke(syncFileCommand)
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
        val gradleCompileSettings = checkLoginOnStart()

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
        val fetchClasspathResult = invoke(fetchClasspathCommand)
        if (fetchClasspathResult != 0) {
            printToStreamErrorIfCanceled("Fetch classpath failed, please check your sync client is opened.")
            return null
        }
        return File(gradleCompileSettings.remoteToLocalSyncClasspathPath)
    }

    override fun fetchLibraryChanges(incDeployTimes: Int): DependencyDiffResultSet? {
        val gradleCompileSettings = checkLoginOnStart()

        // 1. sync source
        val syncFileResult = syncSourceFile(gradleCompileSettings)
        if (!syncFileResult.isSuccess) {
            return null
        }

        // 2. run library diff
        val diffLibraryChangesCommand = DiffLibraryChangesCommand(
            gradleCompileSettings.remoteProjectPath,
            gradleCompileSettings.remoteInitGradleFilePath,
            incDeployTimes,
            localProjectPath = projectDir.path,
            buildTarget = gradleCompileSettings.buildTarget,
        )
        val compileProjectResult = invoke(diffLibraryChangesCommand)
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
        val fetchChangedLibraryResult = invoke(fetchChangedLibraryCommand)
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
        isCanceled = true
        logger.info("[Jugg] remote compile cancel requested, sessionConnected=${session?.isConnected}, " +
            "channelConnected=${channel?.isConnected}, channelClosed=${channel?.isClosed}")
        cmdExecutor.release()
        if (channel == null) {
            logger.info("[Jugg] remote compile cancel requested before shell ready")
            try {
                session?.disconnect()
            } catch (e: Exception) {
                logger.debug("cancelAction disconnect pending session failed", e)
            }
            return
        }
        val commandId = currentRemoteCommandId
        if (commandId == null) {
            logger.info("[Jugg] remote compile cancel requested with no active remote shell command")
            return
        }
        sendRemoteCtrlCIfDue(currentRemoteCommandName, commandId)
    }

    private fun sendRemoteCtrlCIfDue(commandName: String?, commandId: Long?) {
        val currentChannel = channel ?: return
        if (!markCancelCtrlCAttemptIfDue()) {
            return
        }
        try {
            val commander = PrintStream(currentChannel.outputStream, true)
            commander.print(String(byteArrayOf(0x03))) // Ctrl+C
            commander.flush()
            logger.info("[Jugg][cmd-$commandId] remote compile cancel sent Ctrl+C attempt " +
                "$cancelCtrlCAttempts/$CANCEL_CTRL_C_MAX_ATTEMPTS, command=$commandName")
        } catch (e: Exception) {
            logger.debug("cancelAction send Ctrl+C failed", e)
        }
    }

    @Synchronized
    private fun resetCancelCtrlCState() {
        cancelCtrlCAttempts = 0
        lastCancelCtrlCAtMs = Long.MIN_VALUE
    }

    @Synchronized
    private fun markCancelCtrlCAttemptIfDue(): Boolean {
        val now = System.currentTimeMillis()
        if (cancelCtrlCAttempts >= CANCEL_CTRL_C_MAX_ATTEMPTS) {
            return false
        }
        if (cancelCtrlCAttempts > 0 && now - lastCancelCtrlCAtMs < CANCEL_CTRL_C_INTERVAL_MS) {
            return false
        }
        cancelCtrlCAttempts++
        lastCancelCtrlCAtMs = now
        return true
    }

    @Synchronized
    private fun isCancelCtrlCExhausted(): Boolean {
        return cancelCtrlCAttempts >= CANCEL_CTRL_C_MAX_ATTEMPTS &&
            System.currentTimeMillis() - lastCancelCtrlCAtMs >= CANCEL_CTRL_C_INTERVAL_MS
    }

    private fun invoke(command: ISshCommand, noOutputTimeoutMs: Long? = NO_OUTPUT_TIMEOUT_MS): Int {
        printToStreamInfo("[Jugg] ${command::class.simpleName} exec start")

        command.beforeInvokeCommand()
        val result = if (command is RsyncCommand) {
            invokeRsyncCommand(command)
        } else {
            remoteInvoke(command, noOutputTimeoutMs)
        }

        printToStreamInfo("[Jugg] ${command::class.simpleName} exec finished with result: $result")
        return result
    }

    private fun invokeRsyncCommand(command: RsyncCommand): Int {
        if (isCanceled) {
            logger.info("[Jugg] skip ${command::class.simpleName} because remote compile is canceled")
            return IGradleCompileClient.Error.ERROR_CANCELED
        }
        cmdExecutor.terminalOutputListener = terminalOutputListener
        var lastResult = IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
        for (attempt in 0 until RsyncAuthRetryPolicy.MAX_ATTEMPTS) {
            if (attempt > 0) {
                if (isCanceled) {
                    return lastResult
                }
                val delayMs = RsyncAuthRetryPolicy.retryDelaysMs[attempt - 1]
                logger.info("Retry rsync after transient SSH auth failure, attempt ${attempt + 1}/${RsyncAuthRetryPolicy.MAX_ATTEMPTS}, delay ${delayMs}ms")
                Thread.sleep(delayMs)
            }
            val outputLines = mutableListOf<String>()
            lastResult = cmdExecutor.invoke(command, outputCollector = outputLines)
            lastResult = normalizeRsyncProcessResult(lastResult)
            if (lastResult == IGradleCompileClient.Error.SUCCESS || isCanceled) {
                return lastResult
            }
            if (!RsyncAuthRetryPolicy.isRetryable(lastResult, outputLines)) {
                return lastResult
            }
        }
        return lastResult
    }

    private fun normalizeRsyncProcessResult(result: Int): Int {
        if (!isCanceled && result == IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED) {
            logger.warn("process exit without print result, behavior may incorrect")
            return IGradleCompileClient.Error.SUCCESS
        }
        return result
    }

    private fun remoteInvoke(command: ISshCommand, noOutputTimeoutMs: Long?): Int {
        if (isCanceled) {
            return IGradleCompileClient.Error.ERROR_CANCELED
        }
        val channel = channel ?: run {
            logger.warn("Shell channel is null, current state is unexpected, exit.")
            return IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
        }
        val input = shellInputStream ?: run {
            logger.warn("Shell input stream is null, current state is unexpected, exit.")
            return IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
        }
        val commander = PrintStream(channel.outputStream, false)
        val commandString = remoteEnvironmentPrefix +
            command.getCommand(isNeedSetChineseLanguage = true, isWindows = false)
        val commandId = remoteCommandCounter.incrementAndGet()
        val sentAt = System.currentTimeMillis()
        val safeCommand = remoteEnvironmentPrefix +
            command.getPrintSafeCommand(isNeedSetChineseLanguage = true, isWindows = false)
        resetCancelCtrlCState()
        currentRemoteCommandName = command::class.simpleName
        currentRemoteCommandId = commandId
        logger.info("[Jugg][cmd-$commandId] send ${command::class.simpleName}")
        logger.debug("[Jugg][cmd-$commandId] safeCommandHash=${safeCommand.hashCode()} length=${safeCommand.length}")
        commander.printlnCompat(commandString)
        commander.flush()

        val resultEcho = "(Jugg) ${command::class.simpleName} result: "
        val rawBuffer = StringBuilder()
        var parsedResult: Int? = null
        val pollResult = try {
            pollShellInput(
                input = input,
                commander = commander,
                rawBuffer = rawBuffer,
                deadlineMs = Long.MAX_VALUE,
                command = command,
                commandId = commandId,
                noOutputTimeoutMs = noOutputTimeoutMs,
            ) { line ->
                val currentResult = command.hasFinishWithResult(line)
                if (currentResult != null) {
                    val elapsedMs = System.currentTimeMillis() - sentAt
                    logger.debug("[Jugg][cmd-$commandId] ${command::class.simpleName} parsed result line: $line -> $currentResult, elapsed=${elapsedMs}ms")
                    parsedResult = currentResult
                    true
                } else {
                    if (line.startsWith(resultEcho) && line.endsWith("?")) {
                        logger.debug("[Jugg][cmd-$commandId] ${command::class.simpleName} skip echoed template line: $line")
                    }
                    false
                }
            }
        } finally {
            if (currentRemoteCommandId == commandId) {
                currentRemoteCommandName = null
                currentRemoteCommandId = null
            }
        }
        val elapsedMs = System.currentTimeMillis() - sentAt

        if (isCanceled) {
            if (parsedResult != null) {
                logger.info("[Jugg][cmd-$commandId] ${command::class.simpleName} exited after cancel, " +
                    "result=$parsedResult, elapsed=${elapsedMs}ms")
            } else if (pollResult.isCancelAttemptsExhausted) {
                logger.info("[Jugg][cmd-$commandId] ${command::class.simpleName} cancel exhausted after ${elapsedMs}ms")
            } else {
                logger.info("[Jugg][cmd-$commandId] ${command::class.simpleName} observed cancel after ${elapsedMs}ms")
            }
            return IGradleCompileClient.Error.ERROR_CANCELED
        }
        if (parsedResult != null) {
            logger.info("[Jugg][cmd-$commandId] done in ${elapsedMs}ms")
            return parsedResult!!
        }
        if (pollResult.isNoOutputTimeout) {
            val available = try {
                input.available()
            } catch (e: Exception) {
                -1
            }
            val timeoutMessage = "[Jugg][cmd-$commandId] no output in ${noOutputTimeoutMs}ms after send, " +
                "command=${command::class.simpleName}, sessionConnected=${session?.isConnected}, " +
                "channelConnected=${channel.isConnected}, channelClosed=${channel.isClosed}, " +
                "exitStatus=${channel.exitStatus}, inputAvailable=$available, elapsed=${elapsedMs}ms"
            logger.warn(timeoutMessage)
            printToStreamErrorIfCanceled(timeoutMessage)
            return IGradleCompileClient.Error.ERROR_FAILED
        }
        if (channel.isClosed) {
            printToStream("[Jugg] exit-status: " + channel.exitStatus)
            return IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
        }
        return if (pollResult.isMatched) {
            IGradleCompileClient.Error.SUCCESS
        } else {
            IGradleCompileClient.Error.ERROR_FAILED
        }
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
        if (isTerminalOutputLoggingEnabled) {
            logger.debug(line)
        }
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
        closeRemoteConnection()
        cmdExecutor.release()
    }

    private fun closeRemoteConnection() {
        shellInputStream = null
        try {
            inputStream?.close()
        } catch (e: Exception) {
            logger.debug("Close remote input stream failed", e)
        }
        try {
            channel?.disconnect()
        } catch (e: Exception) {
            logger.debug("Disconnect remote channel failed", e)
        }
        try {
            session?.disconnect()
        } catch (e: Exception) {
            logger.debug("Disconnect remote session failed", e)
        }
        inputStream = null
        channel = null
        session = null
        remoteEnvironmentPrefix = ""
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

    private data class PollShellInputResult(
        val isMatched: Boolean,
        val isNoOutputTimeout: Boolean,
        val isCancelAttemptsExhausted: Boolean = false,
    )

    companion object {
        /** Wait for login banner / PTY queries before the first probe command. */
        private const val INITIAL_SHELL_DRAIN_MS = 800L

        /** Re-send probe if the previous one was only echoed during shell init. */
        private const val SHELL_READY_PROBE_RETRY_MS = 1500L

        /** Timeout for commands that produce no shell output at all after being sent. */
        private const val NO_OUTPUT_TIMEOUT_MS = 90_000L

        private const val REMOTE_COMMAND_CONNECT_TIMEOUT_MS = 30_000

        private const val CANCEL_CTRL_C_MAX_ATTEMPTS = 5
        private const val CANCEL_CTRL_C_INTERVAL_MS = 1_000L
    }

    private fun throwIfCanceled() {
        if (isCanceled) {
            throw JuggException.loginToRemoteFailed("User canceled.")
        }
    }
}
