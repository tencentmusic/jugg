package com.sickworm.intellij.jugg.deploy.direct

import com.android.tools.deploy.proto.Deploy
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.JuggJvmtiAgentManager
import java.io.File

/**
 * Pushes Android Studio Apply Changes startup agent via host matryoshka + run-as cp.
 *
 * Unlike [com.sickworm.intellij.jugg.deploy.JuggJvmtiAgentManager.setupAgent], this path does not
 * require the app process to be online: app bitness comes from the caller (pids or APK fallback),
 * and device installer ABI comes from [InstallerDeviceAbiResolver].
 */
class AsStartupAgentPusher(
    private val adb: IDeviceAdb,
    private val matryoshkaReader: InstallerMatryoshkaReader,
    private val versionHash: String,
    private val logger: Logger,
) {

    fun hasApplyChangesStartupAgent(
        packageName: String,
        deviceAbi: String,
        appArch: Deploy.Arch,
    ): Boolean {
        val expected = agentDestFileName(deviceAbi, appArch)
        val agents = JuggJvmtiAgentManager(adb, logger).getCurrentAgentsInApp(packageName)
        logger.debug("hasApplyChangesStartupAgent agents=$agents expected=$expected")
        return agents.any { it == expected }
    }

    fun pushApplyChangesStartupAgent(
        packageName: String,
        deviceAbi: String,
        appArch: Deploy.Arch,
    ) {
        if (hasApplyChangesStartupAgent(packageName, deviceAbi, appArch)) {
            logger.debug("Apply Changes startup agent already present for $packageName")
            return
        }
        val dollName = InstallerAgentDollNames.resolve(deviceAbi, appArch)
        val agentBytes = matryoshkaReader.extractAgentSo(deviceAbi, appArch)
        val remoteDir = "$REMOTE_AGENT_ROOT/$versionHash"
        val remoteAgentPath = "$remoteDir/$dollName"
        val localAgentFile = writeTempAgentFile(dollName, agentBytes)
        try {
            if (!adb.push(localAgentFile, remoteAgentPath)) {
                fail("adb push failed for $remoteAgentPath")
            }
            copyAgentIntoAppSandbox(packageName, remoteAgentPath, agentDestFileName(deviceAbi, appArch))
            logger.info("Pushed Apply Changes startup agent $dollName for $packageName")
        } finally {
            localAgentFile.parentFile?.deleteRecursively()
        }
    }

    private fun writeTempAgentFile(dollName: String, agentBytes: ByteArray): File {
        val tempDir = createTempDirectory("jugg-as-agent-")
        val localAgentFile = File(tempDir, dollName)
        localAgentFile.writeBytes(agentBytes)
        return localAgentFile
    }

    private fun copyAgentIntoAppSandbox(
        packageName: String,
        remoteAgentPath: String,
        destFileName: String,
    ) {
        val destPath = "$STARTUP_AGENTS_DIR/$destFileName"
        val script = """
            mkdir -p $STARTUP_AGENTS_DIR && \
            cp -f $remoteAgentPath $destPath && \
            echo $AGENT_MARKER OK
        """.trimIndent().replace("\n", " ")
        val result = adb.execAdbShellScript("run-as $packageName sh -c '$script'")
        if (!result.contains("$AGENT_MARKER OK")) {
            fail("run-as cp failed for $destPath, output: ${result.trim()}")
        }
    }

    private fun agentDestFileName(deviceAbi: String, appArch: Deploy.Arch): String {
        val dollName = InstallerAgentDollNames.resolve(deviceAbi, appArch)
        return "$versionHash-$dollName"
    }

    private fun fail(message: String): Nothing {
        val detail = "Direct overlay deploy failed: $message"
        logger.warn(detail)
        throw DirectOverlayDeployFailedException(detail)
    }

    private fun createTempDirectory(prefix: String): File {
        val directory = File.createTempFile(prefix, "")
        if (!directory.delete() || !directory.mkdir()) {
            error("Failed to create temp directory with prefix $prefix")
        }
        return directory
    }

    companion object {
        private const val REMOTE_AGENT_ROOT = "/data/local/tmp/jugg/as-agent"
        private const val STARTUP_AGENTS_DIR = "code_cache/startup_agents"
        private const val AGENT_MARKER = "__JUGG_AS_AGENT__"
    }
}
