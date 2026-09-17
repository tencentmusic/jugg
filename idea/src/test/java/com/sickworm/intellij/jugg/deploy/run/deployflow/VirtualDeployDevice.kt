package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.android.ddmlib.IDevice
import com.android.sdklib.AndroidVersion
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.direct.RootlessCompatDeployArchive
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
    var apiLevel: Int = 30
    var processArch: String = "ARCH_64_BIT"
    var installedPrimaryCpuAbi: String? = null
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
    var flutterCacheInvalidationCount: Int = 0
        private set

    /** App restarts recorded through [onAppRestart]; used to check deploy step ordering. */
    var appRestartCount: Int = 0
        private set
    var appRestartCountAtFlutterCacheInvalidation: Int = -1
        private set

    private val remotePushFiles = mutableMapOf<String, File>()
    private val appLogLines = mutableListOf<String>()

    /** Number of staged rootless compat requests the emulated app imported successfully. */
    var rootlessImportCount: Int = 0
        private set

    /** Number of staged rootless compat requests the emulated app rejected. */
    var rootlessImportFailureCount: Int = 0
        private set

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
        Mockito.`when`(device.version).thenAnswer { AndroidVersion(apiLevel, null) }
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

    fun onAppRestart() {
        appRestartCount++
    }

    fun rootlessCompatPackageDir(): File =
        File(root, "sdcard/Android/data/$packageName/files/jugg/rootless-compat")

    fun stagedRootlessRequestDirs(): List<File> {
        return rootlessCompatPackageDir().listFiles()
            ?.filter { it.isDirectory && File(it, ROOTLESS_READY_FILE).isFile }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /** Corrupts the staged payload so the emulated app-side digest check has to reject the request. */
    fun corruptStagedRootlessPayload(): Boolean {
        val payload = stagedRootlessRequestDirs().firstOrNull()
            ?.let { File(it, ROOTLESS_PAYLOAD_FILE) } ?: return false
        payload.appendBytes(byteArrayOf(0x2a))
        return true
    }

    /**
     * Emulates the app-side importer executed from `BootstrapApplication` on the next process start:
     * it validates the staged request, commits `code_cache/.overlay` and reports the result on the
     * `jugg-agent` log tag, which is the only channel the host can read back.
     */
    fun runRootlessCompatImport(): Boolean {
        val requestDir = stagedRootlessRequestDirs().lastOrNull() ?: return false
        val requestId = requestDir.name
        var stage = "metadata"
        return try {
            val metadata = File(requestDir, ROOTLESS_REQUEST_FILE).readLines()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
            require(metadata["protocolVersion"] == "1")
            require(metadata["packageName"] == packageName)
            require(metadata["requestId"] == requestId)
            val payload = File(requestDir, ROOTLESS_PAYLOAD_FILE)
            stage = "digest"
            require(RootlessCompatDeployArchive.sha256(payload.readBytes()) == metadata["payloadSha256"])
            stage = "overlay-state"
            val expectedOverlayId = metadata.getValue("expectedOverlayId")
            require(expectedOverlayId == readOverlayId().orEmpty())
            stage = "commit"
            commitRootlessPayload(payload, expectedOverlayId, metadata.getValue("isFullResourcePush").toBoolean())
            writeOverlayId(metadata.getValue("nextOverlayId"))
            rootlessImportCount++
            logRootlessResult(requestId, null, "")
            true
        } catch (e: Exception) {
            rootlessImportFailureCount++
            logRootlessResult(requestId, stage, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun commitRootlessPayload(payload: File, expectedOverlayId: String, isFullResourcePush: Boolean) {
        val overlayDir = File(packageDataDir(), "code_cache/.overlay")
        if (expectedOverlayId.isNotEmpty()) {
            File(overlayDir, "id").delete()
            zipEntryNames(payload)
                .filterNot { isFullResourcePush && it.startsWith("base.apk/") }
                .forEach { File(overlayDir, it).delete() }
        }
        unzipToDirectory(payload, overlayDir)
        markOverlayDexReadOnly(overlayDir)
    }

    private fun logRootlessResult(requestId: String, stage: String?, detail: String) {
        val marker = RootlessCompatDeployArchive.IMPORT_RESULT_MARKER
        val line = if (stage == null) {
            "$marker OK $requestId"
        } else {
            "$marker FAILED $requestId $stage $detail"
        }
        appLogLines += "01-01 00:00:00.000  1000  1000 I jugg-agent: $line"
    }

    private fun zipEntryNames(zipFile: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    names += entry.name
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return names
    }

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
            cmd.startsWith("dumpsys package ") -> "primaryCpuAbi=${installedPrimaryCpuAbi ?: "arm64-v8a"}"
            cmd.startsWith("logcat") -> appLogLines.joinToString("\n")
            cmd.startsWith("mkdir -p /data/local/tmp/jugg") -> {
                val remote = cmd.removePrefix("mkdir -p ").trim()
                File(root, remote.removePrefix("/")).mkdirs()
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
            cmd.startsWith("rm -rf /data/local/tmp/jugg/") -> {
                val remote = cmd.removePrefix("rm -rf ").trim()
                remotePushFiles.remove(remote)?.delete()
                File(root, remote.removePrefix("/")).deleteRecursively()
                ""
            }
            cmd.startsWith("rm -rf /sdcard/Android/data/") -> {
                val remote = cmd.removePrefix("rm -rf ").trim()
                remotePushFiles.keys
                    .filter { it == remote || it.startsWith("$remote/") }
                    .forEach { remotePushFiles.remove(it)?.delete() }
                File(root, remote.removePrefix("/")).deleteRecursively()
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
            inner.contains(RUN_AS_MARKER) ->
                "$RUN_AS_MARKER:10001\n$RUN_AS_CONTEXT_MARKER:ctx|ctx"
            inner.contains(OVERLAY_STATE_MARKER) -> handleOverlayStateScript(scriptIndex)
            inner.contains(DIRECT_OVERLAY_MARKER) -> handleDirectOverlayScript(inner)
            else -> executeGenericRunAsScript(inner)
        }
    }

    private fun executeGenericRunAsScript(inner: String): String {
        if (inner.contains(FLUTTER_TIMESTAMP_PATH)) {
            flutterCacheInvalidationCount++
            appRestartCountAtFlutterCacheInvalidation = appRestartCount
        }
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
        override val api: Int
            get() = device.apiLevel
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

        override fun getArch(packageName: String): String = device.processArch

        override fun getProperty(name: String): String? = when (name) {
            "ro.product.cpu.abi" -> "arm64-v8a"
            "ro.product.manufacturer" -> device.manufacturer
            "hw_sc.build.platform.version" -> device.harmonyOsVersion
            else -> null
        }
    }

    companion object {
        /** Flutter extraction cache location touched by the JIT cache invalidation command. */
        private const val FLUTTER_TIMESTAMP_PATH = "app_flutter/res_timestamp-"
        private const val OVERLAY_STATE_MARKER = "__JUGG_OVERLAY_STATE__"
        private const val DIRECT_OVERLAY_MARKER = "__JUGG_DIRECT_OVERLAY__"
        private const val AS_AGENT_MARKER = "__JUGG_AS_AGENT__"
        private const val RUN_AS_MARKER = "__JUGG_RUN_AS_OK__"
        private const val RUN_AS_CONTEXT_MARKER = "__JUGG_RUN_AS_CONTEXT__"

        /** Rootless compat request files, see [RootlessCompatDeployArchive]. */
        private const val ROOTLESS_PAYLOAD_FILE = "payload.zip"
        private const val ROOTLESS_REQUEST_FILE = "request.properties"
        private const val ROOTLESS_READY_FILE = "ready"
    }
}
