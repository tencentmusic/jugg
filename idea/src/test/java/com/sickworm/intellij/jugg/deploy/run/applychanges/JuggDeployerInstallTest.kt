package com.sickworm.intellij.jugg.deploy.run.applychanges

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.ClassRedefiner
import com.android.tools.deployer.DexComparator
import com.android.tools.deployer.Installer
import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.DeploymentService
import com.android.utils.ILogger
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IJuggDeployerDeploymentService
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentCacheEntry
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerException
import com.sickworm.intellij.jugg.deploy.run.JuggInstallSession
import com.sickworm.intellij.jugg.deploy.run.LaunchContext
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayFile
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayId
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import com.sickworm.intellij.jugg.deploy.run.SuggestRunConfiguration
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper
import com.sickworm.intellij.jugg.deploy.run.utils.AdbTransientOfflineException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.io.IOException

class JuggDeployerInstallTest {

    @Test
    fun `install retries once after offline exception and succeeds`() {
        val fixture = newFixture()
        fixture.compat.onInstall = { callIndex, _ ->
            if (callIndex == 1) {
                throw IOException("com.android.ddmlib.AdbCommandRejectedException: device offline")
            }
        }

        val result = fixture.deployer.install(
            packageName = PACKAGE_NAME,
            apks = listOf("/tmp/demo.apk"),
            argInstallMode = JuggInstallSession.Mode.FULL,
        )

        assertFalse(result.skippedInstall)
        assertEquals(2, fixture.compat.installCalls)
        Mockito.verify(fixture.ideaLogger).info(
            Mockito.eq("Install succeeded after transient ADB failure, retried once."),
        )
    }

    @Test
    fun `install fails when transport never recovers`() {
        val fixture = newFixture(shellReady = false)
        fixture.compat.onInstall = { _, _ ->
            throw IOException("device offline")
        }

        val thrown = assertThrows(AdbTransientOfflineException::class.java) {
            fixture.deployer.install(
                packageName = PACKAGE_NAME,
                apks = listOf("/tmp/demo.apk"),
                argInstallMode = JuggInstallSession.Mode.FULL,
            )
        }

        assertTrue(thrown.message!!.contains("install"))
        assertEquals(1, fixture.compat.installCalls)
    }

    @Test
    fun `not found on DELTA escalates to FULL then retries`() {
        val fixture = newFixture()
        fixture.compat.onInstall = { callIndex, _ ->
            if (callIndex == 1) {
                throw IOException("device 'emulator-5554' not found")
            }
        }

        fixture.deployer.install(
            packageName = PACKAGE_NAME,
            apks = listOf("/tmp/demo.apk"),
            argInstallMode = JuggInstallSession.Mode.DELTA,
        )

        assertEquals(2, fixture.compat.installCalls)
        assertEquals(JuggInstallSession.Mode.DELTA_NO_SKIP, fixture.compat.installModes[0])
        assertEquals(JuggInstallSession.Mode.FULL, fixture.compat.installModes[1])
    }

    @Test
    fun `realErrorMessage offline triggers retry`() {
        val fixture = newFixture()
        fixture.compat.onInstall = { callIndex, _ ->
            if (callIndex == 1) {
                fixture.logger.verbose("Installation Failure: device offline")
                throw IllegalStateException("Install failed")
            }
        }

        val result = fixture.deployer.install(
            packageName = PACKAGE_NAME,
            apks = listOf("/tmp/demo.apk"),
            argInstallMode = JuggInstallSession.Mode.FULL,
        )

        assertFalse(result.skippedInstall)
        assertEquals(2, fixture.compat.installCalls)
    }

    @Test
    fun `isTransientInstallFailure detects realErrorMessage offline`() {
        val logger = AdbLogWrapper(Mockito.mock(Logger::class.java))
        logger.verbose("Installation Failure: device offline")

        assertTrue(
            JuggDeployer.isTransientInstallFailure(IllegalStateException("Install failed"), logger),
        )
    }

    private fun newFixture(shellReady: Boolean = true): Fixture {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("emulator-5554")
        val deviceAdb = FakeDeviceAdb(shellReady)
        val compat = RecordingInstallCompat(listOf(testApk()))
        val deploymentService = Mockito.mock(IJuggDeployerDeploymentService::class.java)
        val installer = Mockito.mock(Installer::class.java)
        val ideaLogger = Mockito.mock(Logger::class.java)
        val logger = AdbLogWrapper(ideaLogger)
        val launchContext = LaunchContext(
            device = device,
            deviceAdb = deviceAdb,
            installersRoot = "/tmp/installers",
            installSession = JuggInstallSession(installer, "test-installer", { true }, {}),
            deviceAbi = "arm64-v8a",
            exceptOverlayIds = emptyMap(),
            isSkipExceptOverlayCheck = false,
            compileUiHandler = CompileUiHandler.DEFAULT,
            isDirectOverlaySettingsEnabled = false,
            isDeviceReadyDeploy = true,
            isAllowDirectOverlayDeploy = false,
        )

        val deployer = JuggDeployer(
            launchContext = launchContext,
            deploymentService = deploymentService,
            logger = logger,
            asDeployerCompat = compat,
        )
        return Fixture(deviceAdb, compat, deploymentService, ideaLogger, logger, deployer)
    }

