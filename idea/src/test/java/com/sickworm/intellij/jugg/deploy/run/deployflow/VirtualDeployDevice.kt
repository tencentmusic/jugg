package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.android.ddmlib.IDevice
import com.android.sdklib.AndroidVersion
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import org.mockito.Mockito
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * In-memory device filesystem with marker handlers plus host-shell run-as simulation for deploy-flow L2 tests.
 */
class VirtualDeployDevice(
    val packageName: String,
    val serial: String = "virtual-deploy-device",
) {
    val root: File = Files.createTempDirectory("jugg-virtual-device-").toFile()
    val shellCommands: MutableList<String> = mutableListOf()
    val shellScripts: MutableList<String> = mutableListOf()
    val overlayStateProbes: MutableList<OverlayStateProbe> = mutableListOf()
    var installInvokeCount: Int = 0
        private set
    var failDirectOverlayPush: Boolean = false
    var directOverlayWriteResult: DirectOverlayWriteResult = DirectOverlayWriteResult.OK
    var asStartupAgentPushCount: Int = 0
        private set
    var harmonyOsVersion: String? = null
    var manufacturer: String? = null

    private val remotePushFiles = mutableMapOf<String, File>()

    enum class DirectOverlayWriteResult {
        OK,
        SKIPPED,
        APPLYING,
    }

    /**
     * One [__JUGG_OVERLAY_STATE__] script execution (e.g. [DirectOverlayStateChecker.checkRecover]).
     */
    data class OverlayStateProbe(
        val scriptIndex: Int,
        val deviceOverlayId: String?,
        val installInvokeCountAtProbe: Int,
    )

    private val ddmlibDevice: IDevice by lazy {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn(serial)
        Mockito.`when`(device.name).thenReturn("virtual-$serial")
        Mockito.`when`(device.isOnline).thenReturn(true)
        Mockito.`when`(device.version).thenReturn(AndroidVersion(30, null))
        Mockito.`when`(device.clients).thenReturn(emptyArray())
        device
    }

    fun asIDeviceAdb(): IDeviceAdb = VirtualDeviceAdb(this)

    /** Stable ddmlib handle shared by [DeployOptions], target manager stubs, and recover paths. */
    fun asDdmlibDevice(): IDevice = ddmlibDevice

    fun packageDataDir(): File = File(root, "data/data/$packageName")

    fun overlayIdFile(): File = File(packageDataDir(), "code_cache/.overlay/id")

    fun writeOverlayId(overlayId: String) {
        val idFile = overlayIdFile()
        idFile.parentFile?.mkdirs()
        idFile.writeText(overlayId)
    }

    fun writeOverlayFile(path: String, content: ByteArray) {
        val file = File(packageDataDir(), "code_cache/.overlay/$path")
        file.parentFile?.mkdirs()
        file.writeBytes(content)
    }

    fun hasOverlayDir(): Boolean {
        return File(packageDataDir(), "code_cache/.overlay").exists()
    }

    fun readOverlayId(): String? {
        val idFile = overlayIdFile()
        return if (idFile.isFile) idFile.readText().trim() else null
    }

    fun clearCodeCache() {
        val codeCache = File(packageDataDir(), "code_cache")
        if (codeCache.exists()) {
            codeCache.deleteRecursively()
        }
        installInvokeCount++
    }

    fun onInstallCompleted() {
        clearCodeCache()
    }

    fun hasDirectOverlayApply(): Boolean {
        return shellScripts.any { it.contains(DIRECT_OVERLAY_MARKER) }
    }

    fun hasAsStartupAgentPush(): Boolean = asStartupAgentPushCount > 0

    fun listStartupAgents(): List<String> {
        val dir = startupAgentsDir()
        if (!dir.isDirectory) {
            return emptyList()
        }
        return dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }

    fun studioDir(): File = File(packageDataDir(), "code_cache/.studio")

    fun startupAgentsDir(): File = File(packageDataDir(), "code_cache/startup_agents")

    fun remotePushFile(remotePath: String): File? = remotePushFiles[remotePath]

    /**
     * True when recover ran [DirectOverlayStateChecker] against [mismatchedOverlayId] before mock install
     * and before the first direct-overlay write script.
     */
    fun hadRecoverMatchedOverlayCheckBeforeInstall(expectedOverlayId: String): Boolean {
        val directWriteIndex = shellScripts.indexOfFirst { it.contains(DIRECT_OVERLAY_MARKER) }
        return overlayStateProbes.any { probe ->
            probe.installInvokeCountAtProbe == 0 &&
                probe.deviceOverlayId == expectedOverlayId &&
                (directWriteIndex < 0 || probe.scriptIndex < directWriteIndex)
        }
    }

    fun hadOverlayStateCheckWithDeviceId(deviceOverlayId: String): Boolean {
        return overlayStateProbes.any { it.deviceOverlayId == deviceOverlayId }
    }

    fun hadRecoverMismatchOverlayCheckBeforeInstallAndDirectWrite(mismatchedOverlayId: String): Boolean {
        val directWriteIndex = shellScripts.indexOfFirst { it.contains(DIRECT_OVERLAY_MARKER) }
        return overlayStateProbes.any { probe ->
            probe.installInvokeCountAtProbe == 0 &&
                probe.deviceOverlayId == mismatchedOverlayId &&
                (directWriteIndex < 0 || probe.scriptIndex < directWriteIndex)
        }
    }

    private fun execShellCmd(cmd: String): String {
        shellCommands += cmd
        return when {
            cmd.startsWith("mkdir -p /data/local/tmp/jugg") -> {
                File(root, "data/local/tmp/jugg").mkdirs()
                ""
            }
            cmd.startsWith("rm -f /data/local/tmp/jugg/direct-overlay-") -> {
                File(root, "data/local/tmp/jugg").listFiles()
                    ?.filter { it.name.startsWith("direct-overlay-") && it.name.endsWith(".zip") }
                    ?.forEach { it.delete() }
                ""
            }
            cmd.startsWith("rm -f /data/local/tmp/jugg/") -> {
                val remote = cmd.removePrefix("rm -f ").trim()
                remotePushFiles.remove(remote)?.delete()
                ""
            }
            cmd == "run-as $packageName rm -rf code_cache/.overlay" -> {
                File(packageDataDir(), "code_cache/.overlay").deleteRecursively()
                ""
            }
            cmd.contains("run-as $packageName") && cmd.contains("code_cache/startup_agents") && cmd.contains("ls") -> {
                val agents = listStartupAgents()
                if (agents.isEmpty()) {
                    "No such file or directory"
                } else {
                    agents.joinToString("\n")
                }
            }
            else -> ""
        }
    }

    private fun dispatchShellScript(cmd: String): String {
        shellScripts += cmd
        val runAs = VirtualDeployShellExecutor.parseRunAsShC(cmd) ?: return ""
        val (pkg, inner) = runAs
        if (pkg != packageName) {
            return ""
        }
        return executeRunAsInnerScript(inner)
    }

    private fun executeRunAsInnerScript(inner: String): String {
        val scriptIndex = shellScripts.size - 1
        return when {
            inner.contains(OVERLAY_STATE_MARKER) -> handleOverlayStateScript(scriptIndex)
            inner.contains(DIRECT_OVERLAY_MARKER) -> handleDirectOverlayScript(inner)
            else -> executeGenericRunAsScript(inner)
        }
    }

    private fun executeGenericRunAsScript(inner: String): String {
        val output = VirtualDeployShellExecutor.executeRunAsInner(this, inner)
        if (inner.contains("code_cache/startup_agents") && output.contains("$AS_AGENT_MARKER OK")) {
            asStartupAgentPushCount++
        }
        return output
    }

    private fun handleOverlayStateScript(scriptIndex: Int): String {
        overlayStateProbes += OverlayStateProbe(
            scriptIndex = scriptIndex,
            deviceOverlayId = readOverlayId(),
            installInvokeCountAtProbe = installInvokeCount,
        )
        val overlayDir = File(packageDataDir(), "code_cache/.overlay")
        if (!overlayDir.isDirectory) {
            return "$OVERLAY_STATE_MARKER NO_DIR"
        }
        val id = readOverlayId()
        return if (id == null) {
            "$OVERLAY_STATE_MARKER MISSING_ID"
        } else {
            "$OVERLAY_STATE_MARKER ID $id"
        }
    }

    private fun handleDirectOverlayScript(script: String): String {
        when (directOverlayWriteResult) {
            DirectOverlayWriteResult.SKIPPED -> return "$DIRECT_OVERLAY_MARKER SKIPPED"
            DirectOverlayWriteResult.APPLYING -> return "$DIRECT_OVERLAY_MARKER APPLYING"
            DirectOverlayWriteResult.OK -> Unit
        }
        val expectedId = Regex("""!= \"([^\"]*)\"""").find(script)?.groupValues?.get(1)
            ?: return "$DIRECT_OVERLAY_MARKER SKIPPED"
        val newOverlayId = Regex("""printf %s \"([^\"]+)\" >""").find(script)?.groupValues?.get(1)
            ?: return "$DIRECT_OVERLAY_MARKER SKIPPED"
        val remoteZip = extractRemoteZipPath(script) ?: return "$DIRECT_OVERLAY_MARKER SKIPPED"

        val actualId = readOverlayId().orEmpty()
        if (actualId != expectedId) {
            return "$DIRECT_OVERLAY_MARKER MISMATCH"
        }

        val localZip = remotePushFiles[remoteZip] ?: return "$DIRECT_OVERLAY_MARKER SKIPPED"
        val overlayDir = File(packageDataDir(), "code_cache/.overlay")
        overlayDir.mkdirs()
        overlayDir.listFiles()?.forEach { child ->
            if (child.name != "id") {
                child.deleteRecursively()
            }
        }
        File(overlayDir, "id").delete()

        unzipToDirectory(localZip, overlayDir)
        markOverlayDexReadOnly(overlayDir)
        writeOverlayId(newOverlayId)
        return "$DIRECT_OVERLAY_MARKER OK"
    }

    private fun extractRemoteZipPath(script: String): String? {
        val match = Regex("unzip -oq ([^ ]+) -d").find(script) ?: return null
        return match.groupValues[1].trim()
    }

    private fun unzipToDirectory(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(targetDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().buffered().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun markOverlayDexReadOnly(overlayDir: File) {
        overlayDir.walkTopDown()
            .filter { it.isFile && it.extension == "dex" }
            .forEach { it.setReadOnly() }
    }

    private class VirtualDeviceAdb(
        private val device: VirtualDeployDevice,
    ) : IDeviceAdb {
        override val displayName: String = "virtual"
        override val api: Int = 30
        override val serial: String = device.serial
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String = device.execShellCmd(cmd)

        override fun execAdbShellScript(cmd: String): String {
            device.shellCommands += VirtualDeployShellExecutor.wrapLikeIdeaDeviceAdb(cmd)
            return device.dispatchShellScript(cmd)
        }

        override fun push(from: File, to: String): Boolean {
            if (device.failDirectOverlayPush) {
                device.shellCommands += "push FAILED ${from.absolutePath} -> $to"
                return false
            }
            device.shellCommands += "push ${from.absolutePath} -> $to"
            val target = File(device.root, to.removePrefix("/"))
            target.parentFile?.mkdirs()
            from.copyTo(target, overwrite = true)
            device.remotePushFiles[to] = target
            return true
        }

        override fun pull(from: String, to: File): Boolean {
            val source = File(device.root, from.removePrefix("/"))
            if (!source.exists()) {
                return false
            }
            source.copyTo(to, overwrite = true)
            return true
        }

        override fun getDefaultLaunchActivity(apkFile: File): String? = "${device.packageName}.MainActivity"

        override fun getArch(packageName: String): String = "ARCH_64_BIT"

        override fun getProperty(name: String): String? = when (name) {
            "ro.product.cpu.abi" -> "arm64-v8a"
            "ro.product.manufacturer" -> device.manufacturer
            "hw_sc.build.platform.version" -> device.harmonyOsVersion
            else -> null
        }
    }

    companion object {
        private const val OVERLAY_STATE_MARKER = "__JUGG_OVERLAY_STATE__"
        private const val DIRECT_OVERLAY_MARKER = "__JUGG_DIRECT_OVERLAY__"
        private const val AS_AGENT_MARKER = "__JUGG_AS_AGENT__"
    }
}
