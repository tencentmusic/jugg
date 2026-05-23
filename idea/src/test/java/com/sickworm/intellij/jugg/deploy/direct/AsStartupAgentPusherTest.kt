package com.sickworm.intellij.jugg.deploy.direct

import com.android.tools.deploy.proto.Deploy
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AsStartupAgentPusherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val logger = TestGlobal.getLogger()

    @Test
    fun `hasApplyChangesStartupAgent should detect versioned as agent file`() {
        val adb = RecordingAdb(startupAgents = listOf("dced2491-agent.so"))
        val pusher = newPusher(adb)

        assertTrue(pusher.hasApplyChangesStartupAgent("com.example.app", "arm64-v8a", Deploy.Arch.ARCH_64_BIT))
    }

    @Test
    fun `hasApplyChangesStartupAgent should ignore jugg agent only`() {
        val adb = RecordingAdb(startupAgents = listOf("1.0.27-jugg_jvmti_agent.so"))
        val pusher = newPusher(adb)

        assertFalse(pusher.hasApplyChangesStartupAgent("com.example.app", "arm64-v8a", Deploy.Arch.ARCH_64_BIT))
    }

    @Test
    fun `pushApplyChangesStartupAgent should push agent doll for offline app`() {
        val root = tempFolder.newFolder("installers")
        val agentBytes = byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x02)
        writeInstaller(root, agentBytes)
        val adb = RecordingAdb(startupAgents = emptyList())
        val pusher = newPusher(adb, installersRoot = root.absolutePath)

        pusher.pushApplyChangesStartupAgent("com.example.app", "arm64-v8a", Deploy.Arch.ARCH_64_BIT)

        assertTrue(adb.pushedPaths.any { it.contains("/data/local/tmp/jugg/as-agent/dced2491/agent.so") })
        assertTrue(
            adb.shellScripts.any {
                it.contains("run-as com.example.app") &&
                    it.contains("code_cache/startup_agents/dced2491-agent.so")
            },
        )
    }

    @Test
    fun `pushApplyChangesStartupAgent should use agent-alt on 64 bit device with 32 bit app arch`() {
        val root = tempFolder.newFolder("installers")
        val altBytes = byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x01)
        writeArm64Installer(root, altBytes)
        val adb = RecordingAdb(startupAgents = emptyList())
        val pusher = newPusher(adb, installersRoot = root.absolutePath)

        pusher.pushApplyChangesStartupAgent("com.example.app", "arm64-v8a", Deploy.Arch.ARCH_32_BIT)

        assertTrue(adb.pushedPaths.any { it.endsWith("/agent-alt.so") })
        assertTrue(
            adb.shellScripts.any { it.contains("code_cache/startup_agents/dced2491-agent-alt.so") },
        )
    }

    @Test(expected = DirectOverlayDeployFailedException::class)
    fun `pushApplyChangesStartupAgent should throw when run-as cp fails`() {
        val root = tempFolder.newFolder("installers")
        writeInstaller(root, byteArrayOf(0x01, 0x02, 0x03))
        val adb = RecordingAdb(startupAgents = emptyList(), failRunAsCopy = true)
        val pusher = newPusher(adb, installersRoot = root.absolutePath)

        pusher.pushApplyChangesStartupAgent("com.example.app", "arm64-v8a", Deploy.Arch.ARCH_64_BIT)
    }

    @Test
    fun `pushApplyChangesStartupAgent should mkdir remote agent dir before adb push`() {
        val root = tempFolder.newFolder("installers")
        writeInstaller(root, byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x02))
        val adb = RecordingAdb(startupAgents = emptyList(), requireRemoteDirBeforePush = true)
        val pusher = newPusher(adb, installersRoot = root.absolutePath)

        pusher.pushApplyChangesStartupAgent("com.example.app", "arm64-v8a", Deploy.Arch.ARCH_64_BIT)

        assertTrue(
            adb.shellCommands.any { it == "mkdir -p /data/local/tmp/jugg/as-agent/dced2491" },
        )
        assertTrue(adb.pushedPaths.any { it.endsWith("/data/local/tmp/jugg/as-agent/dced2491/agent.so") })
    }

    @Test
    fun `pushApplyChangesStartupAgent should setup studio dir and remove stale startup agents`() {
        val root = tempFolder.newFolder("installers")
        writeInstaller(root, byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x02))
        val adb = RecordingAdb(startupAgents = listOf("oldversion-agent.so"))
        val pusher = newPusher(adb, installersRoot = root.absolutePath)

        pusher.pushApplyChangesStartupAgent("com.example.app", "arm64-v8a", Deploy.Arch.ARCH_64_BIT)

        assertTrue(
            adb.shellScripts.any {
                it.contains("code_cache/.studio") &&
                    it.contains("rm -rf code_cache/startup_agents") &&
                    it.contains("code_cache/startup_agents/dced2491-agent.so")
            },
        )
    }

    private fun newPusher(
        adb: RecordingAdb,
        installersRoot: String = tempFolder.newFolder("empty").absolutePath,
    ): AsStartupAgentPusher {
        return AsStartupAgentPusher(
            adb = adb,
            matryoshkaReader = InstallerMatryoshkaReader(installersRoot, logger),
            versionHash = "dced2491",
            logger = logger,
        )
    }

    private fun writeInstaller(root: File, agentBytes: ByteArray) {
        val abiDir = File(root, "arm64-v8a").also { it.mkdirs() }
        val installer = File(abiDir, "installer")
        installer.writeBytes(byteArrayOf(0x7f))
        MatryoshkaFixtureWriter.appendMatryoshka(
            installer,
            linkedMapOf("agent.so" to agentBytes, "version" to "dced2491".toByteArray()),
        )
    }

    private fun writeArm64Installer(root: File, altBytes: ByteArray) {
        val abiDir = File(root, "arm64-v8a").also { it.mkdirs() }
        val installer = File(abiDir, "installer")
        installer.writeBytes(byteArrayOf(0x7f))
        MatryoshkaFixtureWriter.appendMatryoshka(
            installer,
            linkedMapOf(
                "agent-alt.so" to altBytes,
                "agent.so" to byteArrayOf(0x01),
                "version" to "dced2491".toByteArray(),
            ),
        )
    }

    private class RecordingAdb(
        private val startupAgents: List<String>,
        private val failRunAsCopy: Boolean = false,
        private val requireRemoteDirBeforePush: Boolean = false,
    ) : IDeviceAdb {
        val pushedPaths = mutableListOf<String>()
        val shellCommands = mutableListOf<String>()
        val shellScripts = mutableListOf<String>()
        private val createdRemoteDirs = mutableSetOf<String>()

        override val displayName: String? = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            shellCommands += cmd
            if (cmd.startsWith("mkdir -p ")) {
                createdRemoteDirs += cmd.removePrefix("mkdir -p ").trim()
            }
            return when {
                cmd.contains("startup_agents") && startupAgents.isEmpty() ->
                    "No such file or directory"
                cmd.contains("startup_agents") -> startupAgents.joinToString("\n")
                else -> ""
            }
        }

        override fun execAdbShellScript(cmd: String): String {
            shellScripts += cmd
            return if (failRunAsCopy) {
                "__JUGG_AS_AGENT__ FAILED"
            } else {
                "__JUGG_AS_AGENT__ OK"
            }
        }

        override fun push(from: File, to: String): Boolean {
            if (requireRemoteDirBeforePush) {
                val parent = to.substringBeforeLast('/')
                if (parent !in createdRemoteDirs) {
                    return false
                }
            }
            pushedPaths += to
            return true
        }

        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_UNKNOWN"
        override fun getProperty(name: String): String? = null
    }
}
