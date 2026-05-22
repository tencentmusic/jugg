package com.sickworm.intellij.jugg.deploy.direct

import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.OverlayId
import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.applychanges.OverlayUpdateBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File

class DirectOverlaySwapTransportTest {

    private val logger = Mockito.mock(Logger::class.java)
    private val adb = RecordingAdb()

    @Test
    fun `trySwap should write overlay and return overlay id when direct path is eligible`() {
        val entry = cacheEntry()
        adb.overlayStateId = entry.overlayId.sha
        val data = deployData(apkInfo("com.example.app", "/base.apk"))
        val overlayUpdate = OverlayUpdateBuilder().build(entry, data)

        val overlayId = newTransport().trySwap(
            packageName = "com.example.app",
            data = data,
            overlayUpdate = overlayUpdate,
        )

        assertNotNull(overlayId)
        assertTrue(adb.commands.contains("rm -f /data/local/tmp/jugg/direct-overlay-*.zip"))
        assertFalse(overlayId!!.sha.isEmpty())
    }

    @Test
    fun `trySwap should fall back when device is ready for regular apply changes`() {
        val entry = cacheEntry()
        adb.overlayStateId = entry.overlayId.sha
        val data = deployData(apkInfo("com.example.app", "/base.apk"))
        val overlayUpdate = OverlayUpdateBuilder().build(entry, data)

        val overlayId = newTransport(isDeviceReadyDeploy = true).trySwap(
            packageName = "com.example.app",
            data = data,
            overlayUpdate = overlayUpdate,
        )

        assertNull(overlayId)
        assertTrue(adb.commands.isEmpty())
    }

    @Test
    fun `trySwap should fall back when device overlay id does not match cache`() {
        val entry = cacheEntry()
        adb.overlayStateId = "different-overlay"
        val data = deployData(apkInfo("com.example.app", "/base.apk"))
        val overlayUpdate = OverlayUpdateBuilder().build(entry, data)

        val overlayId = newTransport().trySwap(
            packageName = "com.example.app",
            data = data,
            overlayUpdate = overlayUpdate,
        )

        assertNull(overlayId)
    }

    @Test
    fun `trySwap should fall back when startup agent is missing`() {
        val entry = cacheEntry()
        adb.overlayStateId = entry.overlayId.sha
        adb.startupAgentsAvailable = false
        val data = deployData(apkInfo("com.example.app", "/base.apk"))
        val overlayUpdate = OverlayUpdateBuilder().build(entry, data)

        val overlayId = newTransport().trySwap(
            packageName = "com.example.app",
            data = data,
            overlayUpdate = overlayUpdate,
        )

        assertNull(overlayId)
        assertTrue(adb.commands.none { it.contains("direct-overlay") })
    }

    @Test
    fun `trySwap should fall back when cache is base install`() {
        val entry = cacheEntry(isBaseInstall = true)
        adb.overlayStateId = ""
        val data = deployData(apkInfo("com.example.app", "/base.apk"))
        val overlayUpdate = OverlayUpdateBuilder().build(entry, data)

        val overlayId = newTransport().trySwap(
            packageName = "com.example.app",
            data = data,
            overlayUpdate = overlayUpdate,
        )

        assertNull(overlayId)
        assertTrue(adb.commands.isEmpty())
    }

    @Test
    fun `trySwap should fall back when direct path throws`() {
        val entry = cacheEntry()
        adb.overlayStateId = entry.overlayId.sha
        adb.throwOnOverlayStateCheck = true
        val data = deployData(apkInfo("com.example.app", "/base.apk"))
        val overlayUpdate = OverlayUpdateBuilder().build(entry, data)

        val overlayId = newTransport().trySwap(
            packageName = "com.example.app",
            data = data,
            overlayUpdate = overlayUpdate,
        )

        assertNull(overlayId)
    }

    @Test(expected = DirectOverlayDirtyException::class)
    fun `trySwap should not fall back when writer leaves overlay dirty`() {
        val entry = cacheEntry()
        adb.overlayStateId = entry.overlayId.sha
        adb.directOverlayResponse = "__JUGG_DIRECT_OVERLAY__ APPLYING"
        val data = deployData(apkInfo("com.example.app", "/base.apk"))
        val overlayUpdate = OverlayUpdateBuilder().build(entry, data)

        newTransport().trySwap(
            packageName = "com.example.app",
            data = data,
            overlayUpdate = overlayUpdate,
        )
    }

