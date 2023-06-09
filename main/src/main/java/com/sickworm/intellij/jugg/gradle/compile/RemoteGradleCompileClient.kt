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
    private val logger: com.intellij.openapi.diagnostic.Logger = JuggLogger.getInstance(project, "RemoteClient"),
) : IGradleCompileClient {

    private var session: Session? = null
    private var channel: Channel? = null
    private var juggGradleCompileOptions: JuggGradleCompileOptions? = null

    override var terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

    override fun login(juggGradleCompileOptions: JuggGradleCompileOptions) {
        if ((this.juggGradleCompileOptions == juggGradleCompileOptions) && (session?.isConnected == true) && channel != null) {
            printToStreamInfo("${juggGradleCompileOptions.remoteSshIp} already login")
            return
        }

        dispose()

        try {
            val jsch = JSch()
            JSch.setLogger(JschLogger { terminalOutputListener })
            val session = jsch.getSession(
                juggGradleCompileOptions.remoteSshUser,
                juggGradleCompileOptions.remoteSshIp,
                juggGradleCompileOptions.remoteSshPort)
            if (juggGradleCompileOptions.httpProxyIp.isNotEmpty() &&
                juggGradleCompileOptions.httpProxyPort != 0) {
                session.setProxy(ProxyHTTP(juggGradleCompileOptions.httpProxyIp, juggGradleCompileOptions.httpProxyPort))
            }
            session.setPassword(juggGradleCompileOptions.remoteSshPassword)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("Charset", "UTF-8")
//            session.set("LC_CTYPE", "zh_CN.UTF-8"); // 设置 LC_CTYPE 环境变量
            session.connect()
            val channel = session.openChannel("shell")
            channel.connect()

            this.session = session
            this.channel = channel
            this.juggGradleCompileOptions = juggGradleCompileOptions
        } catch (e: JSchException) {
            printToStreamError("RemoteClient login failed", e)
            throw JuggException.loginToRemoteFailed()
        }
    }

    override fun compileAndFetchResult(): GradleCompileResult {
        isCanceled = false
        val channel = channel
        val gradleCompileSettings = juggGradleCompileOptions
        if (channel == null || gradleCompileSettings == null) {
            throw JuggInternalException.notLoginYet()
        }

        val syncFileCommand = SyncFileCommand(gradleCompileSettings.localProjectIftPath, gradleCompileSettings.remoteProjectPath)
        val syncFileResult = invoke(channel, syncFileCommand)
        if (syncFileResult != 0) {
            printToStreamErrorIfCanceled("Sync file from local to remote failed, please check your iFt client is opened.")
            return GradleCompileResult.failed(isCanceled)
        }

        val compileProjectCommand = CompileProjectCommand(gradleCompileSettings.compileCommand, gradleCompileSettings.remoteProjectPath)
        val compileProjectResult = invoke(channel, compileProjectCommand)
        if (compileProjectResult != 0) {
            printToStreamErrorIfCanceled("Compile project failed, please check the error message.")
            return GradleCompileResult.failed(isCanceled)
        }


        val fetchOutputCommand = FetchOutputCommand(
            gradleCompileSettings.outputApkName,
            gradleCompileSettings.remoteToLocalProjectIftPath,
        )
        val fetchOutputResult = invoke(channel, fetchOutputCommand)
        if (fetchOutputResult != 0) {
            printToStreamErrorIfCanceled("Fetch output from remote to local failed, please check your iFt client is opened.")
            return GradleCompileResult.failed(isCanceled)
        }

        val apkFile = File(gradleCompileSettings.remoteToLocalProjectSyncPath)
            .findFilesRecursively(gradleCompileSettings.outputApkName)
        if (apkFile == null) {
            printToStreamErrorIfCanceled("find apk name with pattern '${gradleCompileSettings.outputApkName}' " +
                    "in ${gradleCompileSettings.remoteToLocalProjectSyncPath} failed, " +
                    "please check your 'Remote to local sync path' in configuration is correct.")
            return GradleCompileResult.failed(isCanceled)
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

        val fetchClasspathCommand = FetchClasspathCommand(
            gradleCompileSettings.remoteProjectPath,
            gradleCompileSettings.remoteToLocalProjectIftPath,
            buildDirs
        )
        val fetchClasspathResult = invoke(channel, fetchClasspathCommand)
        if (fetchClasspathResult != 0) {
            printToStreamErrorIfCanceled("Fetch classpath failed, please check your iFt client is opened.")
            return null
        }
        return File(gradleCompileSettings.remoteToLocalProjectIftPath)
    }

    @Volatile
    private var isCanceled = false

    override fun cancelAction(isByUser: Boolean) {
        if (isByUser) {
            printToStreamInfo("[Jugg] user cancel")
        }
        val channel = channel ?: throw JuggInternalException.notLoginYet()
        val commander = PrintStream(channel.outputStream, true)
        commander.print(String(byteArrayOf(0x03))) // control c
        commander.flush()
        isCanceled = true
    }

    private fun invoke(channel: Channel, command: ISshCommand): Int {
        printToStreamInfo("[Jugg] ${command::class.simpleName} exec start")

        command.beforeInvokeCommand()
        val commander = PrintStream(channel.outputStream, false)
        commander.println(command.command)
        commander.flush()

        val buffer = StringBuilder()
        val bufferedInputStream = BufferedInputStream(channel.inputStream)
        val result: Int
        while (true) {
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
            }
            if (line.isNotEmpty()) {
                printToStream(line)

                val output = command.getInput(line)
                if (output != null) {
                    commander.println(output)
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

        printToStreamInfo("[Jugg] ${command::class.simpleName} exec finished with result: $result")
        return result
    }

    private fun printToStream(line: String) {
        terminalOutputListener.onOutput(line)
    }

    private fun printToStreamInfo(line: String) {
        logger.info(line)
        terminalOutputListener.onOutput(line)
    }

    private fun printToStreamError(line: String, e: Exception? = null) {
        logger.warn(line, e)
        terminalOutputListener.onOutputErr(line)
        e?.let {
            terminalOutputListener.onOutputErr(e.toString())
        }
    }

    private fun printToStreamErrorIfCanceled(line: String, e: Exception? = null) {
        if (isCanceled) {
            return
        }
        return printToStreamError(line, e)
    }

    override fun dispose() {
        session?.disconnect()
        channel?.disconnect()
        session = null
        channel = null
    }

}


class JschLogger(
    private val terminalOutputListener: () -> IGradleCompileClient.TerminalOutputListener?,
) : Logger {

    override fun isEnabled(level: Int): Boolean {
        return true
    }

    override fun log(level: Int, message: String) {
        val levelMessage = name(level) + ": " + message
        if (level >= Logger.WARN) {
            terminalOutputListener.invoke()?.onOutputErr(levelMessage)
        } else {
            terminalOutputListener.invoke()?.onOutput(levelMessage)
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