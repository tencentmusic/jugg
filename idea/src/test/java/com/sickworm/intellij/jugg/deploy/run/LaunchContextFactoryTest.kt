package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.android.tools.deployer.Installer
import com.sickworm.intellij.jugg.deploy.api.ILogger
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class LaunchContextFactoryTest {

    private val oldDirectOverlayDeploy = JuggSettings.isEnableDirectOverlayDeploy
    private val device = Mockito.mock(IDevice::class.java)
    private val adb = Mockito.mock(IDeviceAdb::class.java)
    private val asDeployerCompat = FakeAsDeployerCompat()
    private val logger = TestGlobal.getLogger()

    @Before
    fun setUp() {
        Mockito.`when`(device.serialNumber).thenReturn("emulator-5554")
        Mockito.`when`(adb.getProperty("ro.product.cpu.abi")).thenReturn("x86_64")
    }

    @After
    fun tearDown() {
        JuggSettings.isEnableDirectOverlayDeploy = oldDirectOverlayDeploy
    }

    @Test
    fun `create should build complete launch context`() {
        JuggSettings.isEnableDirectOverlayDeploy = true

        val context = newFactory().create(
            device = device,
            exceptOverlayIds = mapOf("com.example.app" to "overlay-id"),
            isSkipExceptOverlayCheck = true,
            compileUiHandler = CompileUiHandler.DEFAULT,
            isDeviceReadyDeploy = false,
            isAllowDirectOverlayDeploy = true,
        )

        assertSame(device, context.device)
        assertSame(adb, context.deviceAdb)
        assertEquals("/tmp/installers", context.installersRoot)
        assertEquals("test-installer", context.installSession.installerVersion)
        assertSame(asDeployerCompat.boundExecutor, context.applyChangesExecutor)
        assertEquals("x86_64", context.deviceAbi)
        assertEquals(mapOf("com.example.app" to "overlay-id"), context.exceptOverlayIds)
        assertTrue(context.isSkipExceptOverlayCheck)
        assertTrue(context.isDirectOverlayEnabled)
        assertEquals(1, asDeployerCompat.createInstallSessionCount)
    }

    @Test
    fun `direct overlay enabled should require settings device offline and caller consent`() {
        JuggSettings.isEnableDirectOverlayDeploy = true
        assertTrue(newContext(isDeviceReadyDeploy = false, isAllowDirectOverlayDeploy = true).isDirectOverlayEnabled)

        JuggSettings.isEnableDirectOverlayDeploy = false
        assertFalse(newContext(isDeviceReadyDeploy = false, isAllowDirectOverlayDeploy = true).isDirectOverlayEnabled)

        JuggSettings.isEnableDirectOverlayDeploy = true
        assertFalse(newContext(isDeviceReadyDeploy = true, isAllowDirectOverlayDeploy = true).isDirectOverlayEnabled)
        assertFalse(newContext(isDeviceReadyDeploy = false, isAllowDirectOverlayDeploy = false).isDirectOverlayEnabled)
    }

    @Test
    fun `direct overlay enabled should allow force retry when device is ready`() {
        JuggSettings.isEnableDirectOverlayDeploy = true

        assertTrue(
            newContext(
                isDeviceReadyDeploy = true,
                isAllowDirectOverlayDeploy = true,
                forceDirectOverlayDeploy = true,
            ).isDirectOverlayEnabled,
        )
        assertFalse(
            newContext(
                isDeviceReadyDeploy = true,
                isAllowDirectOverlayDeploy = false,
                forceDirectOverlayDeploy = true,
            ).isDirectOverlayEnabled,
        )
    }

    private fun newContext(
        isDeviceReadyDeploy: Boolean,
        isAllowDirectOverlayDeploy: Boolean,
        forceDirectOverlayDeploy: Boolean = false,
    ): LaunchContext {
        return newFactory().create(
            device = device,
            exceptOverlayIds = emptyMap(),
            isSkipExceptOverlayCheck = false,
            compileUiHandler = CompileUiHandler.DEFAULT,
            isDeviceReadyDeploy = isDeviceReadyDeploy,
            isAllowDirectOverlayDeploy = isAllowDirectOverlayDeploy,
            forceDirectOverlayDeploy = forceDirectOverlayDeploy,
        )
    }

    private fun newFactory(): LaunchContextFactory {
        return LaunchContextFactory(
            environment = TestDeployEnvironment(asDeployerCompat, adb, isDirectOverlayEnabled = JuggSettings.isEnableDirectOverlayDeploy),
            logger = logger,
        )
    }

    private class FakeAsDeployerCompat : IAsDeployerCompat by Mockito.mock(IAsDeployerCompat::class.java) {
        val boundExecutor: IApplyChangesExecutor = Mockito.mock(IApplyChangesExecutor::class.java)
        var createInstallSessionCount: Int = 0
            private set

        override fun createInstallSession(
            installersFolder: String,
            device: IDevice,
            logger: ILogger,
            onPrompt: (String) -> Boolean,
            onMessage: (String) -> Unit,
        ): JuggInstallSession {
            createInstallSessionCount++
            assertEquals("/tmp/installers", installersFolder)
            return JuggInstallSession(boundExecutor, Mockito.mock(Installer::class.java), "test-installer", onPrompt, onMessage)
        }
    }
}
