package com.sickworm.intellij.jugg.cmdline.standalone

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpc
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpToolCallResult
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.project.runtime.CliRunConfiguration
import com.sickworm.intellij.jugg.project.runtime.CliRunConfigurationStore
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the real standalone remote Gradle flow only when a maintainer injects an external environment. */
class StandaloneRemoteCompileFlowTest {

    @Test
    fun `standalone completes an injected remote Gradle build`() {
        val configPath = System.getenv(REMOTE_CONFIG_ENV).orEmpty()
        val projectPath = System.getenv(REMOTE_PROJECT_ENV).orEmpty()
        assumeTrue("$REMOTE_CONFIG_ENV is required for standalone remote L3", configPath.isNotBlank())
        assumeTrue("$REMOTE_PROJECT_ENV is required for standalone remote L3", projectPath.isNotBlank())

        val configFile = File(configPath)
        val projectDir = File(projectPath).canonicalFile
        assertTrue(configFile.isFile, "Remote config file does not exist: $configFile")
        assertTrue(projectDir.isDirectory, "Remote test project does not exist: $projectDir")

        val store = CliRunConfigurationStore(JuggPathManager(projectDir))
        val original = store.loadCurrent()
            ?: error("Run jugg init for $projectDir before the standalone remote L3 test")
        val injected = loadRemoteConfiguration(configFile, original)
        val registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "test", "java-11", "test"))
        try {
            store.save(injected)
            store.select(injected.id)
            registry.initialize(projectDir)
            val initResult = call(registry, "init", mapOf("projectDir" to projectDir.path))
            assertEquals("OK", initResult.structuredContent["status"])

            val buildResult = call(registry, "gradle-build", mapOf("projectDir" to projectDir.path))
            val buildData = buildResult.data()
            assertEquals("remote", buildData["executionType"])
            val finalData = if (buildData["status"] == "running") {
                waitForResult(registry, projectDir, buildData["jobId"].toString())
            } else {
                buildData
            }
            assertEquals("success", finalData["status"])
            assertEquals(true, finalData["isCompileSuccess"])
        } finally {
            try {
                registry.close()
            } finally {
                store.save(original)
                store.select(original.id)
            }
        }
    }

    private fun loadRemoteConfiguration(file: File, base: CliRunConfiguration): CliRunConfiguration {
        val json = JsonParser.parseString(file.readText()).asJsonObject
        return base.copy(
            compileCommand = json.requiredString("compileCommand"),
            outputApkName = json.requiredString("outputApkName"),
            isRemoteCompile = true,
            isSyncAllProjects = json.boolean("isSyncAllProjects", base.isSyncAllProjects),
            remoteSshUser = json.requiredString("remoteSshUser"),
            remoteSshPassword = json.string("remoteSshPassword", base.remoteSshPassword),
            remoteSshIp = json.requiredString("remoteSshIp"),
            remoteSshPort = json.requiredInt("remoteSshPort"),
            localToRemoteIftConfigName = json.string("localToRemoteIftConfigName", base.localToRemoteIftConfigName),
            localToRemoteSyncPath = json.string("localToRemoteSyncPath", base.localToRemoteSyncPath),
            remoteSyncPath = json.string("remoteSyncPath", base.remoteSyncPath),
            remoteToLocalIftConfigName = json.string("remoteToLocalIftConfigName", base.remoteToLocalIftConfigName),
            remoteToLocalSyncPath = json.string("remoteToLocalSyncPath", base.remoteToLocalSyncPath),
            httpProxyIp = json.string("httpProxyIp", base.httpProxyIp),
            httpProxyPort = json.int("httpProxyPort", base.httpProxyPort),
            syncMode = json.string("syncMode", SyncMode.RSYNC_SIMPLE.modeName),
            environmentVariables = json.string("environmentVariables", base.environmentVariables),
            remoteSyncExcludePatterns = json.string("remoteSyncExcludePatterns", base.remoteSyncExcludePatterns),
        )
    }

    private fun waitForResult(
        registry: StandaloneProjectRegistry,
        projectDir: File,
        jobId: String,
    ): Map<String, Any?> {
        val deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(REMOTE_BUILD_TIMEOUT_MINUTES)
        var lastResult: Map<String, Any?> = emptyMap()
        while (System.nanoTime() < deadlineNanos) {
            val result = call(
                registry,
                "get-compile-status",
                mapOf("projectDir" to projectDir.path, "jobId" to jobId, "waitTimeoutMs" to 5_000),
            ).data()
            if (result["status"] != "running") {
                return result
            }
            lastResult = result
        }
        error("Standalone remote build timed out after $REMOTE_BUILD_TIMEOUT_MINUTES minutes: $lastResult")
    }

    private fun call(
        registry: StandaloneProjectRegistry,
        toolName: String,
        arguments: Map<String, Any?>,
    ): McpToolCallResult {
        val response = registry.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 1,
                params = mapOf("name" to toolName, "arguments" to arguments),
            )
        )
        return response.result as McpToolCallResult
    }

    @Suppress("UNCHECKED_CAST")
    private fun McpToolCallResult.data(): Map<String, Any?> {
        return structuredContent["data"] as Map<String, Any?>
    }

    private fun JsonObject.requiredString(name: String): String {
        return get(name)?.asString?.takeIf { it.isNotBlank() }
            ?: error("Remote config field is required: $name")
    }

    private fun JsonObject.requiredInt(name: String): Int {
        return get(name)?.asInt ?: error("Remote config field is required: $name")
    }

    private fun JsonObject.string(name: String, defaultValue: String): String =
        get(name)?.asString ?: defaultValue

    private fun JsonObject.int(name: String, defaultValue: Int): Int =
        get(name)?.asInt ?: defaultValue

    private fun JsonObject.boolean(name: String, defaultValue: Boolean): Boolean =
        get(name)?.asBoolean ?: defaultValue

    companion object {
        private const val REMOTE_CONFIG_ENV = "JUGG_REMOTE_CONFIG_FILE"
        private const val REMOTE_PROJECT_ENV = "JUGG_STANDALONE_REMOTE_PROJECT_DIR"
        private const val REMOTE_BUILD_TIMEOUT_MINUTES = 30L
    }
}
