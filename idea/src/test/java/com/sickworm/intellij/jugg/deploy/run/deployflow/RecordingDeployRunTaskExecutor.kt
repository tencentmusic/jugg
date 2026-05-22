package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.sickworm.intellij.jugg.deploy.run.JuggDeployRunTaskRequest
import com.sickworm.intellij.jugg.deploy.run.LaunchResult
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployRunTaskExecutor

/**
 * Records each [JuggDeployRunTaskRequest] at the [JuggDeployerHelper] boundary without running
 * [com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployTask] or
 * [com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployer.optimisticSwap] (incl.
 * [com.sickworm.intellij.jugg.deploy.direct.DirectOverlaySwapTransport]). Use for L2 orchestration only;
 * see deploy-flow plan §3.4.
 */
class RecordingDeployRunTaskExecutor(
    private val overlayIdByPackage: Map<String, String> = mapOf(DEFAULT_PACKAGE to "deploy-flow-overlay"),
) : IJuggDeployRunTaskExecutor {

    val invocations: MutableList<JuggDeployRunTaskRequest> = mutableListOf()

    val lastRequest: JuggDeployRunTaskRequest?
        get() = invocations.lastOrNull()

    override fun execute(request: JuggDeployRunTaskRequest): LaunchResult {
        invocations.add(request)
        val overlayIds = request.data.apks.associate { apk ->
            apk.applicationId to (overlayIdByPackage[apk.applicationId] ?: overlayIdByPackage[DEFAULT_PACKAGE]!!)
        }
        return LaunchResult(
            success = true,
            errorId = 0,
            consoleError = null,
            overlayIds = overlayIds,
        )
    }

    companion object {
        const val DEFAULT_PACKAGE = "com.example.app"
    }
}
