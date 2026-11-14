package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Streams file-backed native libraries into the committed overlay after the ordinary swap. */
class NativeSandboxWriter(
    private val adb: IDeviceAdb,
    private val sandbox: AppSandboxExecutor,
    loggerArg: Logger,
    private val delta: NativeLibraryDelta,
    private val compileUiHandler: CompileUiHandler = CompileUiHandler.DEFAULT,
) {
    private val logger = loggerArg.getInstance("NativeSandboxWriter")

    fun stage(request: NativeSandboxWriteRequest) {
        val files = expandFiles(request)
        try {
            files.forEach { stageFile(request, it) }
        } catch (e: Exception) {
            discard(request)
            throw e
        } finally {
            runCatching { adb.execAdbShellCmd("rm -rf $STAGING_ROOT/${request.sessionId}") }
                .onFailure { logger.debug("Failed to cleanup native staging", it) }
        }
    }

    private fun stageFile(request: NativeSandboxWriteRequest, file: StagedFile) {
        checkCanceled()
        val remote = "$STAGING_ROOT/${request.sessionId}/${file.path}"
        val pending = "$TEMP_ROOT/${request.sessionId}/pending/${file.path}"
        val source = try {
            requireNotNull(file.item.sourceFileOrNull()) { "Native library is not file-backed: ${file.item.name}" }
        } catch (e: IllegalStateException) {
            throw NativeSandboxDeployException(NativeSandboxDeployStep.PUSH, "source unavailable: ${file.path}", e)
        }
        adb.execAdbShellCmd("mkdir -p ${remote.substringBeforeLast('/')}")
        if (file.baseline?.isFile != true) {
            logger.info("No large SO delta baseline for ${file.item.name}; transferring the full file may take longer.")
        } else if (stageDelta(request, file, source, remote, pending)) {
            return
        }
        checkCanceled()
        file.item.sourceFileOrNull()
        val start = System.currentTimeMillis()
        push(source, remote)
        logger.debug("Large SO full transfer: ${file.item.name}, ${file.item.size} bytes, " +
                "cost=${System.currentTimeMillis() - start}ms.")
        checkCanceled()
        copyFullSource(file, remote, pending)
    }

    private fun copyFullSource(file: StagedFile, remote: String, pending: String) {
        val start = System.currentTimeMillis()
        val output = try {
            sandbox.execNoFallback("set -e; mkdir -p ${pending.substringBeforeLast('/')} && " +
                "cp -f $remote $pending && actual=\$(wc -c < $pending) && " +
                "[ \"\$actual\" -eq ${file.item.size} ] && echo \"$MARKER OK\"", repairCodeCache = true)
        } catch (e: Exception) {
            checkCanceled()
            if (e is CancellationException || e is InterruptedException) throw e
            throw NativeSandboxDeployException(NativeSandboxDeployStep.COPY, "native overlay stage failed", e)
        }
        if (output.lineSequence().none { it.trim() == "$MARKER OK" }) {
            throw NativeSandboxDeployException(NativeSandboxDeployStep.COPY, "sandbox stage failed: $output")
        }
        checkCanceled()
        logger.debug("Large SO sandbox copy: ${file.item.name}, cost=${System.currentTimeMillis() - start}ms.")
    }

    /** Delta failures stay local to staging; the existing publication and rollback remain unchanged. */
    private fun stageDelta(request: NativeSandboxWriteRequest, file: StagedFile, source: File,
        remote: String, pending: String): Boolean {
        val old = "$OVERLAY_ROOT/${file.path}"
        val exists = sandbox.execNoFallback("if [ -f $old ]; then echo '$DELTA_MARKER BASE'; fi")
        if (!hasMarker(exists, "BASE")) return fullTransfer(file, "device baseline is missing")
        val abi = adb.getProperty("ro.product.cpu.abi")?.trim().orEmpty()
        val tool = try {
            delta.deviceTool(abi)
        } catch (e: IOException) {
            logger.warn("Cannot prepare native patcher; using full transfer.", e)
            null
        } ?: return fullTransfer(file, "patcher unavailable for ABI $abi")
        val remoteTool = prepareDeviceTool(tool, abi, "$remote.patcher", "$TEMP_ROOT/${request.sessionId}/hpatchz")
        val probe = sandbox.execNoFallback("if $remoteTool -v; then echo '$DELTA_MARKER TOOL'; fi")
        if (!hasMarker(probe, "TOOL")) {
            logger.warn("Native patcher cannot execute in ${sandbox.mode}; using full transfer: $probe")
            return false
        }
        val workDir = Files.createTempDirectory("jugg-native-delta-").toFile()
        try {
            val patch = try {
                delta.createPatch(requireNotNull(file.baseline), source, workDir)
            } catch (e: IOException) {
                logger.warn("Native delta generation failed; using full transfer.", e)
                return false
            } ?: return fullTransfer(file, "delta unavailable or not smaller than the full file")
            file.item.sourceFileOrNull()
            return applyDelta(file, patch, old, remote, remoteTool, pending)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun applyDelta(file: StagedFile, patch: NativeLibraryDelta.Patch, old: String,
        remote: String, remoteTool: String, pending: String): Boolean {
        checkCanceled()
        val hashStart = System.currentTimeMillis()
        val hashOutput = sandbox.execNoFallback("hash=\$(sha256sum $old) && " +
            "echo \"$DELTA_MARKER HASH \${hash%% *}\"")
        logger.debug("Large SO device baseline check: ${file.item.name}, " +
                "cost=${System.currentTimeMillis() - hashStart}ms.")
        if (!hasMarker(hashOutput, "HASH ${patch.oldSha256}")) {
            return fullTransfer(file, "device baseline differs or checksum is unavailable")
        }
        val transferStart = System.currentTimeMillis()
        push(patch.file, "$remote.patch")
        logger.debug("Large SO delta transfer: ${file.item.name}, ${patch.file.length()}/${file.item.size} bytes, " +
                "cost=${System.currentTimeMillis() - transferStart}ms.")
        checkCanceled()
        val applyStart = System.currentTimeMillis()
        val output = sandbox.execNoFallback("mkdir -p ${pending.substringBeforeLast('/')} || exit 1; " +
            "$remoteTool -s-8m $old $remote.patch $pending; result=\$?; " +
            "echo \"$DELTA_MARKER STATUS \$result\"; " +
            "if [ \"\$result\" = 0 ]; then hash=\$(sha256sum $pending) && " +
            "echo \"$DELTA_MARKER HASH \${hash%% *}\"; fi", repairCodeCache = true)
        checkCanceled()
        logger.debug("Large SO patch and verification: ${file.item.name}, " +
                "cost=${System.currentTimeMillis() - applyStart}ms.")
        if (hasMarker(output, "STATUS 0") && hasMarker(output, "HASH ${patch.newSha256}")) return true
        val code = output.lineSequence().map { it.trim() }.firstOrNull {
            it.startsWith("$DELTA_MARKER STATUS ")
        }?.substringAfterLast(' ')?.toIntOrNull()
        // Do not turn storage, file I/O, cancellation or transport failures into another large push.
        if (code !in setOf(0, 1, 8, 9, 10, 11, 16, 20, 21, 22, 23, 126, 127)) {
            throw NativeSandboxDeployException(NativeSandboxDeployStep.COPY, "Native patch failed: $output")
        }
        logger.warn("Native patch or content verification failed; using full transfer: $output")
        val cleanup = sandbox.execNoFallback("rm -f $pending && echo '$DELTA_MARKER CLEAN'")
        check(hasMarker(cleanup, "CLEAN")) { "Cannot discard incomplete native patch: $cleanup" }
        return false
    }

    private fun prepareDeviceTool(tool: File, abi: String, temporaryPath: String, sandboxPath: String): String {
        val path = "/data/local/tmp/jugg/hdiffpatch/${NativeLibraryDelta.VERSION}/$abi/hpatchz"
        val probe = adb.execAdbShellCmd("if [ -x $path ]; then echo '$DELTA_MARKER TOOL'; fi")
        if (!hasMarker(probe, "TOOL")) {
            adb.execAdbShellCmd("mkdir -p ${path.substringBeforeLast('/')}")
            push(tool, temporaryPath)
            val output = adb.execAdbShellCmd("chmod 755 $temporaryPath && mv $temporaryPath $path && " +
                "echo '$DELTA_MARKER TOOL'")
            check(hasMarker(output, "TOOL")) { "Cannot install native patcher: $output" }
        }
        // RUN_AS may be denied execution from shell data; create its executable copy in the app sandbox.
        val staged = sandbox.execNoFallback("mkdir -p ${sandboxPath.substringBeforeLast('/')} && " +
            "cp -f $path $sandboxPath && chmod 700 $sandboxPath && echo '$DELTA_MARKER STAGED_TOOL'",
            repairCodeCache = true)
        checkCanceled()
        if (!hasMarker(staged, "STAGED_TOOL")) {
            throw NativeSandboxDeployException(NativeSandboxDeployStep.COPY, "Cannot stage native patcher: $staged")
        }
        return sandboxPath
    }

    private fun push(file: File, remote: String) {
        checkCanceled()
        if (!adb.push(file, remote)) {
            checkCanceled()
            throw NativeSandboxDeployException(NativeSandboxDeployStep.PUSH, "adb push failed: $remote")
        }
    }

    private fun checkCanceled() {
        if (compileUiHandler.isCanceled || Thread.currentThread().isInterrupted) {
            throw CancellationException("Large SO transfer canceled")
        }
    }

    private fun fullTransfer(file: StagedFile, reason: String): Boolean {
        logger.info("Large SO ${file.item.name}: $reason; transferring the full file may take longer.")
        return false
    }

    private fun hasMarker(output: String, suffix: String) =
        output.lineSequence().any { it.trim() == "$DELTA_MARKER $suffix" }

    fun publish(request: NativeSandboxWriteRequest) {
        checkCanceled()
        val start = System.currentTimeMillis()
        val files = expandFiles(request)
        val commands = files.joinToString("; ") { file ->
            val target = "$OVERLAY_ROOT/${file.path}"
            val pending = "$TEMP_ROOT/${request.sessionId}/pending/${file.path}"
            val backup = "$TEMP_ROOT/${request.sessionId}/backup/${file.path}"
            "mkdir -p ${target.substringBeforeLast('/')} ${backup.substringBeforeLast('/')} && " +
                "if [ -e $target ]; then mv $target $backup; fi && " +
                "touch $backup.published && mv $pending $target"
        }
        val output = try {
            sandbox.execNoFallback("set -e; $commands; echo \"$MARKER OK\"", repairCodeCache = true)
        } catch (e: Exception) {
            throw NativeSandboxDeployException(NativeSandboxDeployStep.COPY, "native overlay publish failed", e)
        }
        if (output.lineSequence().none { it.trim() == "$MARKER OK" }) {
            throw NativeSandboxDeployException(NativeSandboxDeployStep.COPY, "native overlay publish failed: $output")
        }
        logger.debug("Large SO publication finished, cost=${System.currentTimeMillis() - start}ms.")
    }

    fun rollback(request: NativeSandboxWriteRequest) {
        val commands = expandFiles(request).asReversed().joinToString("; ") { file ->
            val target = "$OVERLAY_ROOT/${file.path}"
            val backup = "$TEMP_ROOT/${request.sessionId}/backup/${file.path}"
            "if [ -e $backup ]; then rm -f $target; mv $backup $target; " +
                "elif [ -e $backup.published ]; then rm -f $target; fi"
        }
        sandbox.execNoFallback("set -e; $commands; echo \"$MARKER OK\"", repairCodeCache = true)
    }

    fun discard(request: NativeSandboxWriteRequest) {
        runCatching { sandbox.execNoFallback("rm -rf $TEMP_ROOT/${request.sessionId} && echo success") }
            .onFailure { logger.debug("Failed to cleanup native overlay staging", it) }
    }

    private fun expandFiles(request: NativeSandboxWriteRequest): List<StagedFile> {
        require(PACKAGE_NAME_PATTERN.matches(request.packageName)) { "Unsafe package name: ${request.packageName}" }
        require(SESSION_PATTERN.matches(request.sessionId)) { "Unsafe native session: ${request.sessionId}" }
        return request.files.flatMap { item ->
            require(item.isFileBacked && NATIVE_PATH.matches(item.name)) { "Unsafe native file: ${item.name}" }
            val targetPaths = item.targetApkPaths.ifEmpty { listOf(item.apkPath) }
            targetPaths.map { apkPath ->
                val apkName = request.apkNamesByPath[apkPath]
                    ?: throw IllegalArgumentException("Unknown APK scope for ${item.name}: $apkPath")
                require(APK_NAME.matches(apkName) && !apkName.contains("..")) { "Unsafe APK name: $apkName" }
                StagedFile("$apkName/${item.name}", item, delta.baseline(apkPath, item.name))
            }
        }.distinctBy { it.path }
    }

    private data class StagedFile(val path: String, val item: DeployItem, val baseline: File?)

    companion object {
        private const val DELTA_MARKER = "__JUGG_NATIVE_DELTA__"
        const val MARKER = "__JUGG_NATIVE_OVERLAY__"
        const val STAGING_ROOT = "/data/local/tmp/jugg/nativeLib"
        const val TEMP_ROOT = "code_cache/.jugg_native_stage"
        const val OVERLAY_ROOT = "code_cache/.overlay"
        private val NATIVE_PATH = Regex("lib/(arm64-v8a|armeabi-v7a|armeabi|x86_64|x86)/lib[^/]+\\.so")
        private val APK_NAME = Regex("[A-Za-z0-9_.-]+\\.apk")
        private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val SESSION_PATTERN = Regex("[A-Za-z0-9_-]+")
    }
}

enum class NativeSandboxDeployStep {
    PUSH,
    COPY,
}

class NativeSandboxDeployException(
    val step: NativeSandboxDeployStep,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
