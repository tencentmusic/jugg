package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.logger.getInstance

/** Streams file-backed native libraries into the committed overlay after the ordinary swap. */
class NativeSandboxWriter(
    private val adb: IDeviceAdb,
    private val sandbox: AppSandboxExecutor,
    loggerArg: Logger,
) {
    private val logger = loggerArg.getInstance("NativeSandboxWriter")

    fun stage(request: NativeSandboxWriteRequest) {
        val files = expandFiles(request)
        try {
            files.forEach { file ->
                val remote = "$STAGING_ROOT/${request.sessionId}/${file.path}"
                try {
                    adb.execAdbShellCmd("mkdir -p ${remote.substringBeforeLast('/')}")
                    val source = file.item.sourceFileOrNull()
                        ?: throw IllegalArgumentException("Native library is not file-backed: ${file.item.name}")
                    if (!adb.push(source, remote)) {
                        throw IllegalStateException("adb push failed: $remote")
                    }
                } catch (e: Exception) {
                    throw NativeSandboxDeployException(NativeSandboxDeployStep.PUSH, "stage failed: $remote", e)
                }
            }
            val copies = files.joinToString("; ") { file ->
                val remote = "$STAGING_ROOT/${request.sessionId}/${file.path}"
                val target = "$TEMP_ROOT/${request.sessionId}/pending/${file.path}"
                "mkdir -p ${target.substringBeforeLast('/')} && cp -f $remote $target && " +
                    "actual=\$(wc -c < $target) && [ \"\$actual\" -eq ${file.item.size} ]"
            }
            val output = try {
                sandbox.execNoFallback("set -e; $copies; echo \"$MARKER OK\"", repairCodeCache = true)
            } catch (e: Exception) {
                throw NativeSandboxDeployException(NativeSandboxDeployStep.COPY, "native overlay stage failed", e)
            }
            if (output.lineSequence().none { it.trim() == "$MARKER OK" }) {
                throw NativeSandboxDeployException(NativeSandboxDeployStep.COPY, "sandbox stage failed: $output")
            }
        } catch (e: Exception) {
            discard(request)
            throw e
        } finally {
            runCatching { adb.execAdbShellCmd("rm -rf $STAGING_ROOT/${request.sessionId}") }
                .onFailure { logger.debug("Failed to cleanup native staging", it) }
        }
    }

    fun publish(request: NativeSandboxWriteRequest) {
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
                StagedFile("$apkName/${item.name}", item)
            }
        }.distinctBy { it.path }
    }

    private data class StagedFile(val path: String, val item: DeployItem)

    companion object {
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
