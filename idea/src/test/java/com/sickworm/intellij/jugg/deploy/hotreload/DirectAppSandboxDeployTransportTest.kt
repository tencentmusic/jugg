package com.sickworm.intellij.jugg.deploy.hotreload

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.DexComparator
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.idea.protobuf.ByteString
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.deploy.DirectHotReloadClass
import com.sickworm.intellij.jugg.deploy.DirectHotReloadResult
import com.sickworm.intellij.jugg.deploy.DirectHotReloadWriter
import com.sickworm.intellij.jugg.deploy.JuggJvmtiAgentManager
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateCheckResult
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateChecker
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayWriteRequest
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayWriteResult
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayWriter
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayDeployFailedException
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggInstallSession
import com.sickworm.intellij.jugg.deploy.run.LaunchContext
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentCacheEntry
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayId
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.io.File

class DirectAppSandboxDeployTransportTest {

    @Test
    fun `run-as compatible app should keep Android Studio deploy path`() {
        val result = transport(CompatibleAdb()).tryDeploy(
            packageName = "com.example.app",
            data = classData(),
            overlayUpdate = null,
            asDeployerCompat = Mockito.mock(IAsDeployerCompat::class.java),
            pids = emptyList(),
            appArch = Deploy.Arch.ARCH_64_BIT,
        )

        assertNull(result)
    }

    @Test
    fun `resource-only deploy refreshes resources without process restart`() {
        assertDirectDeploy(resourceData(), needsRestart = false, expectedRefreshResources = true)
    }

    @Test
    fun `method and resource changes apply together without process restart`() {
        assertDirectDeploy(
            classData().copy(overlays = resourceData().overlays),
            needsRestart = false,
            expectedRefreshResources = true,
        )
    }

    @Test
    fun `pure method changes remain online after overlay commit`() {
        assertDirectDeploy(classData(), needsRestart = false)
    }

    @Test
    fun `new classes remain online after overlay commit`() {
        assertDirectDeploy(newClassData(), needsRestart = false)
    }

    @Test
    fun `empty payload completes Direct Apply Changes without restart`() {
        assertDirectDeploy(
            JuggDeployData.forDryDeploy(emptyList()).copy(isPushOverlayOnly = false),
            needsRestart = false,
        )
    }

    @Test
    fun `missing modified class explains why committed overlay needs restart`() {
        val logger = Mockito.mock(Logger::class.java)

        listOf("CLASS_NOT_FOUND", "MISSING").forEach { result ->
            assertDirectDeploy(
                classData(),
                needsRestart = true,
                hotReloadResult = DirectHotReloadResult(false, "$result\tLcom/example/Foo;"),
                logger = logger,
            )
        }

        Mockito.verify(logger, Mockito.times(2)).info(
            "Direct app sandbox could not redefine com.example.Foo because it is not loaded in the running " +
                "process; restarting the app to activate the committed class overlay.",
        )
    }

    @Test
    fun `new class definition failure explains why committed overlay needs restart`() {
        val logger = Mockito.mock(Logger::class.java)

        assertDirectDeploy(
            newClassData(),
            needsRestart = true,
            hotReloadResult = DirectHotReloadResult(
                false,
                "ERROR\tdefine_new_classes\t0\tdefining new classes failed: " +
                    "java.lang.NoSuchFieldException: mLoadedApk",
            ),
            logger = logger,
        )

        Mockito.verify(logger).info(
            "Direct app sandbox could not add new classes to the running process; restarting the app to activate " +
                "the committed class overlay. Cause: java.lang.NoSuchFieldException: mLoadedApk",
        )
    }

    @Test
    fun `Activity relaunch failure falls back to process restart`() {
        assertDirectDeploy(
            classData(),
            needsRestart = true,
            hotReloadResult = DirectHotReloadResult(false, "ERROR\trestart_activity\t0\tActivity relaunch failed"),
        )
    }

    @Test
    fun `full resource replay preserves full push semantics and refreshes resources`() {
        assertDirectDeploy(
            resourceData().copy(isFullRes = true),
            needsRestart = false,
            expectedRefreshResources = true,
        )
    }

    @Test
    fun `resource refresh failure falls back to process restart`() {
        assertDirectDeploy(
            resourceData(),
            needsRestart = true,
            hotReloadResult = DirectHotReloadResult(false, "ERROR\trefresh_resources\t0\tRefresh failed"),
            expectedRefreshResources = true,
        )
    }

    @Test
    fun `overlay-only class replay must restart even when methods are redefinable`() {
        assertDirectDeploy(
            classData().copy(isPushOverlayOnly = true),
            needsRestart = true,
            expectRuntimeApply = false,
        )
    }

    @Test
    fun `reinstall replay and compat class payloads must restart`() {
        assertDirectDeploy(
            classData().copy(isRecoverReplayAfterReinstall = true),
            needsRestart = true,
            expectRuntimeApply = false,
        )
        assertDirectDeploy(
            classData().copy(isCompatDeploy = true),
            needsRestart = true,
            expectRuntimeApply = false,
        )
    }

