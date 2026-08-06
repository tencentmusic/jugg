package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IDevice as RawDevice
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.android.tools.deployer.ApkEntryExtractor
import com.android.tools.deployer.D8DexSplitter
import com.android.tools.deployer.DexComparator
import com.android.tools.deployer.OptimisticApkSwapper
import com.android.tools.deployer.common.AdbClient
import com.android.tools.deployer.common.ApkDiffer
import com.android.tools.deployer.common.DeploymentCacheDatabase
import com.android.tools.deployer.model.ApkParser
import com.android.tools.deployer.model.FileDiff
import com.android.utils.StdLogger
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneDeployerDeviceFlowTest {

    private var bridge: AndroidDebugBridge? = null

    @After
    fun tearDown() {
        AndroidDebugBridge.disconnectBridge()
        AndroidDebugBridge.terminate()
    }

    @Test
    fun `real device keeps process and limits activity restart to resource change`() {
        val serial = System.getProperty(DEVICE_SERIAL_PROPERTY)
        val baselineApk = System.getProperty(BASELINE_APK_PROPERTY)?.let(::File)
        val classApk = System.getProperty(CLASS_APK_PROPERTY)?.let(::File)
        val resourceApk = System.getProperty(RESOURCE_APK_PROPERTY)?.let(::File)
        assumeTrue(serial != null && baselineApk?.isFile == true && classApk?.isFile == true && resourceApk?.isFile == true)

        val device = connectDevice(serial!!)
        val executor = StandaloneApplyChangesExecutor()
        val converter = StandaloneDeployApiConverter()
        val juggDevice = converter.toJuggDevice(device)
        val prepared = StandaloneDeployerResources.prepare("device-flow")
        val logger = StdLogger(StdLogger.Level.VERBOSE)
        val baseline = executor.parseApks(listOf(baselineApk!!.path))
        val packageName = executor.getPackageName(baseline)
        val juggLogger = converter.toJuggLogger(logger)
        val session = executor.createInstallSession(prepared.directory.resolve("installer").path, juggDevice, juggLogger, { true }, {})

        shell(device, "am force-stop $packageName")
        device.uninstallPackage(packageName)
        executor.install(juggDevice, session, juggLogger, packageName, listOf(baselineApk.path), JuggInstallSession.Mode.FULL)
        shell(device, "logcat -c")
        shell(device, "am start -W -n $packageName/.MainActivity")
        val initialPid = waitForPid(device, packageName)
        triggerRender(device, packageName)
        waitForLog(device, "[JUGG_BENCH] VALUE=resource-v1")

        var cache = executor.createDeploymentCacheEntry(baseline, executor.createBaseOverlayId(baseline))
        cache = deploy(executor, converter, session, device, packageName, cache, classApk!!.path,
            "com.example.myapplication.MainActivity")
        assertEquals(initialPid, waitForPid(device, packageName))
        triggerRender(device, packageName)
        waitForLog(device, "[JUGG_BENCH] VALUE=RESOURCE-V1")
        assertEquals(1, activityStartCount(device))

        cache = deploy(executor, converter, session, device, packageName, cache, resourceApk!!.path, restartActivity = true)
        assertTrue(cache.overlayId.sha.isNotBlank())
        assertEquals(initialPid, waitForPid(device, packageName))
        triggerRender(device, packageName)
        waitForLog(device, "[JUGG_BENCH] VALUE=RESOURCE-V2")

        assertEquals(2, activityStartCount(device))
    }

    private fun deploy(
        executor: StandaloneApplyChangesExecutor,
        converter: StandaloneDeployApiConverter,
        session: JuggInstallSession,
        device: RawDevice,
        packageName: String,
        cache: JuggDeploymentCacheEntry,
        apkPath: String,
        expectedClass: String? = null,
        restartActivity: Boolean = false,
    ): JuggDeploymentCacheEntry {
        val apks = executor.parseApks(listOf(apkPath))
        val rawApks = ApkParser.parsePaths(listOf(apkPath))
        val diffs = ApkDiffer().specDiff(cache.raw as DeploymentCacheDatabase.Entry, rawApks)
        val dexDiffs = diffs.filter { it.newFile?.name?.endsWith(".dex") == true }
        val changedClasses = DexComparator().compare(dexDiffs, D8DexSplitter())
        val files = ApkEntryExtractor { path ->
            path == "resources.arsc" || path.startsWith("res/") || path.startsWith("assets/")
        }.extractFromDiffs(
            diffs.filter { it.status != FileDiff.Status.DELETED },
            ApkParser.parsePaths(listOf(cache.apks.first().path)).first(),
        )
        val changedClassNames = (changedClasses.newClasses + changedClasses.modifiedClasses).map { it.name }
        if (expectedClass != null) assertTrue(expectedClass in changedClassNames,
            "Expected $expectedClass in changed classes: $changedClassNames")
        assertTrue(changedClasses.newClasses.isNotEmpty() || changedClasses.modifiedClasses.isNotEmpty() || files.isNotEmpty(),
            "APK diff did not produce deployable changes")
        val update = JuggOverlayUpdate(
            cache,
            converter.toJuggChangedClasses(changedClasses),
            files.mapKeys { converter.toJuggApkEntry(it.key) }.mapValues { converter.toJuggByteString(it.value) },
            OptimisticApkSwapper.OverlayUpdate(cache.raw as DeploymentCacheDatabase.Entry, changedClasses, files),
        )
        val pid = waitForPid(device, packageName).toInt()
        val arch = AdbClient.getArchForAbi(device.abis.first())
            ?.let { Deploy.Arch.valueOf(it.name) }
            ?: Deploy.Arch.ARCH_64_BIT
        val overlayId = executor.optimisticSwap(session, emptyMap(), packageName, restartActivity, listOf(pid), arch, update)
        return executor.createDeploymentCacheEntry(apks, overlayId)
    }

    private fun connectDevice(serial: String): RawDevice {
        AndroidDebugBridge.initIfNeeded(false)
        bridge = AndroidDebugBridge.createBridge(resolveAdb().path, false)
        repeat(100) {
            bridge?.devices?.firstOrNull { it.serialNumber == serial }?.let { return it }
            Thread.sleep(100)
        }
        error("Device not found: $serial")
    }

    private fun resolveAdb(): File {
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        require(!androidHome.isNullOrBlank()) { "ANDROID_HOME or ANDROID_SDK_ROOT is required" }
        return File(androidHome, "platform-tools/adb")
    }

    private fun waitForPid(device: RawDevice, packageName: String): String {
        repeat(100) {
            shell(device, "pidof $packageName").trim().takeIf(String::isNotEmpty)?.let { return it.substringBefore(' ') }
            Thread.sleep(100)
        }
        error("Process did not start: $packageName")
    }

    private fun triggerRender(device: RawDevice, packageName: String) {
        shell(device, "am start -f 0x00020000 -n $packageName/.MainActivity >/dev/null; " +
            "am start -f 0x20000000 -n $packageName/.MainActivity --es jugg.action render >/dev/null")
    }

    private fun activityStartCount(device: RawDevice): Int {
        return shell(device, "logcat -d -s jugg:I '*:S'")
            .lineSequence().count { it.contains("[JUGG_BENCH] MAIN_ACTIVITY_READY") }
    }

    private fun waitForLog(device: RawDevice, marker: String) {
        repeat(50) {
            if (shell(device, "logcat -d -s jugg:I '*:S'").contains(marker)) return
            Thread.sleep(100)
        }
        error("Log marker did not appear: $marker")
    }

    private fun shell(device: RawDevice, command: String): String {
        val receiver = CollectingOutputReceiver()
        device.executeShellCommand(command, receiver, 30, TimeUnit.SECONDS)
        return receiver.output
    }

    private companion object {
        const val DEVICE_SERIAL_PROPERTY = "standalone.deployer.device.serial"
        const val BASELINE_APK_PROPERTY = "standalone.deployer.baseline.apk"
        const val CLASS_APK_PROPERTY = "standalone.deployer.class.apk"
        const val RESOURCE_APK_PROPERTY = "standalone.deployer.resource.apk"
    }
}