    private fun newTransport(isDeviceReadyDeploy: Boolean = false): DirectOverlaySwapTransport {
        return DirectOverlaySwapTransport(
            options = DirectOverlaySwapOptions(
                enabled = true,
                isDeviceReadyDeploy = isDeviceReadyDeploy,
                adb = adb,
            ),
            logger = logger,
        )
    }

    private fun deployData(vararg apkInfos: ApkInfo): JuggDeployData {
        return JuggDeployData(
            apks = apkInfos.toList(),
            newClasses = emptyList(),
            hotFixModifiedClasses = emptyList(),
            hotReloadModifiedClasses = emptyList(),
            effectedClassNodes = emptyList(),
            overlays = listOf(
                DeployItem(
                    name = "res/layout/main.xml",
                    type = CompileOutput.Type.Res,
                    checksum = 1L,
                    content = byteArrayOf(1),
                    apkPath = apkInfos.first().files.first().apkFile.path,
                )
            ),
            parsedDex = com.sickworm.intellij.jugg.deploy.data.ParsedDex.EMPTY,
            isFullRes = false,
            isWarmUp = false,
            isPushOverlayOnly = true,
        )
    }

    private fun apkInfo(applicationId: String, apkPath: String): ApkInfo {
        return ApkInfo(
            files = listOf(ApkFileUnit(applicationId, "", true, File(apkPath))),
            applicationId = applicationId,
        )
    }

    private fun cacheEntry(isBaseInstall: Boolean = false): DeploymentCacheDatabase.Entry {
        val apk = apk("base.apk", "/base.apk", "com.example.app")
        val baseOverlayId = OverlayId(listOf(apk))
        val overlayId = if (isBaseInstall) {
            baseOverlayId
        } else {
            OverlayId.builder(baseOverlayId).addOverlayFile("base.apk/res/layout/old.xml", 1L).build()
        }
        val constructor = DeploymentCacheDatabase.Entry::class.java
            .getDeclaredConstructor(java.util.List::class.java, OverlayId::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(listOf(apk), overlayId)
    }

    private fun apk(name: String, path: String, packageName: String): Apk {
        val constructor = Apk::class.java.declaredConstructors.firstOrNull { it.parameterCount == 10 }
            ?: error(Apk::class.java.declaredConstructors.joinToString("\n") { it.toGenericString() })
        constructor.isAccessible = true
        val args = constructor.parameterTypes.map { type ->
            when {
                type == String::class.java -> null
                java.util.List::class.java.isAssignableFrom(type) -> emptyList<String>()
                java.util.Map::class.java.isAssignableFrom(type) -> emptyMap<String, ApkEntry>()
                else -> null
            }
        }.toMutableList()
        args[0] = name
        args[1] = "checksum"
        args[2] = path
        args[3] = packageName
        return constructor.newInstance(*args.toTypedArray()) as Apk
    }

    private class RecordingAdb : IDeviceAdb {
        val commands = mutableListOf<String>()
        var overlayStateId: String = ""
        var startupAgentsAvailable: Boolean = true
        var throwOnOverlayStateCheck: Boolean = false
        var directOverlayResponse: String = "__JUGG_DIRECT_OVERLAY__ OK"

        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            commands += cmd
            return when {
                cmd.contains("startup_agents") && startupAgentsAvailable ->
                    "1.0.27-jugg_jvmti_agent.so\napplychanges_jvmti_agent.so"
                cmd.contains("startup_agents") ->
                    "No such file or directory"
                else -> ""
            }
        }

        override fun execAdbShellScript(cmd: String): String {
            commands += cmd
            if (throwOnOverlayStateCheck && cmd.contains("__JUGG_OVERLAY_STATE__")) {
                throw IllegalStateException("boom")
            }
            return when {
                cmd.contains("__JUGG_OVERLAY_STATE__") -> "__JUGG_OVERLAY_STATE__ ID $overlayStateId"
                cmd.contains("__JUGG_DIRECT_OVERLAY__") -> directOverlayResponse
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
