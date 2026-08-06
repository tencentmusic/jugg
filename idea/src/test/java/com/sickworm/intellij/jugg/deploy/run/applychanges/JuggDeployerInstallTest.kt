package com.sickworm.intellij.jugg.deploy.run.applychanges

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.sickworm.intellij.jugg.deploy.api.DexComparator
import com.android.tools.deployer.Installer
import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ApkEntry
import com.sickworm.intellij.jugg.deploy.api.ByteString
import com.sickworm.intellij.jugg.deploy.api.ILogger
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.createTestTaskRunnerManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IJuggDeployerDeploymentService
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentCacheEntry
import com.sickworm.intellij.jugg.deploy.cache.JuggDeploymentCacheStore
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerException
import com.sickworm.intellij.jugg.deploy.run.JuggInstallSession
import com.sickworm.intellij.jugg.deploy.run.JuggClassRedefiner
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

    @Test
    fun `deploy main path does not expose legacy deployer runtime types`() {
        val paths = listOf(
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt",
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggOverlayUpdate.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/IJuggDeployerDeploymentService.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentService.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/IdeaDeviceAdb.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt",
        )
        val legacyTypes = listOf(
            "AdbClient",
            "Installer",
            "InstallOptions",
            "UIService",
            "OverlayId",
            "DeployerException",
            "DeploymentCacheDatabase",
            "ApplicationDumper",
        )

        paths.forEach { path ->
            val file = findRepoFile(path)
            val text = file.readText()
            val signatureText = text.lineSequence()
                .filterNot { it.contains('"') }
                .filterNot { it.trimStart().startsWith("*") }
                .joinToString("\n")
            legacyTypes.forEach { legacyType ->
                val importReference = Regex("""import\s+com\.android\.tools\.deployer\.(\*|$legacyType)\b""")
                    .containsMatchIn(text)
                val signatureReference = Regex("""(^|[\s:<,(])$legacyType($|[\s>),?=])""")
                    .containsMatchIn(signatureText)
                assertFalse(
                    "$path should route legacy deployer $legacyType access through deploy_compat",
                    importReference || signatureReference,
                )
            }
        }

        assertFalse(
            "JuggDeployTask should route StudioFlags access through deploy_compat",
            findRepoFile(
                "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt",
            ).readText().contains("StudioFlags"),
        )
    }

    @Test
    fun `deployer compat boundary does not expose device adb or installer construction methods`() {
        val compatInterface = findRepoFile(
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt",
        ).readText()
        val forbiddenApiNames = listOf(
            "getInstaller(",
            "createInstallOptions(",
            "getPids(",
            "getArch(",
            "shell(",
            "push(",
            "uninstall(",
        )

        forbiddenApiNames.forEach { apiName ->
            assertFalse(
                "IAsDeployerCompat should not expose $apiName",
                compatInterface.contains(apiName),
            )
        }
    }

    @Test
    fun `install mode is resolved by version compat implementations`() {
        val compatInterface = findRepoFile(
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt",
        ).readText()
        val chipmunkCompat = findRepoFile(
            "deploy_compat/v_chipmunk/src/main/java/com/sickworm/intellij/jugg/deploy/run/ChipmunkAsDeployerCompat.kt",
        ).readText()
        val quailCompat = findRepoFile(
            "deploy_compat/v_quail/src/main/java/com/sickworm/intellij/jugg/deploy/run/QuailAsDeployerCompat.kt",
        ).readText()

        assertFalse(
            "IAsDeployerCompat should not bypass version dispatch for StudioFlags",
            compatInterface.contains("DELTA_INSTALL"),
        )
        assertTrue(
            "Chipmunk compat should keep legacy DELTA_INSTALL behavior",
            chipmunkCompat.contains("override fun getInstallMode()") &&
                chipmunkCompat.contains("StudioFlags.DELTA_INSTALL.get()"),
        )
        assertTrue(
            "Quail compat should define its own install mode behavior",
            quailCompat.contains("override fun getInstallMode()"),
        )
        assertFalse(
            "Quail compat should not read removed DELTA_INSTALL flag",
            quailCompat.contains("DELTA_INSTALL"),
        )
    }

    @Test
    fun `deployment service delegates studio cache database details`() {
        val serviceText = findRepoFile(
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentService.kt",
        ).readText()

        assertFalse(
            "JuggDeploymentService should delegate DeploymentCacheDatabase reflection to a cache store",
            serviceText.contains("DeploymentCacheDatabase"),
        )
    }

    @Test
    fun `deployment service keeps runtime local memory cache before disk fallback`() {
        val serviceText = findRepoFile(
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentService.kt",
        ).readText()

        assertTrue(serviceText.contains("ConcurrentHashMap<String, JuggDeploymentCacheEntry>"))
        assertTrue(serviceText.contains("load from memory cache"))
    }

    @Test
    fun `production code uses task runner instead of execution lock types`() {
        val allowedFiles = setOf(
            findRepoFile("main/src/main/java/com/sickworm/intellij/jugg/project/runtime/TaskRunnerManager.kt").canonicalFile,
            findRepoFile("main/src/main/java/com/sickworm/intellij/jugg/project/runtime/ExecutionLockManager.kt").canonicalFile,
        )
        val productionFiles = listOf(
            "main/src/main",
            "idea/src/main",
            "idea/src/ide_entry",
            "cmd_line/src/main",
        ).flatMap { root ->
            findRepoFile(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.filterNot { it.canonicalFile in allowedFiles }

        productionFiles.forEach { file ->
            val source = file.readText()
            listOf("IExecutionLockManager", "GlobalExecutionLock", "RuntimeIdentity").forEach { type ->
                assertFalse("${file.path} should not expose $type", source.contains(type))
            }
        }
    }

    @Test
    fun `hot update bootstrap does not depend on task runner`() {
        listOf(
            "idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggLoader.kt",
            "idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggHotUpdateBootstrap.kt",
        ).forEach { path ->
            val source = findRepoFile(path).readText()
            assertFalse(
                "$path should not depend on TaskRunnerManager",
                source.contains("TaskRunnerManager"),
            )
        }
        val loaderSource = findRepoFile(
            "idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggLoader.kt",
        ).readText()
        assertFalse("JuggLoader should not delete hot update files", loaderSource.contains(".delete()"))
    }

    @Test
    fun `deployment cache store uses local source snapshot without studio deployer dependencies`() {
        assertFalse(
            "Deployment cache store should use local source implementation instead of reflection",
            findOptionalRepoFile(
                "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/ReflectiveJuggDeploymentCacheStore.kt",
            )?.exists() == true,
        )

        val storeText = findRepoFile(
            "main/src/main/java/com/sickworm/intellij/jugg/deploy/cache/JuggDeploymentCacheStore.kt",
        ).readText()
        listOf(
            "Class.forName",
            "getMethod(",
            "getDeclaredConstructor",
            "com.android.tools.deployer",
            "com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat",
            "com.sickworm.intellij.jugg.deploy.run.JuggDeploymentCacheEntry",
            "Apk",
        ).forEach { forbiddenCall ->
            assertFalse(
                "JuggDeploymentCacheStore should not depend on $forbiddenCall",
                storeText.contains(forbiddenCall),
            )
        }
    }

    @Test
    fun `local deployment cache store persists source snapshot`() {
        val cacheFile = File.createTempFile("jugg-deployment-cache", ".bin")
        cacheFile.deleteOnExit()
        val pathManager = JuggPathManager(cacheFile.parentFile.resolve("jugg-cache-test-project"))
        val store = JuggDeploymentCacheStore(cacheFile, createTestTaskRunnerManager(pathManager))
        val entry = JuggDeploymentCacheStore.CacheEntry(
            apkPaths = listOf("/tmp/app.apk"),
            overlayId = JuggDeploymentCacheStore.OverlayId(
                sha = "overlay-sha",
                isBaseInstall = false,
                overlayFiles = listOf(JuggDeploymentCacheStore.OverlayFile("base.apk/classes.dex", 42L)),
            ),
        )

        store.store("device", PACKAGE_NAME, entry)
        val restored = JuggDeploymentCacheStore(cacheFile, createTestTaskRunnerManager(pathManager))
            .load("device", PACKAGE_NAME)

        assertEquals(entry, restored)
    }

    private fun findRepoFile(path: String): File {
        return findOptionalRepoFile(path) ?: throw IllegalStateException("Cannot find $path")
    }

    private fun findOptionalRepoFile(path: String): File? {
        var current = File("").absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) {
                return candidate
            }
            current = current.parentFile ?: break
        }
        return null
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
        return Apk("demo.apk", "checksum", "/tmp/demo.apk", PACKAGE_NAME, emptyList(), emptyList(), emptyList(), emptyMap())
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
        ): Map<Int, JuggClassRedefiner> = unsupported()

        override fun optimisticSwap(
            session: JuggInstallSession,
            redefiners: Map<Int, JuggClassRedefiner>,
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