    @Test
    fun `APK updates must not be treated as pure method reload`() {
        assertDirectDeploy(classData().copy(updateApkFiles = listOf(
            DeployItem("AndroidManifest.xml", CompileOutput.Type.Res, 1, byteArrayOf(8), "base.apk"),
            DeployItem("lib/arm64-v8a/libdemo.so", CompileOutput.Type.NativeLib, 2, byteArrayOf(9), "base.apk"),
        )), needsRestart = true, expectRuntimeApply = false)
    }

    @Test
    fun `run-as incompatible class payload should require Direct Deploy cache`() {
        val error = captureFailure {
            transport(DirectAdb()).tryDeploy(
                packageName = "com.example.app",
                data = classData(),
                overlayUpdate = null,
                asDeployerCompat = Mockito.mock(IAsDeployerCompat::class.java),
                pids = emptyList(),
                appArch = Deploy.Arch.ARCH_64_BIT,
            )
        }

        assertTrue(error.message.orEmpty().contains("existing deployment cache"))
    }

    private fun captureFailure(block: () -> Unit): DirectOverlayDeployFailedException {
        return try {
            block()
            throw AssertionError("Expected DirectOverlayDeployFailedException")
        } catch (e: DirectOverlayDeployFailedException) {
            e
        }
    }

    private fun resourceData() = JuggDeployData.forDryDeploy(emptyList()).copy(
        overlays = listOf(DeployItem("resources.arsc", CompileOutput.Type.Res, 1, byteArrayOf(7), "base.apk")),
        isPushOverlayOnly = false,
    )

    private fun assertDirectDeploy(
        data: JuggDeployData,
        needsRestart: Boolean,
        hotReloadResult: DirectHotReloadResult = DirectHotReloadResult(true, "OK"),
        expectedRefreshResources: Boolean = false,
        expectRuntimeApply: Boolean = true,
        logger: Logger = Mockito.mock(Logger::class.java),
    ) {
        val compat = Mockito.mock(IAsDeployerCompat::class.java)
        val baseId = JuggOverlayId(Any(), "base", false)
        val nextId = JuggOverlayId(Any(), "next", false)
        whenever(compat.buildOverlayId(any(), any())).thenReturn(nextId)
        val overlayUpdate = JuggOverlayUpdate(
            JuggDeploymentCacheEntry(Any(), emptyList(), baseId),
            DexComparator.ChangedClasses(
                (data.newClasses + data.hotFixModifiedClasses).map { it.toIncompleteDexClass() },
                data.hotReloadModifiedClasses.map { it.toIncompleteDexClass() },
            ),
            data.overlays.associate { item ->
                val entry = Mockito.mock(ApkEntry::class.java)
                whenever(entry.qualifiedPath).thenReturn("base.apk/${item.name}")
                entry to ByteString.copyFrom(item.content)
            },
            Any(),
        )
        var written: DirectOverlayWriteRequest? = null
        var checkedExpectedOverlayId: String? = null
        Mockito.mockConstruction(JuggJvmtiAgentManager::class.java) { manager, _ ->
            whenever(manager.pushAgentToApp(any(), any())).thenReturn(true)
        }.use { _ ->
            Mockito.mockConstruction(DirectOverlayStateChecker::class.java) { checker, _ ->
                whenever(checker.checkDevice(any(), any())).thenAnswer {
                    checkedExpectedOverlayId = it.getArgument(1)
                    DirectOverlayStateCheckResult.MATCHED
                }
            }.use { _ ->
                Mockito.mockConstruction(DirectOverlayWriter::class.java) { writer, _ ->
                    whenever(writer.write(any())).thenAnswer {
                        written = it.getArgument(0)
                        DirectOverlayWriteResult.SUCCESS
                    }
                }.use { _ ->
                    assertDirectResult(
                        data, overlayUpdate, compat, needsRestart, hotReloadResult,
                        expectedRefreshResources,
                        expectRuntimeApply,
                        logger,
                    )
                }
            }
        }
        assertEquals("base", checkedExpectedOverlayId)
        assertEquals(data.isFullRes, requireNotNull(written).isFullResourcePush)
        val expectedPaths = (overlayUpdate.dexOverlays.newClasses + overlayUpdate.dexOverlays.modifiedClasses)
            .map { "${it.name}.dex" } + data.overlays.map { "base.apk/${it.name}" }
        assertEquals(expectedPaths, written!!.files.map { it.path })
    }

