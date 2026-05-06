package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JuggJvmtiAgentManagerHelperFilterTest {

    private val appApk = ApkInfo(
        files = listOf(ApkFileUnit("com.example.app", "", true, File("app.apk"))),
        applicationId = "com.example.app",
    )
    private val appTestApk = ApkInfo(
        files = listOf(ApkFileUnit("com.example.app.test", "", true, File("app-test.apk"))),
        applicationId = "com.example.app.test",
        instrumentationTargetPackage = "com.example.app",
    )
    private val libTestApk = ApkInfo(
        files = listOf(ApkFileUnit("com.example.lib.test", "", true, File("lib-test.apk"))),
        applicationId = "com.example.lib.test",
        instrumentationTargetPackage = "com.example.lib.test",
    )

    /** Fake adb that records queried packages and returns controlled agent states. */
    private class RecordingAdb(
        private val agentStates: Map<String, String>,
    ) : IDeviceAdb {
        val queriedPackages = mutableListOf<String>()

        private fun extractPackageName(cmd: String): String? {
            val runAsMatch = Regex("""run-as (\S+)""").find(cmd)
            if (runAsMatch != null) return runAsMatch.groupValues[1].trim('"')
            val attachMatch = Regex("""am attach-agent (\S+)""").find(cmd)
            if (attachMatch != null) return attachMatch.groupValues[1]
            return null
        }

        override fun execAdbShellCmd(cmd: String): String {
            extractPackageName(cmd)?.let { queriedPackages.add(it) }
            val matchingPkg = agentStates.keys.firstOrNull { cmd.contains(it) }
            if (matchingPkg != null) {
                return agentStates[matchingPkg]!!
            }
            return "success"
        }

        override fun execAdbShellCmdStreaming(
            cmd: String, lineConsumer: (String) -> Unit, cancelSignal: () -> Boolean,
        ): Int = 0

        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getArch(packageName: String): String = "arm64-v8a"
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getProperty(name: String): String? = null
        override val displayName: String? get() = "test_device"
        override val api: Int get() = 34
        override val serial: String get() = "test_serial"
        override val isOnline: Boolean get() = true
    }

    @Test
    fun `isNeedPushAgentAfterDeploy skips other-targeting test apk when base has agent`() {
        val adb = RecordingAdb(mapOf(
            appApk.applicationId to "1.0.27-jugg_jvmti_agent.so\napplychanges_jvmti_agent.so",
            libTestApk.applicationId to "1.0.27-jugg_jvmti_agent.so\napplychanges_jvmti_agent.so",
            // appTestApk not in the map - would throw if queried
        ))
        val data = JuggDeployData.forDryDeploy(listOf(appApk, appTestApk, libTestApk))
        val helper = JuggJvmtiAgentManagerHelper(logger)

        val result = helper.isNeedPushAgentAfterDeploy(adb, data)

        assertFalse(result)
        assertFalse(adb.queriedPackages.contains(appTestApk.applicationId))
        assertTrue(adb.queriedPackages.contains(appApk.applicationId))
        assertTrue(adb.queriedPackages.contains(libTestApk.applicationId))
    }

    @Test
    fun `isNeedPushAgentAfterDeploy still detects need when base apk lacks agent`() {
        val adb = RecordingAdb(mapOf(
            appApk.applicationId to "",  // no agents
            libTestApk.applicationId to "1.0.27-jugg_jvmti_agent.so\napplychanges_jvmti_agent.so",
        ))
        val data = JuggDeployData.forDryDeploy(listOf(appApk, appTestApk, libTestApk))
        val helper = JuggJvmtiAgentManagerHelper(logger)

        val result = helper.isNeedPushAgentAfterDeploy(adb, data)

        assertTrue(result)
        assertFalse(adb.queriedPackages.contains(appTestApk.applicationId))
    }

    @Test
    fun `pushAgentToApps skips other-targeting test apk`() {
        val adb = RecordingAdb(mapOf(
            appApk.applicationId to "0.0.1-jugg_jvmti_agent.so",
            libTestApk.applicationId to "0.0.1-jugg_jvmti_agent.so",
        ))
        val data = JuggDeployData.forDryDeploy(listOf(appApk, appTestApk, libTestApk))
        val helper = JuggJvmtiAgentManagerHelper(logger)

        helper.pushAgentToApps(adb, data)

        assertFalse(adb.queriedPackages.contains(appTestApk.applicationId))
        assertTrue(adb.queriedPackages.contains(appApk.applicationId))
        assertTrue(adb.queriedPackages.contains(libTestApk.applicationId))
    }

    @Test
    fun `attachAgentToApps skips other-targeting test apk`() {
        val adb = RecordingAdb(emptyMap())
        val data = JuggDeployData.forDryDeploy(listOf(appApk, appTestApk, libTestApk))
        val helper = JuggJvmtiAgentManagerHelper(logger)

        helper.attachAgentToApps(adb, data)

        assertFalse(adb.queriedPackages.contains(appTestApk.applicationId))
        assertTrue(adb.queriedPackages.contains(appApk.applicationId))
        assertTrue(adb.queriedPackages.contains(libTestApk.applicationId))
    }

}
