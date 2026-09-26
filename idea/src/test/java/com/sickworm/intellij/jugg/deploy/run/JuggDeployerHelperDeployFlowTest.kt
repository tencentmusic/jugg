package com.sickworm.intellij.jugg.deploy.run

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateCheckResult
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateChecker
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.JuggJvmtiAgentManager
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.deploy.run.applychanges.CustomApkInstallScriptRunner
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowCaseId
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowFixture
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowMockBackend
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowOverlaySeed
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowTestSupport
import com.sickworm.intellij.jugg.deploy.run.deployflow.VirtualDeployDevice
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.MockedConstruction
import java.io.File

/**
 * L2 deploy-flow via [com.sickworm.intellij.jugg.deploy.run.deployflow.VirtualDeployDevice].
 * Spec: docs/task/2026-05/jugg_deploy_flow_virtual_device.md, jugg_deployer_helper_deploy_flow_test_plan.md §5.1
 */
class JuggDeployerHelperDeployFlowTest {

    @Test
    fun `recover reinstall executes configured script and stops on script failure`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_002)
        Mockito.mockConstruction(CustomApkInstallScriptRunner::class.java) { runner, _ ->
            Mockito.doThrow(IllegalStateException("Custom APK install script failed with exit code 7."))
                .`when`(runner).run(org.mockito.kotlin.any())
        }.use {
            val result = fixture.helper.deploy(fixture.deployOptions.copy(
                customApkInstallScript = "./install-app.sh",
            ))

            assertFalse(result.isSuccess)
            assertFalse(result.isCanFallback)
            assertTrue(result.failedReason.orEmpty().contains("exit code 7"))
            assertEquals(0, fixture.virtualDevice.installInvokeCount)
            assertFalse(fixture.virtualDevice.hasDirectOverlayApply())
            Mockito.verify(fixture.deployFileManager, Mockito.never()).resetAfterReinstall()
        }
    }

    @Test
    fun `DF-L2-001 direct write incremental deploy when app not deployable`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_001)
        assertOverlayRecoverMatched(fixture)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(fixture.virtualDevice.hasDirectOverlayApply())
        assertNotEquals("", fixture.virtualDevice.readOverlayId().orEmpty())
        assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
    }

    @Test
    fun `DF-L2-002 recover reinstall then direct write when overlay mismatched`() {
        val mismatchedDeviceOverlayId = "mismatched-device-overlay"
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_002)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(
            "recover must run DirectOverlayStateChecker with device overlay $mismatchedDeviceOverlayId " +
                "before reinstall/direct write (not APP_NOT_INSTALLED or legacy dry-deploy path)",
            fixture.virtualDevice.hadRecoverMismatchOverlayCheckBeforeInstallAndDirectWrite(mismatchedDeviceOverlayId),
        )
        assertTrue(fixture.virtualDevice.installInvokeCount >= 1)
        Mockito.verify(fixture.deployFileManager).resetAfterReinstall()
        assertTrue(fixture.virtualDevice.hasDirectOverlayApply())
        val deviceOverlayId = fixture.virtualDevice.readOverlayId().orEmpty()
        assertNotEquals("", deviceOverlayId)
        assertNotEquals(mismatchedDeviceOverlayId, deviceOverlayId)
        assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
        Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartApp(fixture.device)
    }

    @Test
    fun `DF-L2-003 recover dry restarts foreground app after direct write`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_003)
        assertOverlayRecoverMatched(fixture)
        Mockito.`when`(fixture.deployTargetManager.isAppForeground(fixture.device)).thenReturn(true)
        val recoverHost = requireNotNull(fixture.recoverRunHost)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(
            fixture.virtualDevice.hadRecoverMatchedOverlayCheckBeforeInstall(fixture.seededOverlayId),
        )
        assertEquals(0, recoverHost.installRecoverTaskCount)
        assertEquals(0, fixture.virtualDevice.installInvokeCount)
        Mockito.verify(fixture.deployTargetManager).restartApp(fixture.device)
        assertTrue(fixture.virtualDevice.hasDirectOverlayApply())
        assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
        assertEquals(JuggDeployData.DeployType.HOT_FIX, result.deployType)
    }

    @Test
    fun `DF-L2-004 direct write skipped falls back to Apply Changes`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_004)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(fixture.virtualDevice.failDirectOverlayPush)
        assertFalse(fixture.virtualDevice.hasDirectOverlayApply())
        assertTrue(
            "expected Apply Changes fallback after direct overlay push failure",
            fixture.compatBoundary.optimisticSwapInvokeCount >= 1,
        )
        assertEquals(
            1,
            fixture.virtualDevice.shellCommands.count {
                it == "dumpsys package ${DeployFlowOverlaySeed.packageName()}"
            },
        )
    }

    @Test
    fun `DF-L2-005 direct write dirty failure does not fall back to Apply Changes`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_005)
        assertEquals(VirtualDeployDevice.DirectOverlayWriteResult.APPLYING, fixture.virtualDevice.directOverlayWriteResult)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertFalse("deploy should fail on dirty direct overlay write", result.isSuccess)
        assertTrue(
            "failed reason should mention direct overlay",
            result.failedReason.orEmpty().contains("Direct overlay", ignoreCase = true),
        )
        assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
        assertTrue(fixture.virtualDevice.shellScripts.any { it.contains("__JUGG_DIRECT_OVERLAY__") })
    }

    @Test
    fun `DF-L2-006 skips direct write when app is deployable and uses Apply Changes`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_006)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertFalse(fixture.virtualDevice.hasDirectOverlayApply())
        assertTrue(
            "expected Apply Changes when isDeviceReadyDeploy is true",
            fixture.compatBoundary.optimisticSwapInvokeCount >= 1,
        )
    }

    @Test
    fun `force direct overlay retry bypasses ready deploy state`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_006)

        val result = fixture.helper.deploy(fixture.deployOptions.copy(forceDirectOverlayDeploy = true))

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(fixture.virtualDevice.hasDirectOverlayApply())
        assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
    }

    @Test
    fun `DF-L2-007 swap phase device mismatch falls back to Apply Changes without reinstall`() {
        val swapMismatchOverlayId = "swap-phase-mismatch-overlay"
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_007)
        val recoverHost = requireNotNull(fixture.recoverRunHost)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(
            fixture.virtualDevice.hadRecoverMatchedOverlayCheckBeforeInstall(fixture.seededOverlayId),
        )
        assertTrue(fixture.virtualDevice.hadOverlayStateCheckWithDeviceId(swapMismatchOverlayId))
        assertEquals(0, recoverHost.installRecoverTaskCount)
        assertEquals(0, fixture.virtualDevice.installInvokeCount)
        assertFalse(fixture.virtualDevice.hasDirectOverlayApply())
        assertTrue(
            "expected Apply Changes after swap-phase checkDevice mismatch",
            fixture.compatBoundary.optimisticSwapInvokeCount >= 1,
        )
    }

    @Test
    fun `DF-L2-008 recover reinstall base cache then direct write with as startup agent push`() {
        val mismatchedDeviceOverlayId = "mismatched-device-overlay"
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_008)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(
            fixture.virtualDevice.hadRecoverMismatchOverlayCheckBeforeInstallAndDirectWrite(mismatchedDeviceOverlayId),
        )
        assertTrue(fixture.virtualDevice.installInvokeCount >= 1)
        Mockito.verify(fixture.deployFileManager).resetAfterReinstall()
        assertTrue(fixture.virtualDevice.hasAsStartupAgentPush())
        assertTrue(fixture.virtualDevice.listStartupAgents().contains("dced2491-agent.so"))
        assertTrue(fixture.virtualDevice.hasDirectOverlayApply())
        assertNotEquals("", fixture.virtualDevice.readOverlayId().orEmpty())
        assertNotEquals(mismatchedDeviceOverlayId, fixture.virtualDevice.readOverlayId().orEmpty())
        assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
    }

    @Test
    fun `DF-L2-009 empty Apply Changes does not create debugger redefiners`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_009)
        val result = fixture.helper.deploy(fixture.deployOptions)

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertEquals(1, fixture.compatBoundary.optimisticSwapInvokeCount)
        assertEquals(0, fixture.compatBoundary.makeDebuggerRedefinersInvokeCount)
    }

    @Test
    fun `all HarmonyOS versions automatically use compat deploy`() {
        val oldRecordJson = JuggSettings.deviceCompatRecordJson
        JuggSettings.deviceCompatRecordJson = ""
        try {
            mapOf<String?, JuggDeployData.DeployType>(
                null to JuggDeployData.DeployType.HOT_RELOAD,
                "2.0.0" to JuggDeployData.DeployType.COMPAT_HOT_FIX,
                "3.0.0" to JuggDeployData.DeployType.COMPAT_HOT_FIX,
                "4.1.0" to JuggDeployData.DeployType.COMPAT_HOT_FIX,
                "4.2.0" to JuggDeployData.DeployType.COMPAT_HOT_FIX,
                "5.0.0" to JuggDeployData.DeployType.COMPAT_HOT_FIX,
            ).forEach { (version, expectedDeployType) ->
                val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_006)
                fixture.virtualDevice.harmonyOsVersion = version
                val normalData = DeployFlowTestSupport.incrementalDeployDataWithoutAppRestart()
                Mockito.`when`(
                    fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
                ).thenAnswer { invocation ->
                    if (invocation.getArgument<Boolean>(1)) {
                        normalData.copy(isCompatDeploy = true, isPushOverlayOnly = true)
                    } else {
                        normalData
                    }
                }

                val result = fixture.helper.deploy(fixture.deployOptions)

                assertTrue("deploy failed for HarmonyOS $version: ${result.failedReason}", result.isSuccess)
                assertEquals("unexpected deploy type for HarmonyOS $version", expectedDeployType, result.deployType)
            }
        } finally {
            JuggSettings.deviceCompatRecordJson = oldRecordJson
        }
    }

    @Test
    fun `all ASUS devices automatically use compat deploy`() {
        val oldRecordJson = JuggSettings.deviceCompatRecordJson
        JuggSettings.deviceCompatRecordJson = ""
        try {
            mapOf<String?, JuggDeployData.DeployType>(
                null to JuggDeployData.DeployType.HOT_RELOAD,
                "samsung" to JuggDeployData.DeployType.HOT_RELOAD,
                "asus" to JuggDeployData.DeployType.COMPAT_HOT_FIX,
                " ASUS " to JuggDeployData.DeployType.COMPAT_HOT_FIX,
            ).forEach { (manufacturer, expectedDeployType) ->
                val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_006)
                fixture.virtualDevice.manufacturer = manufacturer
                val normalData = DeployFlowTestSupport.incrementalDeployDataWithoutAppRestart()
                Mockito.`when`(
                    fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
                ).thenAnswer { invocation ->
                    if (invocation.getArgument<Boolean>(1)) {
                        normalData.copy(isCompatDeploy = true, isPushOverlayOnly = true)
                    } else {
                        normalData
                    }
                }

                val result = fixture.helper.deploy(fixture.deployOptions)

                assertTrue("deploy failed for manufacturer $manufacturer: ${result.failedReason}", result.isSuccess)
                assertEquals("unexpected deploy type for manufacturer $manufacturer", expectedDeployType, result.deployType)
            }
        } finally {
            JuggSettings.deviceCompatRecordJson = oldRecordJson
        }
    }

    @Test
    fun `debug restart flag restarts app after hot reload deploy`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_003)
        val compileUiHandler = object : CompileUiHandler by CompileUiHandler.DEFAULT {
            override val isAlwaysRestartApp: Boolean = true
        }

        val result = fixture.helper.deploy(fixture.deployOptions.copy(compileUiHandler = compileUiHandler))

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartApp(fixture.device)
    }

    @Test
    fun `apk root overlay restarts app after deploy`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_003)
        val deployData = DeployFlowTestSupport.incrementalDeployDataWithoutAppRestart()
        val apkPath = deployData.apks.first().files.first().apkFile.path
        Mockito.`when`(
            fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
        ).thenReturn(
            deployData.copy(
                overlays = listOf(
                    DeployItem(
                        name = "values/strings.xml",
                        type = CompileOutput.Type.Res,
                        checksum = 1L,
                        content = byteArrayOf(1, 2, 3),
                        apkPath = apkPath,
                    ),
                ),
            ),
        )

        val result = fixture.helper.deploy(fixture.deployOptions)

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartApp(fixture.device)
    }

    @Test
    fun `compose resource compile restarts app after deploy`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_003)
        val deployData = DeployFlowTestSupport.incrementalDeployDataWithoutAppRestart()
            .copy(isComposeResourceCompiled = true)
        Mockito.`when`(
            fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
        ).thenReturn(deployData)

        val result = fixture.helper.deploy(fixture.deployOptions)

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartApp(fixture.device)
    }

    @Test
    fun `affected AS restarts app twice for first modern compose resource deploy`() {
        withRelaunchActivityIssues {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_006)
            val deployData = modernComposeResourceDeployData()
            writeAsTransformCache(fixture.virtualDevice)
            Mockito.`when`(
                fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
            ).thenReturn(deployData)

            val result = fixture.helper.deploy(fixture.deployOptions)

            assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
            Mockito.verify(fixture.deployTargetManager, Mockito.times(2)).restartApp(fixture.device)
        }
    }

    @Test
    fun `affected AS does not restart app twice for legacy compose resource deploy`() {
        withRelaunchActivityIssues {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_006)
            val modernData = modernComposeResourceDeployData()
            val deployData = modernData.copy(
                overlays = listOf(modernData.overlays.single().let {
                    DeployItem(
                        name = "values/strings.xml",
                        type = CompileOutput.Type.Res,
                        checksum = it.checksum,
                        content = it.content,
                        apkPath = it.apkPath,
                    )
                }),
            )
            Mockito.`when`(
                fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
            ).thenReturn(deployData)

            val result = fixture.helper.deploy(fixture.deployOptions)

            assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
            Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartApp(fixture.device)
        }
    }

    @Test
    fun `affected AS does not restart app twice after a previous successful deploy`() {
        withRelaunchActivityIssues {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_006)
            val deployData = modernComposeResourceDeployData()
            val deployedFile = File(fixture.virtualDevice.root, "previous/Previous.dex").apply {
                parentFile.mkdirs()
                writeText("deployed")
            }
            writeAsTransformCache(fixture.virtualDevice)
            Mockito.`when`(fixture.deployFileManager.getDeployedFiles()).thenReturn(
                listOf(CompileOutput(CompileOutput.Type.Dex, deployedFile, deployedFile.parentFile)),
            )
            Mockito.`when`(
                fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
            ).thenReturn(deployData)

            val result = fixture.helper.deploy(fixture.deployOptions)

            assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
            Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartApp(fixture.device)
        }
    }

    @Test
    fun `flutter jit runtime change invalidates extraction cache before app restart`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_003)
        val deployData = DeployFlowTestSupport.incrementalDeployDataWithoutAppRestart()
        val apkPath = deployData.apks.first().files.first().apkFile.path
        val timestamp = File(
            fixture.virtualDevice.packageDataDir(),
            "app_flutter/res_timestamp-1-1789261483352",
        ).apply {
            parentFile.mkdirs()
            writeText("1")
        }
        Mockito.`when`(
            fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
        ).thenReturn(
            deployData.copy(
                flutterJitRuntimeFiles = listOf(
                    DeployItem(
                        name = "assets/flutter_assets/kernel_blob.bin",
                        type = CompileOutput.Type.Asset,
                        checksum = 1L,
                        content = byteArrayOf(1, 2, 3),
                        apkPath = apkPath,
                    ),
                ),
            ),
        )
        Mockito.doAnswer { fixture.virtualDevice.onAppRestart(); true }
            .`when`(fixture.deployTargetManager).restartApp(fixture.device)

        val result = fixture.helper.deploy(fixture.deployOptions)

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertEquals(JuggDeployData.DeployType.HOT_FIX, result.deployType)
        assertEquals(1, fixture.virtualDevice.flutterCacheInvalidationCount)
        assertEquals(
            "Flutter timestamp must be invalidated after the overlays are committed and before the app restart",
            0,
            fixture.virtualDevice.appRestartCountAtFlutterCacheInvalidation,
        )
        assertFalse("Flutter timestamp should be removed on device", timestamp.exists())
        Mockito.verify(fixture.deployTargetManager).restartApp(fixture.device)
    }

    @Test
    fun `flutter jit cache invalidation failure fails the deploy without restart`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_003)
        val deployData = DeployFlowTestSupport.incrementalDeployDataWithoutAppRestart()
        val apkPath = deployData.apks.first().files.first().apkFile.path
        Mockito.`when`(
            fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
        ).thenReturn(
            deployData.copy(
                flutterJitRuntimeFiles = listOf(
                    DeployItem(
                        name = "assets/flutter_assets/kernel_blob.bin",
                        type = CompileOutput.Type.Asset,
                        checksum = 1L,
                        content = byteArrayOf(1, 2, 3),
                        apkPath = apkPath,
                    ),
                ),
            ),
        )
        val adb = fixture.virtualDevice.asIDeviceAdb()
        Mockito.mockConstruction(AppSandboxExecutor::class.java) { sandbox, _ ->
            whenever(sandbox.mode).thenReturn(AppSandboxExecutor.Mode.RUN_AS)
            // Delegate filesystem commands to the virtual device; only the Flutter cache step fails.
            whenever(sandbox.exec(any(), any())).thenAnswer {
                val command = it.getArgument<String>(0)
                if (command.contains("app_flutter/res_timestamp-")) {
                    throw IllegalStateException("app sandbox shell failed")
                }
                adb.execAdbShellScript("run-as ${DeployFlowOverlaySeed.packageName()} sh -c '$command'")
            }
            whenever(sandbox.execNoFallback(any(), any())).thenAnswer {
                adb.execAdbShellScript("run-as ${DeployFlowOverlaySeed.packageName()} sh -c '${it.getArgument<String>(0)}'")
            }
        }.use {
            val result = fixture.helper.deploy(
                fixture.deployOptions.copy(retryReason = JuggDeployerHelper.DO_NOT_RETRY),
            )

            assertFalse("deploy must fail when the Flutter cache cannot be invalidated", result.isSuccess)
            assertTrue(result.failedReason.orEmpty().contains("app sandbox shell failed"))
            assertEquals(0, fixture.virtualDevice.flutterCacheInvalidationCount)
            Mockito.verify(fixture.deployTargetManager, Mockito.never()).restartApp(fixture.device)
        }
    }

    @Test
    fun `failed overlay slice does not invalidate flutter jit cache`() {
        withSingleOverlayPerSlice {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_011)
            val deployData = DeployFlowTestSupport.fullResourceDeployData(overlayCount = 3)
            val apkPath = deployData.apks.first().files.first().apkFile.path
            Mockito.`when`(
                fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
            ).thenReturn(
                deployData.copy(
                    flutterJitRuntimeFiles = listOf(
                        DeployItem(
                            name = "assets/flutter_assets/kernel_blob.bin",
                            type = CompileOutput.Type.Asset,
                            checksum = 1L,
                            content = byteArrayOf(1, 2, 3),
                            apkPath = apkPath,
                        ),
                    ),
                ),
            )

            val result = fixture.helper.deploy(
                fixture.deployOptions.copy(retryReason = JuggDeployerHelper.DO_NOT_RETRY),
            )

            assertFalse("deploy should fail on second slice", result.isSuccess)
            assertEquals(0, fixture.virtualDevice.flutterCacheInvalidationCount)
        }
    }

    @Test
    fun `recover reinstall restarts app after replay without other restart conditions`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_002)
        val deployData = DeployFlowTestSupport.incrementalDeployDataWithoutAppRestart()
        Mockito.`when`(
            fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
        ).thenReturn(
            deployData,
            deployData,
        )

        val result = fixture.helper.deploy(fixture.deployOptions)

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(result.hasDeployChanges)
        Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartApp(fixture.device)
    }

    @Test
    fun `native-only overlay deploy uses ordinary swap and restarts app`() {
        withNativeSandboxDeploy(enabled = true) {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_009)
            Mockito.`when`(
                fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
            ).thenReturn(DeployFlowTestSupport.nativeLibOnlyDeployData())

            val result = fixture.helper.deploy(fixture.deployOptions)

            assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
            assertEquals(0, fixture.virtualDevice.installInvokeCount)
            assertEquals(1, fixture.compatBoundary.optimisticSwapInvokeCount)
            Mockito.verify(fixture.deployTargetManager).restartApp(fixture.device)
            assertEquals(JuggDeployData.DeployType.HOT_FIX, result.deployType)
            assertTrue(result.hasDeployChanges)
        }
    }

    @Test
    fun `native-only deploy updates apk when so hot update is disabled`() {
        withNativeSandboxDeploy(enabled = false) {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_009)
            Mockito.`when`(
                fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
            ).thenReturn(DeployFlowTestSupport.nativeLibOnlyDeployData())

            val result = fixture.helper.deploy(fixture.deployOptions)

            assertFalse(result.isSuccess)
            assertTrue(result.isCanFallback)
            assertTrue(result.failedReason.orEmpty().contains("signing config"))
            assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
            assertEquals(0, fixture.virtualDevice.installInvokeCount)
        }
    }

    @Test
    fun `native-only deploy updates apk when device api is below oreo`() {
        withNativeSandboxDeploy(enabled = true) {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_009)
            fixture.virtualDevice.apiLevel = 25
            Mockito.`when`(
                fixture.deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()),
            ).thenReturn(DeployFlowTestSupport.nativeLibOnlyDeployData())

            val result = fixture.helper.deploy(fixture.deployOptions)

            assertFalse(result.isSuccess)
            assertTrue(result.isCanFallback)
            assertTrue(result.failedReason.orEmpty().contains("signing config"))
            assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
            assertEquals(0, fixture.virtualDevice.installInvokeCount)
        }
    }

    @Test
    fun `always restart flag does not restart app after empty deploy when app is foreground`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_009)
        val compileUiHandler = object : CompileUiHandler by CompileUiHandler.DEFAULT {
            override val isAlwaysRestartApp: Boolean = true
        }
        Mockito.`when`(fixture.deployTargetManager.isAppForeground(fixture.device)).thenReturn(true)

        val result = fixture.helper.deploy(fixture.deployOptions.copy(compileUiHandler = compileUiHandler))

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertFalse(result.hasDeployChanges)
        Mockito.verify(fixture.deployTargetManager, Mockito.never()).restartApp(fixture.device)
        Mockito.verify(fixture.deployTargetManager, Mockito.never()).startApp(fixture.device)
    }

    @Test
    fun `debug run starts app with debugger wait after empty deploy`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_009)
        val compileUiHandler = object : CompileUiHandler by CompileUiHandler.DEFAULT {
            override val isAlwaysRestartApp: Boolean = true
            override val isDebugRun: Boolean = true
        }

        val result = fixture.helper.deploy(fixture.deployOptions.copy(compileUiHandler = compileUiHandler))

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertFalse(result.hasDeployChanges)
        Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartAppForDebug(fixture.device)
        Mockito.verify(fixture.deployTargetManager, Mockito.never()).restartApp(fixture.device)
    }

    @Test
    fun `split full resource deploy restarts activity only on final slice`() {
        withSingleOverlayPerSlice {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_010)

            val result = fixture.helper.deploy(fixture.deployOptions)

            assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
            assertEquals(3, fixture.compatBoundary.optimisticSwapInvokeCount)
            assertEquals(listOf(false, false, true), fixture.compatBoundary.optimisticSwapRestartArgs)
            assertEquals(
                1,
                fixture.virtualDevice.shellCommands.count {
                    it == "dumpsys package ${DeployFlowOverlaySeed.packageName()}"
                },
            )
        }
    }

    @Test
    fun `split full resource deploy clears device overlay after partial slice failure`() {
        withSingleOverlayPerSlice {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_011)

            val result = fixture.helper.deploy(
                fixture.deployOptions.copy(retryReason = JuggDeployerHelper.DO_NOT_RETRY),
            )

            assertFalse("deploy should fail on second slice", result.isSuccess)
            assertEquals(2, fixture.compatBoundary.optimisticSwapInvokeCount)
            assertFalse("partial overlay directory should be removed", fixture.virtualDevice.hasOverlayDir())
            assertTrue(
                fixture.virtualDevice.shellCommands.any {
                    it.contains("run-as ${DeployFlowOverlaySeed.packageName()}") &&
                        it.contains("rm -rf code_cache/.overlay")
                },
            )
        }
    }

    @Test
    fun `direct overlay full resource deploy bypasses slicing`() {
        withSingleOverlayPerSlice {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_012)

            val result = fixture.helper.deploy(fixture.deployOptions)

            assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
            assertEquals(
                1,
                fixture.virtualDevice.shellScripts.count { it.contains("__JUGG_DIRECT_OVERLAY__") },
            )
            assertEquals(1, fixture.compatBoundary.createInstallSessionInvokeCount)
            assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
        }
    }

    @Test
    fun `system app full resource deploy bypasses slicing when ordinary Direct is disabled`() {
        withSingleOverlayPerSlice {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_010)
            val adb = fixture.virtualDevice.asIDeviceAdb()
            Mockito.mockConstruction(AppSandboxExecutor::class.java) { sandbox, _ ->
                whenever(sandbox.applyChangesCapability).thenReturn(AppSandboxExecutor.ApplyChangesCapability.INCOMPATIBLE)
                whenever(sandbox.mode).thenReturn(AppSandboxExecutor.Mode.DIRECT_SHELL)
                // Delegate filesystem commands to the virtual device; only sandbox permissions are mocked.
                whenever(sandbox.exec(any(), any())).thenAnswer {
                    adb.execAdbShellScript("run-as ${DeployFlowOverlaySeed.packageName()} sh -c '${it.getArgument<String>(0)}'")
                }
                whenever(sandbox.execNoFallback(any(), any())).thenAnswer {
                    adb.execAdbShellScript("run-as ${DeployFlowOverlaySeed.packageName()} sh -c '${it.getArgument<String>(0)}'")
                }
            }.use { sandboxes ->
                Mockito.mockConstruction(JuggJvmtiAgentManager::class.java) { manager, _ ->
                    whenever(manager.pushAgentToApp(any(), any(), any())).thenReturn(true)
                }.use {
                    val result = fixture.helper.deploy(fixture.deployOptions.copy(isAllowDirectOverlayDeploy = false))

                    assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
                    assertEquals(1, fixture.virtualDevice.shellScripts.count { it.contains("__JUGG_DIRECT_OVERLAY__") })
                    assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
                    assertEquals(1, sandboxes.constructed().size)
                    Mockito.verify(fixture.deployTargetManager).restartApp(fixture.device)
                }
            }
        }
    }

    @Test
    fun `DF-L2-013 rootless compat deploy stages payload, restarts once and commits after app import`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_013)
        val sandbox = unavailableSandbox(fixture)

        sandbox.use {
            val result = fixture.helper.deploy(fixture.deployOptions)

            assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
            assertEquals(JuggDeployData.DeployType.COMPAT_HOT_FIX, result.deployType)
            // The app owns the commit: one restart, then the confirmed overlay id is committed.
            Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartApp(fixture.device)
            assertEquals(1, fixture.virtualDevice.rootlessImportCount)
            val committedOverlayId = fixture.virtualDevice.readOverlayId().orEmpty()
            assertNotEquals("", committedOverlayId)
            assertNotEquals(fixture.seededOverlayId, committedOverlayId)
            assertEquals(
                committedOverlayId,
                JuggDeploymentService.loadEntry(
                    fixture.device.serialNumber,
                    DeployFlowOverlaySeed.packageName(),
                    AdbLogWrapper(logger),
                )
                    ?.overlayId?.sha,
            )
            assertEquals(
                committedOverlayId,
                fixture.deployHistoryManager.lastDeployOverlayIds[DeployFlowOverlaySeed.packageName()],
            )
            Mockito.verify(fixture.deployFileManager).commit(any())
            // No JVMTI agent, no in-process redefine, and the staged request is cleaned up.
            assertEquals(0, fixture.virtualDevice.asStartupAgentPushCount)
            assertTrue(
                fixture.virtualDevice.shellScripts.toString(),
                fixture.virtualDevice.shellScripts.none { it.contains("attach-agent") },
            )
            assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
            assertTrue(fixture.virtualDevice.stagedRootlessRequestDirs().isEmpty())
        }
    }

    @Test
    fun `DF-L2-013 rootless compat import failure keeps deploy state and reports the reason`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_013)
        val sandbox = unavailableSandbox(fixture)
        Mockito.doAnswer {
            fixture.virtualDevice.onAppRestart()
            fixture.virtualDevice.corruptStagedRootlessPayload()
            fixture.virtualDevice.runRootlessCompatImport()
            true
        }.`when`(fixture.deployTargetManager).restartApp(fixture.device)

        sandbox.use {
            val result = fixture.helper.deploy(fixture.deployOptions)

            assertFalse("deploy must fail when the app rejects the import", result.isSuccess)
            assertTrue(
                "failed reason should explain the missing confirmation: ${result.failedReason}",
                result.failedReason.orEmpty().contains("Rootless compat deploy was not confirmed"),
            )
            assertEquals(1, fixture.virtualDevice.rootlessImportFailureCount)
            // Nothing advances: device overlay, deployment cache and deploy history stay put.
            assertEquals(fixture.seededOverlayId, fixture.virtualDevice.readOverlayId())
            assertEquals(
                fixture.seededOverlayId,
                JuggDeploymentService.loadEntry(
                    fixture.device.serialNumber,
                    DeployFlowOverlaySeed.packageName(),
                    AdbLogWrapper(logger),
                )
                    ?.overlayId?.sha,
            )
            assertEquals(
                fixture.seededOverlayId,
                fixture.deployHistoryManager.lastDeployOverlayIds[DeployFlowOverlaySeed.packageName()],
            )
            Mockito.verify(fixture.deployFileManager, Mockito.never()).commit(any())
            Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartApp(fixture.device)
        }
    }

    /**
     * Models a production user ROM: Apply Changes is incompatible and ordinary shell, adb root and
     * su all fail to write the app sandbox.
     */
    private fun unavailableSandbox(fixture: DeployFlowFixture): MockedConstruction<AppSandboxExecutor> {
        val adb = fixture.virtualDevice.asIDeviceAdb()
        return Mockito.mockConstruction(AppSandboxExecutor::class.java) { sandbox, _ ->
            whenever(sandbox.applyChangesCapability).thenReturn(AppSandboxExecutor.ApplyChangesCapability.INCOMPATIBLE)
            whenever(sandbox.mode).thenReturn(AppSandboxExecutor.Mode.UNAVAILABLE)
            whenever(sandbox.unavailableReason).thenReturn("run-as incompatible; shell uid=2000; su unavailable")
            // Only the write capabilities are unavailable; the pre-deploy recover check still reads
            // the device overlay directly.
            whenever(sandbox.exec(any(), any())).thenAnswer {
                adb.execAdbShellScript(
                    "run-as ${DeployFlowOverlaySeed.packageName()} sh -c '${it.getArgument<String>(0)}'",
                )
            }
            whenever(sandbox.execNoFallback(any(), any())).thenAnswer {
                adb.execAdbShellScript(
                    "run-as ${DeployFlowOverlaySeed.packageName()} sh -c '${it.getArgument<String>(0)}'",
                )
            }
        }
    }

    private fun withSingleOverlayPerSlice(block: () -> Unit) {
        val oldRecordJson = JuggSettings.sliceDeployRecordJson
        JuggSettings.sliceDeployRecordJson = """[{"displayName":"virtual","firstSliceSize":1,"sliceSize":1}]"""
        try {
            block()
        } finally {
            JuggSettings.sliceDeployRecordJson = oldRecordJson
        }
    }

    private fun withNativeSandboxDeploy(enabled: Boolean, block: () -> Unit) {
        val previous = JuggSettings.isEnableNativeSandboxDeploy
        JuggSettings.isEnableNativeSandboxDeploy = enabled
        try {
            block()
        } finally {
            JuggSettings.isEnableNativeSandboxDeploy = previous
        }
    }

    private fun modernComposeResourceDeployData(): JuggDeployData {
        val data = DeployFlowTestSupport.fullResourceDeployData(overlayCount = 1)
        val overlay = data.overlays.single()
        return data.copy(
            overlays = listOf(
                DeployItem(
                    name = "assets/composeResources/example/values/strings.commonMain.cvr",
                    type = CompileOutput.Type.Asset,
                    checksum = overlay.checksum,
                    content = overlay.content,
                    apkPath = overlay.apkPath,
                ),
            ),
            isComposeResourceCompiled = true,
        )
    }

    private fun writeAsTransformCache(device: VirtualDeployDevice) {
        val cacheDir = File(device.studioDir(), "instruments-flow.jar.cache")
        cacheDir.mkdirs()
        File(cacheDir, "android-app-ResourcesManager").writeText("ready")
        File(cacheDir, "android-app-LoadedApk").writeText("ready")
    }

    private fun withRelaunchActivityIssues(block: () -> Unit) {
        val original = PlatformApi.impl
        PlatformApi.impl = object : IPlatformApi by original {
            override fun isHasRelaunchActivityIssues(
                device: IDeviceAdb,
                logger: Logger,
            ): Boolean = true
        }
        try {
            block()
        } finally {
            PlatformApi.impl = original
        }
    }

    private fun assertOverlayRecoverMatched(fixture: DeployFlowFixture) {
        val checker = DirectOverlayStateChecker(
            adb = fixture.virtualDevice.asIDeviceAdb(),
            logger = logger,
            deployHistoryManager = fixture.deployHistoryManager,
            deploymentService = JuggDeploymentService,
        )
        assertEquals(
            DirectOverlayStateCheckResult.MATCHED,
            checker.checkRecover(fixture.device.serialNumber, DeployFlowOverlaySeed.packageName()),
        )
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            DeployFlowMockBackend.initSettings()
        }
    }
}
