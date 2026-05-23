package com.sickworm.intellij.jugg.deploy.run.deployflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VirtualDeployShellExecutorTest {

    @Test
    fun `parseRunAsShC should extract package and inner script`() {
        val parsed = VirtualDeployShellExecutor.parseRunAsShC(
            "run-as com.example.app sh -c 'echo hello'",
        )

        assertTrue(parsed != null)
        assertTrue(parsed!!.first == "com.example.app")
        assertTrue(parsed.second == "echo hello")
    }

    @Test
    fun `wrapLikeIdeaDeviceAdb should mirror IdeaDeviceAdb sh -c escaping`() {
        val wrapped = VirtualDeployShellExecutor.wrapLikeIdeaDeviceAdb(
            "run-as com.example.app sh -c 'mkdir -p code_cache/.studio'",
        )

        assertTrue(wrapped.startsWith("sh -c '"))
        assertTrue(wrapped.endsWith("'"))
        assertTrue(wrapped.contains("run-as com.example.app sh -c "))
        assertTrue(wrapped.contains("mkdir -p code_cache/.studio"))
    }

    @Test
    fun `executeRunAsInner should run valid setup agent script on virtual fs`() {
        val device = VirtualDeployDevice("com.example.app")
        val remotePath = "/data/local/tmp/jugg/as-agent/dced2491/agent.so"
        val remoteAgent = File(device.root, "data/local/tmp/jugg/as-agent/dced2491/agent.so")
        remoteAgent.parentFile?.mkdirs()
        remoteAgent.writeBytes(byteArrayOf(0x01, 0x02))

        val script = listOf(
            "mkdir -p code_cache/.studio",
            "mkdir -p code_cache/startup_agents",
            "cp -f $remotePath code_cache/startup_agents/dced2491-agent.so",
            "echo __JUGG_AS_AGENT__ OK",
        ).joinToString(" && ")

        val output = VirtualDeployShellExecutor.executeRunAsInner(device, script)

        assertTrue("output=$output", output.contains("__JUGG_AS_AGENT__ OK"))
        assertTrue(device.studioDir().isDirectory)
        assertTrue(device.listStartupAgents().contains("dced2491-agent.so"))
    }

    @Test
    fun `executeRunAsInner should surface shell syntax errors for invalid backslash continuations`() {
        val device = VirtualDeployDevice("com.example.app")
        val script = "mkdir -p code_cache/.studio && \\ if [ -d code_cache/startup_agents ]; then rm -rf code_cache/startup_agents; fi"

        val output = VirtualDeployShellExecutor.executeRunAsInner(device, script)

        assertTrue(
            "expected host sh syntax error, got: $output",
            output.contains("syntax error") || output.contains("unexpected"),
        )
        assertFalse(output.contains("__JUGG_AS_AGENT__ OK"))
    }

    @Test
    fun `virtual device adb script should record IdeaDeviceAdb outer sh -c wrapper`() {
        val device = VirtualDeployDevice("com.example.app")
        val adb = device.asIDeviceAdb()

        adb.execAdbShellScript("run-as ${device.packageName} sh -c 'echo __JUGG_AS_AGENT__ OK'")

        assertTrue(
            device.shellCommands.any {
                it.startsWith("sh -c '") && it.contains("run-as ${device.packageName} sh -c ")
            },
        )
    }
}
