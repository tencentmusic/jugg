package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File

/**
 * Pushes changed native libraries into the app sandbox without rewriting the APK.
 */
class NativeSandboxWriter(
    private val adb: IDeviceAdb,
    private val sandbox: AppSandboxExecutor,
    loggerArg: Logger,
) {

    private val logger = loggerArg.getInstance("NativeSandboxWriter")

    fun write(request: NativeSandboxWriteRequest) {
        require(PACKAGE_NAME_PATTERN.matches(request.packageName)) {
            "Unsafe package name: ${request.packageName}"
        }
        require(SESSION_PATTERN.matches(request.sessionId)) {
            "Unsafe native sandbox session: ${request.sessionId}"
        }
        val stagedFiles = mutableListOf<StagedFile>()
        try {
            request.abiDirs.forEach { (abi, items) ->
                require(abi in LEGAL_ABIS) { "Unsafe abi: $abi" }
                val stagingDir = "$STAGING_ROOT/${request.sessionId}/$abi"
                mkdirStaging(stagingDir)
                items.forEach { item ->
                    val abiName = NativeSandboxDeployPlanner.parseAbi(item.name)
                        ?: throw NativeSandboxDeployException(NativeSandboxDeployStep.PUSH, "unsafe so path ${item.name}")
                    require(abiName == abi) { "Abi mismatch for ${item.name}" }
                    val fileName = item.name.substringAfterLast('/')
                    val remotePath = "$stagingDir/$fileName"
                    pushFile(item, remotePath)
                    stagedFiles += StagedFile(abi, fileName, remotePath, item.content.size)
                }
            }
            copyIntoSandbox(request.packageName, stagedFiles)
        } finally {
            cleanupStaging(request.sessionId)
        }
    }

    fun bestEffortRemovePatchFiles(abiDirs: Map<String, List<DeployItem>>) {
        val remove = buildRemoveCommands(abiDirs)
        if (remove.isEmpty()) {
            return
        }
        try {
            sandbox.exec("$remove && echo success")
        } catch (e: Exception) {
            logger.debug("Failed to remove native sandbox patch files", e)
        }
    }

    fun bestEffortSetEnabled(enabled: Boolean): Boolean {
        val command = if (enabled) {
            "mkdir -p $SANDBOX_DIR && touch $SANDBOX_DIR/$ENABLED_FLAG && echo success"
        } else {
            "rm -f $SANDBOX_DIR/$ENABLED_FLAG && echo success"
        }
        return try {
            sandbox.exec(command).trim() == "success"
        } catch (e: Exception) {
            logger.debug("Failed to update native sandbox enabled flag", e)
            false
        }
    }

    private fun mkdirStaging(stagingDir: String) {
        try {
            adb.execAdbShellCmd("mkdir -p $stagingDir")
        } catch (e: Exception) {
            throw NativeSandboxDeployException(NativeSandboxDeployStep.STAGING, "mkdir $stagingDir failed", e)
        }
    }

    private fun pushFile(item: DeployItem, remotePath: String) {
        val local = File.createTempFile("jugg-native-", ".so")
        try {
            local.writeBytes(item.content)
            if (!adb.push(local, remotePath)) {
                throw NativeSandboxDeployException(NativeSandboxDeployStep.PUSH, "adb push failed: $remotePath")
            }
        } catch (e: NativeSandboxDeployException) {
            throw e
        } catch (e: Exception) {
            throw NativeSandboxDeployException(NativeSandboxDeployStep.PUSH, "adb push failed: $remotePath", e)
        } finally {
            local.delete()
        }
    }

    private fun copyIntoSandbox(packageName: String, stagedFiles: List<StagedFile>) {
        if (stagedFiles.isEmpty()) {
            throw NativeSandboxDeployException(NativeSandboxDeployStep.COPY, "no staged native files")
        }
        val script = buildCopyScript(stagedFiles)
        val output = try {
            sandbox.execNoFallback(script, repairCodeCache = true)
        } catch (e: Exception) {
            val step = if (e.message.orEmpty().contains("repair code_cache")) {
                NativeSandboxDeployStep.SELINUX
            } else {
                NativeSandboxDeployStep.COPY
            }
            throw NativeSandboxDeployException(step, "sandbox copy failed for $packageName", e)
        }
        if (!output.lineSequence().any { it.trim() == "$MARKER OK" }) {
            throw NativeSandboxDeployException(NativeSandboxDeployStep.COPY, "sandbox copy failed for $packageName: $output")
        }
    }

    private fun buildCopyScript(stagedFiles: List<StagedFile>): String {
        val copies = stagedFiles.joinToString("; ") { file ->
            val destDir = "$SANDBOX_DIR/${file.abi}"
            "mkdir -p $destDir && " +
                "cp -f ${file.remotePath} $destDir/${file.fileName} && " +
                "actual=\$(wc -c < $destDir/${file.fileName}) && " +
                "[ \"\$actual\" -eq ${file.size} ]"
        }
        return "set -e; $copies; touch $SANDBOX_DIR/$ENABLED_FLAG; echo \"$MARKER OK\""
    }

    private fun buildRemoveCommands(abiDirs: Map<String, List<DeployItem>>): String {
        return abiDirs.flatMap { (abi, items) ->
            if (abi !in LEGAL_ABIS) {
                emptyList()
            } else {
                items.mapNotNull { item -> patchFileRemoveCommand(abi, item) }
            }
        }.joinToString("; ")
    }

    private fun patchFileRemoveCommand(abi: String, item: DeployItem): String? {
        val parsedAbi = NativeSandboxDeployPlanner.parseAbi(item.name) ?: return null
        if (parsedAbi != abi) {
            return null
        }
        val fileName = item.name.substringAfterLast('/')
        return "rm -f $SANDBOX_DIR/$abi/$fileName"
    }

    private fun cleanupStaging(sessionId: String) {
        try {
            adb.execAdbShellCmd("rm -rf $STAGING_ROOT/$sessionId")
        } catch (e: Exception) {
            logger.debug("Failed to cleanup native sandbox staging $sessionId", e)
        }
    }

    private data class StagedFile(
        val abi: String,
        val fileName: String,
        val remotePath: String,
        val size: Int,
    )

    companion object {
        const val MARKER = "__JUGG_NATIVE_SANDBOX__"
        const val STAGING_ROOT = "/data/local/tmp/jugg/nativeLib"
        const val SANDBOX_DIR = "code_cache/.jugg_native"
        const val ENABLED_FLAG = ".enabled"
        private val LEGAL_ABIS = setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86")
        private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val SESSION_PATTERN = Regex("[A-Za-z0-9_-]+")
    }
}

enum class NativeSandboxDeployStep {
    STAGING,
    PUSH,
    COPY,
    SELINUX,
    CLEANUP_PATCH,
}

class NativeSandboxDeployException(
    val step: NativeSandboxDeployStep,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