    private fun assertDirectResult(
        data: JuggDeployData,
        overlayUpdate: JuggOverlayUpdate,
        compat: IAsDeployerCompat,
        needsRestart: Boolean,
        hotReloadResult: DirectHotReloadResult,
        expectedRefreshResources: Boolean,
        expectRuntimeApply: Boolean,
        logger: Logger,
    ) {
        var restartActivity: Boolean? = null
        var refreshResources: Boolean? = null
        var newClassCount: Int? = null
        var modifiedClassCount: Int? = null
        Mockito.mockConstruction(DirectHotReloadWriter::class.java) { writer, _ ->
            whenever(writer.apply(any(), any(), any(), any(), any(), any())).thenAnswer {
                newClassCount = it.getArgument<List<DirectHotReloadClass>>(2).size
                modifiedClassCount = it.getArgument<List<DirectHotReloadClass>>(3).size
                refreshResources = it.getArgument(4)
                restartActivity = it.getArgument(5)
                hotReloadResult
            }
        }.use { reload ->
            val deploy = {
                requireNotNull(transport(DirectAdb(), logger).tryDeploy(
                    "com.example.app", data, overlayUpdate, compat, listOf(123), Deploy.Arch.ARCH_64_BIT,
                ))
            }
            val result = deploy()
            assertEquals("next", result.overlayId.sha)
            assertEquals(needsRestart, result.needsRestart)
            assertEquals(if (expectRuntimeApply) 1 else 0, reload.constructed().size)
            if (expectRuntimeApply) {
                assertEquals(data.newClasses.size, newClassCount)
                assertEquals(data.hotReloadModifiedClasses.size, modifiedClassCount)
                assertEquals(expectedRefreshResources, refreshResources)
                assertEquals(data.isNeedRestartActivity, restartActivity)
            }
        }
    }

    private fun transport(
        adb: IDeviceAdb,
        logger: Logger = Mockito.mock(Logger::class.java),
    ): DirectAppSandboxDeployTransport {
        val context = LaunchContext(
            device = Mockito.mock(IDevice::class.java),
            deviceAdb = adb,
            installersRoot = "",
            installSession = Mockito.mock(JuggInstallSession::class.java),
            deviceAbi = "arm64-v8a",
            exceptOverlayIds = emptyMap(),
            isSkipExceptOverlayCheck = false,
            compileUiHandler = Mockito.mock(CompileUiHandler::class.java),
            isDirectOverlaySettingsEnabled = false,
            isDeviceReadyDeploy = true,
            isAllowDirectOverlayDeploy = false,
        )
        return DirectAppSandboxDeployTransport(context, logger)
    }

    private fun classData(): JuggDeployData {
        return JuggDeployData.forDryDeploy(emptyList()).copy(
            hotReloadModifiedClasses = listOf(
                ClassDeployItem(
                    DeployItem(
                        "com.example.Foo",
                        CompileOutput.Type.Dex,
                        1,
                        byteArrayOf(1),
                        DeployItem.FLAG_CLASS,
                    ),
                    listOf(ClassNode("Foo.dex", "Lcom/example/Foo;", 1, emptyList(), emptyList(),
                        emptyList(), "Ljava/lang/Object;", "Foo.java")),
                ),
            ),
            isPushOverlayOnly = false,
        )
    }

    private fun newClassData(): JuggDeployData {
        return JuggDeployData.forDryDeploy(emptyList()).copy(
            newClasses = listOf(
                ClassDeployItem(
                    DeployItem(
                        "com.example.NewClass",
                        CompileOutput.Type.Dex,
                        1,
                        byteArrayOf(1),
                        DeployItem.FLAG_CLASS,
                    ),
                    listOf(ClassNode("NewClass.dex", "Lcom/example/NewClass;", 1, emptyList(), emptyList(),
                        emptyList(), "Ljava/lang/Object;", "NewClass.java")),
                ),
            ),
            isPushOverlayOnly = false,
        )
    }

    private open class CompatibleAdb : IDeviceAdb {
        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String = ""

        override fun execAdbShellScript(cmd: String): String {
            return if (cmd.contains("__JUGG_RUN_AS_OK__")) {
                "__JUGG_RUN_AS_OK__:10001\n__JUGG_RUN_AS_CONTEXT__:ctx|ctx"
            } else {
                ""
            }
        }

        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }

    private class DirectAdb : CompatibleAdb() {
        override fun execAdbShellCmd(cmd: String): String {
            return when {
                cmd.startsWith("pidof ") -> "123"
                cmd.startsWith("dumpsys package ") -> "dataDir=/data/user/0/com.example.app"
                else -> ""
            }
        }

        override fun execAdbShellScript(cmd: String): String {
            return when {
                cmd.contains("__JUGG_RUN_AS_OK__") ->
                    "__JUGG_RUN_AS_OK__:1000\n__JUGG_RUN_AS_CONTEXT__:ctx|ctx"
                cmd.contains("__JUGG_DIRECT_SANDBOX_OK__") -> "__JUGG_DIRECT_SANDBOX_OK__"
                cmd.contains("direct_resource_overlay") ->
                    "success\n__JUGG_APP_SANDBOX_REPAIR_START__\n__JUGG_APP_SANDBOX_REPAIRED__"
                else -> ""
            }
        }
    }
}
