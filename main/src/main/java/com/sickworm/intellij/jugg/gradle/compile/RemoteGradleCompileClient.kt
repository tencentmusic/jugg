package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.project.Project
import com.jcraft.jsch.*
import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.GradleCompileSettings
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import java.io.PrintStream

class RemoteGradleCompileClient(
    project: Project,
    private val logger: com.intellij.openapi.diagnostic.Logger = JuggLogger.getInstance(project, "RemoteClient"),
) : IGradleCompileClient {

    private var session: Session? = null
    private var channel: Channel? = null
    private var gradleCompileSettings: GradleCompileSettings? = null

    override var terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

    override fun login(gradleCompileSettings: GradleCompileSettings) {
        if ((this.gradleCompileSettings == gradleCompileSettings) && (session?.isConnected == true) && channel != null) {
            printToStreamInfo("${gradleCompileSettings.remoteClientInfo.ip} already login")
            return
        }
        val clientInfo = gradleCompileSettings.remoteClientInfo

        dispose()

        try {
            val jsch = JSch()
            JSch.setLogger(JschLogger { terminalOutputListener })
            val session = jsch.getSession(clientInfo.user, clientInfo.ip, clientInfo.port)
            if (clientInfo.httpProxyIp != null && clientInfo.httpProxyPort != null) {
                session.setProxy(ProxyHTTP(clientInfo.httpProxyIp, clientInfo.httpProxyPort))
            }
            session.setPassword(clientInfo.password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect()
            val channel = session.openChannel("shell")
            channel.connect()

            this.session = session
            this.channel = channel
            this.gradleCompileSettings = gradleCompileSettings
        } catch (e: JSchException) {
            printToStreamError("RemoteClient login failed", e)
            throw JuggException.loginToRemoteFailed()
        }
    }

    override fun compileAndFetchResult(): GradleCompileResult {
        isCanceled = false
        val channel = channel
        val gradleCompileSettings = gradleCompileSettings
        val clientInfo = gradleCompileSettings?.remoteClientInfo
        if (channel == null || gradleCompileSettings == null || clientInfo == null) {
            throw JuggInternalException.notLoginYet()
        }

        val syncFileCommand = SyncFileCommand(clientInfo.localProjectIftPath, clientInfo.remoteProjectPath)
        val syncFileResult = invoke(channel, syncFileCommand)
        if (syncFileResult != 0) {
            printToStreamErrorIfCanceled("Sync file from local to remote failed, please check your iFt client is opened.")
            return GradleCompileResult.failed(isCanceled)
        }

        val compileProjectCommand = CompileProjectCommand(gradleCompileSettings.compileCommand, clientInfo.remoteProjectPath)
        val compileProjectResult = invoke(channel, compileProjectCommand)
        if (compileProjectResult != 0) {
            printToStreamErrorIfCanceled("Compile project failed, please check the error message.")
            return GradleCompileResult.failed(isCanceled)
        }


        val fetchOutputCommand = FetchOutputCommand(gradleCompileSettings.compileCommand, clientInfo.remoteToLocalIftConfigName)
        val fetchOutputResult = invoke(channel, fetchOutputCommand)
        if (fetchOutputResult != 0) {
            printToStreamErrorIfCanceled("Fetch output from remote to local failed, please check your iFt client is opened.")
            return GradleCompileResult.failed(isCanceled)
        }

        return GradleCompileResult.success(File(""))
    }

    override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): Boolean {
        isCanceled = false
        val channel = channel
        val gradleCompileSettings = gradleCompileSettings
        val clientInfo = gradleCompileSettings?.remoteClientInfo
        if (channel == null || gradleCompileSettings == null || clientInfo == null) {
            throw JuggInternalException.notLoginYet()
        }


        val fetchClasspathCommand = FetchClasspathCommand(
            clientInfo.remoteProjectPath,
            clientInfo.remoteToLocalClasspathPath,
            buildDirs
        )
        val fetchClasspathResult = invoke(channel, fetchClasspathCommand)
        if (fetchClasspathResult != 0) {
            printToStreamErrorIfCanceled("Fetch classpath failed, please check your iFt client is opened.")
            return false
        }
        return true
    }

    @Volatile
    private var isCanceled = false

    override fun cancelAction() {
        printToStreamInfo("[Jugg] user cancel")
        val channel = channel ?: throw JuggInternalException.notLoginYet()
        val commander = PrintStream(channel.outputStream, true)
        commander.print(String(byteArrayOf(0x03))) // control c
        commander.flush()
        isCanceled = true
    }

    private fun invoke(channel: Channel, command: ISshCommand): Int {
        printToStreamInfo("[Jugg] ${command::class.simpleName} exec start")

        val commander = PrintStream(channel.outputStream, true)
        commander.println(command.command)
        commander.flush()

        val reader = channel.inputStream.bufferedReader(Charsets.UTF_8)
        val result: Int
        while (true) {
            val line = reader.readLine()
            if (line != null) {
                if (line.isNotEmpty()) {
                    printToStream(line)
                }
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