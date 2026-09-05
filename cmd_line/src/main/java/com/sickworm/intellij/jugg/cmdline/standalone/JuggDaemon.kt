package com.sickworm.intellij.jugg.cmdline.standalone

import com.sickworm.intellij.jugg.ai.mcp.McpLocalServer
import com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerResources
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import com.sickworm.intellij.jugg.runtime.PluginInfoReader
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_IDLE_TIMEOUT_MILLIS = 4 * 60 * 60 * 1000L
private const val DEFAULT_IDLE_RECHECK_MILLIS = 60 * 1000L

/** Hosts standalone project runtimes and the shared MCP HTTP server. */
class JuggDaemon(
    private val runtimeInfo: RuntimeInfo = defaultRuntimeInfo(),
    idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    idleRecheckMillis: Long = DEFAULT_IDLE_RECHECK_MILLIS,
) : AutoCloseable {
    private val activity = StandaloneDaemonActivity()
    private val registry = StandaloneProjectRegistry(runtimeInfo, activity)
    private val stopped = AtomicBoolean()
    private val stoppedLatch = CountDownLatch(1)
    private val idleTimer = DaemonIdleTimer(activity, idleTimeoutMillis, idleRecheckMillis, ::close)

    fun start(projectDirs: List<File>): Int {
        require(projectDirs.isNotEmpty()) { "At least one project directory is required" }
        StandaloneDeployerResources.prepare()
        projectDirs.forEach(registry::initialize)
        McpLocalServer.start(idleTimer::recordExternalActivity)
        check(McpLocalServer.isRunning()) { "Failed to start standalone MCP server" }
        idleTimer.start()
        return McpLocalServer.getPort()
    }

    fun awaitTermination() {
        stoppedLatch.await()
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        idleTimer.close()
        McpLocalServer.stop()
        registry.close()
        stoppedLatch.countDown()
    }

    companion object {
        private fun defaultRuntimeInfo(): RuntimeInfo {
            return RuntimeInfo(
                runtimeType = "standalone",
                runtimeVersion = PluginInfoReader.getPluginVersion(),
                hostVersion = "java-${Runtime.version().feature()}",
                buildTime = PluginInfoReader.getPluginCompileTimestamp(),
            )
        }
    }
}

fun main(args: Array<String>) {
    runStandaloneDaemon(args)
}

internal fun runStandaloneDaemon(args: Array<String>) {
    val projectDirs = parseProjectDirs(args)
    val daemon = JuggDaemon()
    Runtime.getRuntime().addShutdownHook(Thread(daemon::close, "jugg-standalone-shutdown"))
    val port = daemon.start(projectDirs)
    println("Jugg standalone daemon started on port $port")
    daemon.awaitTermination()
}

private fun parseProjectDirs(args: Array<String>): List<File> {
    val projectDirs = mutableListOf<File>()
    var index = 0
    while (index < args.size) {
        val argument = args[index]
        when {
            argument == "--project-dir" -> {
                require(index + 1 < args.size) { "--project-dir requires a path" }
                projectDirs += File(args[++index])
            }
            argument.startsWith("--project-dir=") -> projectDirs += File(argument.substringAfter("="))
            else -> throw IllegalArgumentException("Unknown standalone argument: $argument")
        }
        index++
    }
    return projectDirs
}
