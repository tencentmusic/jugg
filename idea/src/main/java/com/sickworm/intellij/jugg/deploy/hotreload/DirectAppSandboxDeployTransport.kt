package com.sickworm.intellij.jugg.deploy.hotreload

import com.android.tools.deploy.proto.Deploy
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.deploy.DirectHotReloadClass
import com.sickworm.intellij.jugg.deploy.DirectHotReloadWriter
import com.sickworm.intellij.jugg.deploy.JuggJvmtiAgentManager
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayDeployFailedException
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayDirtyException
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateCheckResult
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateChecker
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayWriteRequestBuilder
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayWriteResult
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayWriter
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayId
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import com.sickworm.intellij.jugg.deploy.run.LaunchContext
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig

/**
 * Deploys incremental overlays when Android Studio Apply Changes cannot use the app sandbox.
 */
class DirectAppSandboxDeployTransport(
    private val launchContext: LaunchContext,
    loggerArg: Logger,
) {

    private val logger = loggerArg.getInstance("DirectAppSandboxDeployTransport")

    fun canTry(packageName: String): Boolean {
        return launchContext.deviceAdb.api >= 26 &&
            launchContext.getAppSandboxExecutor(packageName, logger).applyChangesCapability ==
            AppSandboxExecutor.ApplyChangesCapability.INCOMPATIBLE
    }

    fun tryDeploy(
        packageName: String,
        data: JuggDeployData,
        overlayUpdate: JuggOverlayUpdate?,
        asDeployerCompat: IAsDeployerCompat,
        pids: List<Int>,
        appArch: Deploy.Arch,
    ): DirectAppSandboxDeployResult? {
        if (!canTry(packageName)) {
            return null
        }
        val sandbox = launchContext.getAppSandboxExecutor(packageName, logger)
        if (sandbox.mode == AppSandboxExecutor.Mode.UNAVAILABLE) {
            throw DirectOverlayDeployFailedException(
                "Direct app sandbox unavailable for $packageName: ${sandbox.unavailableReason}",
            )
        }
        val requiredOverlayUpdate = overlayUpdate ?: throw DirectOverlayDeployFailedException(
            "Direct app sandbox deploy requires an existing deployment cache for $packageName.",
        )

        logger.debug("Direct app sandbox deploy selected: package=$packageName, mode=${sandbox.mode}, arch=$appArch")
        prepareStartupAgent(packageName, sandbox)
        val overlayId = writeOverlay(packageName, requiredOverlayUpdate, asDeployerCompat, sandbox, data.isFullRes)
        return finishDeploy(packageName, data, pids, sandbox, overlayId)
    }

    private fun prepareStartupAgent(packageName: String, sandbox: AppSandboxExecutor) {
        if (!JuggJvmtiAgentManager(launchContext.deviceAdb, logger).pushAgentToApp(packageName, sandbox)) {
            throw DirectOverlayDeployFailedException("Direct app sandbox startup agent preparation failed.")
        }
        val result = sandbox.exec("touch code_cache/${BuildConfig.DIRECT_RESOURCE_OVERLAY_FLAG_FILE} && echo success",
            repairCodeCache = true)
        if (result.trim() != "success") {
            throw DirectOverlayDeployFailedException("Direct app sandbox resource loader preparation failed: $result")
        }
    }

    private fun writeOverlay(
        packageName: String,
        overlayUpdate: JuggOverlayUpdate,
        asDeployerCompat: IAsDeployerCompat,
        sandbox: AppSandboxExecutor,
        isFullResourcePush: Boolean,
    ): JuggOverlayId {
        val expectedDeviceOverlayId = overlayUpdate.cachedDump.overlayId.let {
            if (it.isBaseInstall) "" else it.sha
        }
        val state = DirectOverlayStateChecker(
            adb = launchContext.deviceAdb,
            logger = logger,
            sandboxExecutor = sandbox,
            propagateFailure = true,
        ).checkDevice(packageName, expectedDeviceOverlayId)
        if (state != DirectOverlayStateCheckResult.MATCHED) {
            throw DirectOverlayDeployFailedException("Direct app sandbox overlay state mismatch: $state")
        }
        val prepared = DirectOverlayWriteRequestBuilder(logger).build(
            packageName = packageName,
            overlayUpdate = overlayUpdate,
            asDeployerCompat = asDeployerCompat,
            isFullResourcePush = isFullResourcePush,
        )
        return when (DirectOverlayWriter(launchContext.deviceAdb, logger, sandbox).write(prepared.request)) {
            DirectOverlayWriteResult.SUCCESS -> prepared.overlayId
            DirectOverlayWriteResult.SKIPPED -> throw DirectOverlayDeployFailedException(
                "Direct app sandbox overlay write failed before commit.",
            )
            DirectOverlayWriteResult.FAILED_DIRTY -> throw DirectOverlayDirtyException(
                "Direct app sandbox overlay write failed after mutation.",
            )
        }
    }

    private fun finishDeploy(
        packageName: String,
        data: JuggDeployData,
        pids: List<Int>,
        sandbox: AppSandboxExecutor,
        overlayId: JuggOverlayId,
    ): DirectAppSandboxDeployResult {
        val refreshResources = data.overlays.isNotEmpty()
        restartReason(data)?.let { reason ->
            logger.info(reason)
            return DirectAppSandboxDeployResult(overlayId, needsRestart = true)
        }
        if (refreshResources && launchContext.deviceAdb.api < 30) {
            logger.info("Direct resource refresh requires Android 11 or newer; restart to load committed overlay.")
            return DirectAppSandboxDeployResult(overlayId, needsRestart = true)
        }
        val pid = findMainProcessPid(packageName, pids)
        if (pid == null) {
            if (!hasRuntimeChanges(data)) {
                logger.debug("Direct app sandbox completed with no running process or runtime changes.")
                return DirectAppSandboxDeployResult(overlayId, needsRestart = false)
            }
            logger.info("Direct app sandbox target is not running; restart to load committed overlay.")
            return DirectAppSandboxDeployResult(overlayId, needsRestart = true)
        }
        val newClasses = data.newClasses.map { clazz ->
            DirectHotReloadClass(clazz.name, clazz.content)
        }
        val modifiedClasses = data.hotReloadModifiedClasses.map { clazz ->
            val descriptor = clazz.classNodes.singleOrNull()?.className
            if (descriptor == null) {
                logger.info("Direct Hot Reload requires one class per dex; restart to load overlay: ${clazz.name}")
                return DirectAppSandboxDeployResult(overlayId, needsRestart = true)
            }
            DirectHotReloadClass(descriptor, clazz.content)
        }
        val hotReloadResult = DirectHotReloadWriter(launchContext.deviceAdb, logger, sandbox)
            .apply(packageName, pid, newClasses, modifiedClasses, refreshResources, data.isNeedRestartActivity)
        if (hotReloadResult.success) {
            logger.debug("Direct app sandbox Hot Reload succeeded: ${hotReloadResult.detail}")
            return DirectAppSandboxDeployResult(overlayId, needsRestart = false)
        }
        logger.info(runtimeFallbackMessage(hotReloadResult.detail))
        return DirectAppSandboxDeployResult(overlayId, needsRestart = true)
    }

    private fun restartReason(data: JuggDeployData): String? {
        return when {
            data.hotFixModifiedClasses.isNotEmpty() ->
                "Direct app sandbox class structure changes require app restart to load the committed overlay."
            data.updateApkFiles.isNotEmpty() ->
                "Direct app sandbox APK updates require app restart after overlay commit."
            data.isRecoverReplayAfterReinstall ->
                "Direct app sandbox recovery replay requires app restart after overlay commit."
            data.isCompatDeploy ->
                "Direct app sandbox compatibility deployment requires app restart after overlay commit."
            data.isInstall ->
                "Direct app sandbox installation requires app restart after overlay commit."
            data.isPushOverlayOnly && !data.isEmpty ->
                "Direct app sandbox overlay-only deployment requires app restart after overlay commit."
            data.overlays.any(::isApkRootOverlay) ->
                "Direct app sandbox APK-root overlay changes require app restart after overlay commit."
            data.isComposeResourceCompiled && !data.isEmpty ->
                "Direct app sandbox Compose resource changes require app restart after overlay commit."
            else -> null
        }
    }

    private fun isApkRootOverlay(item: DeployItem): Boolean {
        return !item.name.startsWith("res/") &&
            !item.name.startsWith("assets/") &&
            item.name != "resources.arsc"
    }

    private fun hasRuntimeChanges(data: JuggDeployData): Boolean {
        return hasClassRuntimeChanges(data) || data.overlays.isNotEmpty()
    }

    private fun hasClassRuntimeChanges(data: JuggDeployData): Boolean {
        return data.newClasses.isNotEmpty() || data.hotReloadModifiedClasses.isNotEmpty()
    }

    private fun runtimeFallbackMessage(detail: String): String {
        if (detail.startsWith("ERROR\tdefine_new_classes\t")) {
            val cause = detail.split('\t', limit = 4).getOrNull(3)
                ?.removePrefix("defining new classes failed: ")
                ?.takeUnless { it.isBlank() || it == "defining new classes failed" }
            val message = "Direct app sandbox could not add new classes to the running process; restarting the " +
                    "app to activate the committed class overlay."
            return cause?.let { "$message Cause: $it" } ?: message
        }
        val missingClass = when {
            detail.startsWith("CLASS_NOT_FOUND\t") -> detail.substringAfter('\t')
            detail.startsWith("MISSING\t") -> detail.substringAfter('\t')
            else -> null
        }
        if (missingClass != null) {
            val className = missingClass.removePrefix("L").removeSuffix(";").replace('/', '.')
            return "Direct app sandbox could not redefine $className because it is not loaded in the running " +
                    "process; restarting the app to activate the committed class overlay."
        }
        return "Direct app sandbox Hot Reload fallback to app restart: $detail"
    }

    private fun findMainProcessPid(packageName: String, knownPids: List<Int>): Int? {
        val pid = launchContext.deviceAdb.execAdbShellCmd("pidof $packageName")
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull(String::toIntOrNull)
            .firstOrNull()
        if (pid != null && (knownPids.isEmpty() || pid in knownPids)) {
            return pid
        }
        return null
    }
}

data class DirectAppSandboxDeployResult(
    val overlayId: JuggOverlayId,
    val needsRestart: Boolean,
)