    private fun testApk(): Apk {
        val constructor = Apk::class.java.declaredConstructors.first { it.parameterCount == 10 }
        constructor.isAccessible = true
        val args = constructor.parameterTypes.map { type ->
            when {
                type == String::class.java -> ""
                java.util.List::class.java.isAssignableFrom(type) -> emptyList<String>()
                java.util.Map::class.java.isAssignableFrom(type) -> emptyMap<String, com.android.tools.deployer.model.ApkEntry>()
                else -> null
            }
        }.toMutableList()
        args[0] = "demo.apk"
        args[1] = "checksum"
        args[2] = "/tmp/demo.apk"
        args[3] = PACKAGE_NAME
        return constructor.newInstance(*args.toTypedArray()) as Apk
    }

    private class RecordingInstallCompat(
        private val parsedApks: List<Apk>,
    ) : IAsDeployerCompat {
        var installCalls = 0
        val installModes = mutableListOf<JuggInstallSession.Mode>()
        var onInstall: (callIndex: Int, installMode: JuggInstallSession.Mode) -> Unit = { _, _ -> }

        override fun install(
            device: IDevice,
            session: JuggInstallSession,
            logger: ILogger,
            packageName: String,
            apks: List<String>,
            installMode: JuggInstallSession.Mode,
        ): Boolean {
            installCalls++
            installModes.add(installMode)
            onInstall(installCalls, installMode)
            return true
        }

        override fun getInstallMode(): JuggInstallSession.Mode = JuggInstallSession.Mode.DELTA

        override fun parseApks(paths: List<String>): List<Apk> = parsedApks

        override fun getPackageName(apks: List<Apk>): String = PACKAGE_NAME

        override fun createBaseOverlayId(apks: List<Apk>): JuggOverlayId {
            return JuggOverlayId(raw = Any(), sha = "base-overlay", isBaseInstall = true)
        }

        override fun buildOverlayId(base: JuggOverlayId, addedFiles: List<JuggOverlayFile>): JuggOverlayId =
            unsupported()

        override fun createOverlayUpdate(
            cachedDump: JuggDeploymentCacheEntry,
            dexOverlays: DexComparator.ChangedClasses,
            fileOverlays: Map<ApkEntry, ByteString>,
        ): JuggOverlayUpdate = unsupported()

        override fun dumpApks(session: JuggInstallSession, apks: List<Apk>): List<Apk> = unsupported()

        override fun remoteApkNotFound(): JuggDeployerException = unsupported()

        override fun overlayIdMismatch(): JuggDeployerException = unsupported()

        override fun apiNotSupported(): JuggDeployerException = unsupported()

        override fun wrapDeployerException(e: Throwable): JuggDeployerException? = null

        override fun createDeploymentCacheEntry(apks: List<Apk>, overlayId: JuggOverlayId): JuggDeploymentCacheEntry =
            unsupported()

        override fun getApkProvider(project: Project, config: AndroidRunConfiguration): ApkProvider =
            unsupported()

        override fun getSelectedDevices(project: Project): List<IDevice>? = unsupported()

        override fun getConnectedDevices(project: Project): List<IDevice>? = unsupported()

        override fun createInstallSession(
            installersFolder: String,
            device: IDevice,
            logger: ILogger,
            onPrompt: (String) -> Boolean,
            onMessage: (String) -> Unit,
        ): JuggInstallSession =
            unsupported()

        override fun makeDebuggerRedefiners(
            project: Project,
            device: IDevice,
            fallback: Boolean,
        ): Map<Int, ClassRedefiner> = unsupported()

        override fun optimisticSwap(
            session: JuggInstallSession,
            redefiners: Map<Int, ClassRedefiner>,
            packageName: String,
            argRestart: Boolean,
            pids: List<Int>,
            arch: Deploy.Arch,
            overlayUpdate: JuggOverlayUpdate,
            device: IDevice,
            logger: ILogger,
            isPushOverlayOnly: Boolean,
        ): JuggOverlayId = unsupported()

        override fun getIdeDeployStateResult(project: Project, device: IDevice?, packageName: String?): IdeDeployState =
            unsupported()

        override fun getDeploymentService(project: Project): DeploymentService = unsupported()

        override fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>) = unsupported()

        override fun getSuggestRunConfigurations(
            existsRunConfigNames: List<String>,
            project: Project,
            logger: Logger,
            isNeedDefaultRunConfig: Boolean,
        ): List<SuggestRunConfiguration> = unsupported()

        override fun getIdeModuleInfo(
            project: Project,
            module: Module,
            logger: Logger,
            isSafeMode: Boolean,
        ) = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    private data class Fixture(
        val deviceAdb: FakeDeviceAdb,
        val compat: RecordingInstallCompat,
        val deploymentService: IJuggDeployerDeploymentService,
        val ideaLogger: Logger,
        val logger: AdbLogWrapper,
        val deployer: JuggDeployer,
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
    }
}

private class FakeDeviceAdb(
    private val shellReady: Boolean,
) : IDeviceAdb {
    override val displayName: String = "fake"
    override val api: Int = 30
    override val serial: String = "emulator-5554"
    override val isOnline: Boolean = true

    override fun execAdbShellCmd(cmd: String): String {
        if (!shellReady && cmd == "true") {
            throw IOException("device offline")
        }
        return ""
    }

    override fun isAdbTransportReady(): Boolean = shellReady

    override fun push(from: File, to: String): Boolean = false

    override fun pull(from: String, to: File): Boolean = false

    override fun getDefaultLaunchActivity(apkFile: File): String? = null

    override fun getArch(packageName: String): String = "ARCH_UNKNOWN"

    override fun getProperty(name: String): String? = null
}
