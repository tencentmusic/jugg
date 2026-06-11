package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.project.Project
import com.jcraft.jsch.*
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient.Companion.parseDiffSet
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
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
import java.util.concurrent.atomic.AtomicLong

/**
 * RemoteGradleCompileClient manages SSH-based Gradle compilation and synchronizes build outputs/dependency changes back to local.
 * Collaboration: Establishes SSH sessions via JSch, executes remote commands through [CmdExecutor]/[SshCommand], and synchronizes artifacts with [RsyncCommand] plus local deserialization helpers in [LocalGradleCompileClient].
 * Data Contract: [login] initializes/refreshes SSH channel state before build commands; authentication falls back across password and discovered key paths; login failures reset session fields and throw [JuggException.loginToRemoteFailed].
 */
class RemoteGradleCompileClient(
    private val project: Project,
    private val isRemoteWindows: Boolean = false,
    private val logger: com.intellij.openapi.diagnostic.Logger = JuggLogger.getInstance(project, "RemoteGradleCompileClient"),
) : IGradleCompileClient {

    private var session: Session? = null
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

    override fun login(juggGradleCompileOptions: JuggGradleCompileOptions) {
        logger.debug("[Jugg] remote login starts with fresh shell, reuse disabled")
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
                keyPathList = mutableListOf() // won't use keyPathList in laster rsync
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
        shellInputStream = null
        inputStream = null
        channel = null
        session = null
        remoteEnvironmentPrefix = ""
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
        logger.info("Prepared ${envVars.size} remote environment variables for shell commands")
    }

    private fun showDialogAndGetPasswordOrKey(extraTips: String): String {
        return PlatformApi.showUserAndPasswordInputDialog(
            "SSH Password or Key Path",
            subTitle = "<html>You will see this because [SSH password or key path] is empty $extraTips.</html>",
            isPassword = true,
        ) ?: throw JuggException.loginToRemoteFailed("User canceled.")
    }

    private fun doLogin(juggGradleCompileOptions: JuggGradleCompileOptions, keyPathList: List<String>, password: String) {
        isCanceled = false
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
        session.connect()

        this.session = session
        this.juggGradleCompileOptions = juggGradleCompileOptions
        updateRemoteEnvironmentPrefix(juggGradleCompileOptions.environmentVariables)
        openShellChannel()
        if (!waitShellReady()) {
            throw JuggException.loginToRemoteFailed("Remote shell is not ready, terminal handshake may have failed.")
        }
        logger.debug("login success, isUseKey: $isUseKey")
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
        noOutputTimeoutMs: Long? = null,
        onLineMatched: (String) -> Boolean,
    ): PollShellInputResult {
        var lastInterruptCode = IGradleCompileClient.Error.SUCCESS
        val startMs = System.currentTimeMillis()
        var sawOutput = false
        while (System.currentTimeMillis() < deadlineMs && !isCanceled) {
            val currentChannel = channel
            if (currentChannel == null || !currentChannel.isConnected || currentChannel.isClosed) {
                return PollShellInputResult(isMatched = false, isNoOutputTimeout = false)
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
                    if (line.isNotEmpty() && (command == null || command.isCanOutput(line, false))) {
                        printToStream(line)
                        if (onLineMatched(line)) {
                            return PollShellInputResult(isMatched = true, isNoOutputTimeout = false)
                        }
                        if (command != null) {
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
                localProjectPath = project.basePath,
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
            printToStreamError("Can't find apks in $failedApkPaths in ${project.basePath}, " +
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
    ): RemoteApk? {
        // find apk path
        val findOutputCommand = FindOutputCommand(gradleCompileSettings.remoteProjectPath, outputApkName)
        val findOutputResult = invoke(findOutputCommand)
        if (findOutputResult != 0) {
            reportFindApkFailure(isRequired, "Find APK failed, please check your sync client is opened.")
            return null
        }
        val apkPath = findOutputCommand.apkPath
        if (!ApkLookupPlanner.isFoundRemoteApkPath(apkPath)) {
            reportFindApkFailure(isRequired, "Find APK failed, please check your apk name is correct.")
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
            reportFindApkFailure(isRequired, "Fetch output from remote to local failed, please check your sync client is opened.")
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
            reportFindApkFailure(isRequired, "find apk name with pattern '$outputApkName' " +
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

    private fun reportFindApkFailure(isRequired: Boolean, message: String) {
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
            val testApkPattern = LocalGradleCompileClient.deriveAndroidTestApkPattern(appApk.remotePath)
            if (testApkPattern == null) {
                logger.warn("AndroidTest mode: cannot derive test APK pattern from ${appApk.remotePath}")
                return@mapIndexedNotNull null
            }
            findApk(appApks.size + index, testApkPattern, gradleCompileSettings)
        }
    }

    private data class RemoteApk(val remotePath: String, val localFile: File)

    private fun checkLoginOnStart(): JuggGradleCompileOptions {
        isCanceled = false
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
            )
        } else {
            SyncFileCommand(
                gradleCompileSettings.localSyncIftPath,
                gradleCompileSettings.remoteSyncRootPath,
                gradleCompileSettings.remoteProjectSyncRelativePath,
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
            localProjectPath = project.basePath,
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
        if (session == null || channel == null) {
            logger.debug("cancelAction but not login, exit")
            return
        }
        val channel = channel!!
        try {
            val commander = PrintStream(channel.outputStream, true)
            commander.print(String(byteArrayOf(0x03))) // control c
            commander.flush()
            // iFt/rsync needs control c twice
            commander.print(String(byteArrayOf(0x03))) // control c
            commander.flush()
        } catch (e: Exception) {
            logger.debug("cancelAction send Ctrl+C failed", e)
        }
        cmdExecutor.release()
        try {
            channel.disconnect()
        } catch (e: Exception) {
            logger.debug("cancelAction disconnect channel failed", e)
        }
    }

    private fun invoke(command: ISshCommand): Int {
        printToStreamInfo("[Jugg] ${command::class.simpleName} exec start")

        command.beforeInvokeCommand()
        val result = if (command is RsyncCommand) {
            invokeRsyncCommand(command)
        } else {
            remoteInvoke(command)
        }

        printToStreamInfo("[Jugg] ${command::class.simpleName} exec finished with result: $result")
        return result
    }

    private fun invokeRsyncCommand(command: RsyncCommand): Int {
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

    private fun remoteInvoke(command: ISshCommand): Int {
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
        logger.info("[Jugg][cmd-$commandId] send ${command::class.simpleName}")
        logger.debug("[Jugg][cmd-$commandId] safeCommandHash=${safeCommand.hashCode()} length=${safeCommand.length}")
        logger.debug("Jsch invoke command: $commandString")
        commander.printlnCompat(commandString)
        commander.flush()

        val resultEcho = "(Jugg) ${command::class.simpleName} result: "
        val rawBuffer = StringBuilder()
        var parsedResult: Int? = null
        val pollResult = pollShellInput(
            input = input,
            commander = commander,
            rawBuffer = rawBuffer,
            deadlineMs = Long.MAX_VALUE,
            command = command,
            noOutputTimeoutMs = NO_OUTPUT_TIMEOUT_MS,
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
        val elapsedMs = System.currentTimeMillis() - sentAt

        if (isCanceled) {
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
            val timeoutMessage = "[Jugg][cmd-$commandId] no output in ${NO_OUTPUT_TIMEOUT_MS}ms after send, " +
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
        shellInputStream = null
        inputStream?.close()
        channel?.disconnect()
        session?.disconnect()
        inputStream = null
        channel = null
        session = null
        remoteEnvironmentPrefix = ""

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

    private data class PollShellInputResult(
        val isMatched: Boolean,
        val isNoOutputTimeout: Boolean,
    )

    companion object {
        /** Wait for login banner / PTY queries before the first probe command. */
        private const val INITIAL_SHELL_DRAIN_MS = 800L

        /** Re-send probe if the previous one was only echoed during shell init. */
        private const val SHELL_READY_PROBE_RETRY_MS = 1500L

        /** Timeout for commands that produce no shell output at all after being sent. */
        private const val NO_OUTPUT_TIMEOUT_MS = 90_000L
    }
}
