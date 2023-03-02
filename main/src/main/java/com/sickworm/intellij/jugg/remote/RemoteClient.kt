package com.sickworm.intellij.jugg.remote

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jcraft.jsch.*
import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import java.io.PrintStream

class RemoteClient(project: Project, parent: Disposable) : IRemoteClient, Disposable {

    private var session: Session? = null
    private var channel: Channel? = null
    private var clientInfo: RemoteCompileClientInfo? = null
    private val logger = JuggLogger.getInstance(project, "RemoteClient")

    var terminalOutputListener: TerminalOutputListener = object : TerminalOutputListener {
        override fun onOutput(line: String) {
            println(line)
        }
        override fun onOutputErr(line: String) {
            System.err.println(line)
        }
    }

    init {
        Disposer.register(parent, this)
    }

    override fun login(clientInfo: RemoteCompileClientInfo) {
        if ((this.clientInfo == clientInfo) && (session?.isConnected == true) && channel != null) {
            printToStreamInfo("${clientInfo.ip} already login")
            return
        }

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
            this.clientInfo = clientInfo
        } catch (e: JSchException) {
            printToStreamError("RemoteClient login failed", e)
            throw JuggException.loginToRemoteFailed()
        }
    }

    override fun compileAndFetchResult(): RemoteCompileResult {
        val channel = channel
        val clientInfo = clientInfo
        if (channel == null || clientInfo == null) {
            throw JuggInternalException.notLoginYet()
        }

        val syncFileCommand = SyncFileCommand(clientInfo.localProjectIftPath, clientInfo.remoteProjectPath)
        val syncFileResult = invoke(channel, syncFileCommand)
        if (syncFileResult != 0) {
            printToStreamErrorIfCanceled("Sync file from local to remote failed, please check your iFt client is opened.")
            return RemoteCompileResult.failed()
        }

        val compileProjectCommand = CompileProjectCommand(clientInfo.compileCommand, clientInfo.remoteProjectPath)
        val compileProjectResult = invoke(channel, compileProjectCommand)
        if (compileProjectResult != 0) {
            printToStreamErrorIfCanceled("Compile project failed, please check the error message.")
            return RemoteCompileResult.failed()
        }


        val fetchOutputCommand = FetchOutputCommand(clientInfo.compileCommand, clientInfo.remoteToLocalIftConfigName)
        val fetchOutputResult = invoke(channel, fetchOutputCommand)
        if (fetchOutputResult != 0) {
            printToStreamErrorIfCanceled("Fetch output from remote to local failed, please check your iFt client is opened.")
            return RemoteCompileResult.failed()
        }

        return RemoteCompileResult.success(File(""))
    }

    override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): Boolean {
        val channel = channel
        val clientInfo = clientInfo
        if (channel == null || clientInfo == null) {
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
        val channel = channel
        val clientInfo = clientInfo
        if (channel == null || clientInfo == null) {
            throw JuggInternalException.notLoginYet()
        }
        val commander = PrintStream(channel.outputStream, true)
        commander.print(String(byteArrayOf(0x03))) // control c
        commander.flush()
        isCanceled = true
    }

    private fun invoke(channel: Channel, command: ISshCommand): Int {
        printToStream("[Jugg] ${command::class.simpleName} exec start")

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
                result = RESULT_CHANNEL_CLOSED
                break
            }
        }

        printToStream("[Jugg] ${command::class.simpleName} exec finished with result: $result")
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
            isCanceled = false
            return
        }
        return printToStreamError(line, e)
    }

    companion object {
        const val RESULT_CHANNEL_CLOSED = -1001
    }

    override fun dispose() {
        session?.disconnect()
        channel?.disconnect()
        session = null
        channel = null
    }

    interface TerminalOutputListener {
        fun onOutput(line: String)
        fun onOutputErr(line: String)
    }
}


class JschLogger(
    private val terminalOutputListener: () -> RemoteClient.TerminalOutputListener?,
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