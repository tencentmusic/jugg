package com.sickworm.intellij.jugg.deploy.run.applychanges

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.AdbClient
import com.android.tools.deployer.AdbInstaller
import com.android.tools.deployer.ClassRedefiner
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.InstallOptions
import com.android.tools.deployer.Installer
import com.android.tools.deployer.OverlayId
import com.android.tools.deployer.UIService
import com.android.tools.deployer.model.Apk
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.DeploymentService
import com.android.utils.ILogger
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlaySwapOptions
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IJuggDeployerDeploymentService
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import com.sickworm.intellij.jugg.deploy.run.SuggestRunConfiguration
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper
import com.sickworm.intellij.jugg.deploy.run.utils.AdbTransientOfflineException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
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
            options = InstallOptions.builder().build(),
            argInstallMode = InstallMode.FULL,
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
                options = InstallOptions.builder().build(),
                argInstallMode = InstallMode.FULL,
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
            options = InstallOptions.builder().build(),
            argInstallMode = InstallMode.DELTA,
        )

        assertEquals(2, fixture.compat.installCalls)
        assertEquals(InstallMode.DELTA_NO_SKIP, fixture.compat.installModes[0])
        assertEquals(InstallMode.FULL, fixture.compat.installModes[1])
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
            options = InstallOptions.builder().build(),
            argInstallMode = InstallMode.FULL,
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
        val adb = mockAdbClient(shellReady)
        val compat = RecordingInstallCompat(listOf(testApk()))
        val deploymentService = Mockito.mock(IJuggDeployerDeploymentService::class.java)
        val installer = Mockito.mock(Installer::class.java)
        val service = Mockito.mock(UIService::class.java)
        val ideaLogger = Mockito.mock(Logger::class.java)
        val logger = AdbLogWrapper(ideaLogger)

        val deployer = JuggDeployer(
            adb = adb,
            deploymentService = deploymentService,
            installer = installer,
            service = service,
            exceptOverlayIds = emptyMap(),
            isSkipExceptOverlayCheck = false,
            logger = logger,
            directOverlaySwapOptions = DirectOverlaySwapOptions.disabled(),
            asDeployerCompat = compat,
        )
        return Fixture(adb, compat, deploymentService, ideaLogger, logger, deployer)
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

    private fun mockAdbClient(shellReady: Boolean): AdbClient {
        val defaultAnswer = Answers.RETURNS_DEFAULTS
        return Mockito.mock(AdbClient::class.java) { invocation: InvocationOnMock ->
            when (invocation.method.name) {
                "getSerial" -> "emulator-5554"
                "shell" -> if (shellReady) {
                    ByteArray(0)
                } else {
                    throw IOException("device offline")
                }
                else -> defaultAnswer.answer(invocation)
            }
        }
    }

    private class RecordingInstallCompat(
        private val parsedApks: List<Apk>,
    ) : IAsDeployerCompat {
        var installCalls = 0
        val installModes = mutableListOf<InstallMode>()
        var onInstall: (callIndex: Int, installMode: InstallMode) -> Unit = { _, _ -> }

        override fun install(
            adb: AdbClient,
            service: UIService,
            installer: Installer,
            logger: ILogger,
            packageName: String,
            apks: List<String>,
            options: InstallOptions,
            installMode: InstallMode,
        ): Boolean {
            installCalls++
            installModes.add(installMode)
            onInstall(installCalls, installMode)
            return true
        }

        override fun parseApks(paths: List<String>): List<Apk> = parsedApks

        override fun getApkProvider(project: Project, config: AndroidRunConfiguration): ApkProvider =
            unsupported()

        override fun getSelectedDevices(project: Project): List<IDevice>? = unsupported()

        override fun getConnectedDevices(project: Project): List<IDevice>? = unsupported()

        override fun getInstaller(installersFolder: String, adb: AdbClient, logger: ILogger): AdbInstaller =
            unsupported()

        override fun makeDebuggerRedefiners(
            project: Project,
            device: IDevice,
            fallback: Boolean,
        ): Map<Int, ClassRedefiner> = unsupported()

        override fun optimisticSwap(
            installer: Installer,
            redefiners: Map<Int, ClassRedefiner>,
            packageName: String,
            argRestart: Boolean,
            pids: List<Int>,
            arch: Deploy.Arch,
            overlayUpdate: JuggOverlayUpdate,
            adb: AdbClient,
            logger: ILogger,
            isPushOverlayOnly: Boolean,
        ): OverlayId = unsupported()

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
        val adb: AdbClient,
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
