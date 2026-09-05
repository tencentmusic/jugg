package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File

class JuggJvmtiAgentManagerDirectSandboxTest {

    @Test
    fun `direct sandbox should receive app readable instrumentation jar`() {
        val adb = DirectAdb()
        val sandbox = AppSandboxExecutor(adb, "com.example.app", Mockito.mock(Logger::class.java))

        assertTrue(JuggJvmtiAgentManager(adb, Mockito.mock(Logger::class.java))
            .pushAgentToApp("com.example.app", sandbox))
        assertTrue(adb.scripts.any {
            it.contains("jugg-instruments.jar") &&
                it.contains("code_cache/startup_agents") &&
                it.contains("__JUGG_APP_SANDBOX_REPAIRED__")
        })
    }

    private class DirectAdb : IDeviceAdb {
        val scripts = mutableListOf<String>()
        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            return when {
                cmd.contains("find /data/local/tmp/jugg/") -> "success"
                cmd.startsWith("dumpsys package ") -> "dataDir=/data/user/0/com.example.app"
                else -> ""
            }
        }

        override fun execAdbShellScript(cmd: String): String {
            scripts += cmd
            return when {
                cmd.contains("__JUGG_RUN_AS_OK__") ->
                    "__JUGG_RUN_AS_OK__:1000\n__JUGG_RUN_AS_CONTEXT__:ctx|ctx"
                cmd.contains("__JUGG_DIRECT_SANDBOX_OK__") -> "__JUGG_DIRECT_SANDBOX_OK__"
                cmd.contains("jugg_jvmti_agent") -> "success"
                cmd.contains("jugg-instruments.jar") ->
                    "success\n__JUGG_APP_SANDBOX_REPAIR_START__\n__JUGG_APP_SANDBOX_REPAIRED__"
                else -> ""
            }
        }

        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }
}
