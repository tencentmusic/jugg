package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.mock.RequiresDevice
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RequiresDevice
class JuggJvmtiAgentManagerTest {

    @Test
    fun test() {
        val adb = CmdAdb(logger)

//        adb.install(projectInfo.apkFile)

        val agentName = "${BuildConfig.AGENT_VERSION}-jugg_jvmti_agent.so"
        val agentDestPath = "code_cache/startup_agents/$agentName"
//        val result = adb.execAdbShellCmd("run-as ${projectInfo.packageName} ls $agentDestPath")
//        assertTrue(result.contains("No such file or directory"))

        val manager = JuggJvmtiAgentManager(adb, logger)
        val result2 = manager.pushAgentToApp(projectInfo.packageName)
        assertTrue(result2)
        val result3 = adb.execAdbShellCmd("run-as ${projectInfo.packageName} ls $agentDestPath")
        assertFalse(result3.contains("No such file or directory"))
        assertTrue(result3.contains(agentName))
    }
}