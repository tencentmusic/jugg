package com.sickworm.intellij.jugg.remote

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jcraft.jsch.*
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

    init {
        Disposer.register(parent, this)
    }

    override fun login(clientInfo: RemoteCompileClientInfo) {
        if ((this.clientInfo == clientInfo) && (session?.isConnected == true) && channel != null) {
            logger.info("${clientInfo.ip} already login")
            return
        }

        dispose()

        try {
            val jsch = JSch()
            JSch.setLogger(JschLogger())
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
            logger.warn("RemoteClient login failed", e)
            throw JuggException.loginToRemoteFailed()
        }
    }

    override fun compileAndFetchResult(): RemoteCompileResult {
        val channel = channel
        val clientInfo = clientInfo
        if (channel == null || clientInfo == null) {
            throw JuggInternalException.notLoginYet()
        }

        val syncFileCommand = SyncFileCommand(clientInfo.localProjectPath, clientInfo.serverProjectPath)
        val syncFileResult = invoke(channel, syncFileCommand)
        if (syncFileResult != 0) {
            logger.warn("Sync file from local failed, please check your iFt client is opened.")
            return RemoteCompileResult.failed()
        }

        val compileProjectCommand = CompileProjectCommand(clientInfo.serverProjectPath)
        val compileProjectResult = invoke(channel, compileProjectCommand)
        if (compileProjectResult != 0) {
            logger.warn("Compile project failed, please check your iFt client is opened.")
            return RemoteCompileResult.failed()
        }


        val fetchOutputCommand = FetchOutputCommand(clientInfo.remoteToLocalIftConfigName)
        val fetchOutputResult = invoke(channel, fetchOutputCommand)
        if (fetchOutputResult != 0) {
            logger.warn("Fetch output failed, please check your iFt client is opened.")
            return RemoteCompileResult.failed()
        }

        return RemoteCompileResult.success(File(""))
    }

    private fun invoke(channel: Channel, command: ISshCommand): Int {
        println("${command::class.simpleName} exec start")

        val commander = PrintStream(channel.outputStream, true)
        commander.println(command.command)
        commander.flush()

        val reader = channel.inputStream.bufferedReader(Charsets.UTF_8)
        val result: Int
        while (true) {
            val line = reader.readLine()
            if (line != null) {
                if (line.isNotEmpty()) {
                    println(line)
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
                println("exit-status: " + channel.exitStatus)
                result = RESULT_CHANNEL_CLOSED
                break
            }
        }

        println("${command::class.simpleName} exec finished with result: $result")
        return result
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
}


class JschLogger : Logger {
    override fun isEnabled(level: Int): Boolean {
        return true
    }

    override fun log(level: Int, message: String) {
        println(name(level) + ": " + message)
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