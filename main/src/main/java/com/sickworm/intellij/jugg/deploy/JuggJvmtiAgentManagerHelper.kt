package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance

class JuggJvmtiAgentManagerHelper(loggerArg: Logger) {

    private val logger = loggerArg.getInstance("JuggJvmtiAgentManagerHelper")

    fun isNeedPushAgentAfterDeploy(adb: IDeviceAdb, data: JuggDeployData): Boolean {
        try {
            TimeLogger.start("isNeedPushAgentAfterDeploy")
            if (data.isInstall) {
                // install has no incremental deploy files, no need push agent
                return false
            }
            data.apks.forEach {
                if (isNeedPushAfterDeploy(adb, it.applicationId)) {
                    // any App need push agent, do it all (they are always push together)
                    return true
                }
            }
            // otherwise, no need push agent
            return false
        } finally {
            TimeLogger.end("isNeedPushAgentAfterDeploy", logger)
        }
    }

    private fun isNeedPushAfterDeploy(adb: IDeviceAdb, packageName: String): Boolean {
        val agents: List<String> = JuggJvmtiAgentManager(adb, logger).getCurrentAgentsInApp(packageName)
        logger.debug("isNeedPushAfterDeploy agents=$agents")
        val isAgentPushed = agents.any { it == JuggJvmtiAgentManager.AGENT_SO_NAME }
        if (!isAgentPushed) {
            logger.debug("isNeedPushAfterDeploy=true for agent not pushed")
            return true
        }
        val isAlreadyApplyChanges = agents.any { !it.contains("jugg_jvmti_agent") && it.endsWith(".so") }
        if (!isAlreadyApplyChanges) {
            // apply changes will clear all agent on first deploy agent
            logger.debug("isNeedPushAfterDeploy=true for no apply changes agent")
            return true
        }

        logger.debug("isNeedPushAfterDeploy=false")
        return false
    }

    fun pushAgentToApps(adb: IDeviceAdb, data: JuggDeployData) {
        val isEnable = JuggSettings.finalIsEnableCompatibleDeploymentMode
        if (!isEnable) {
            logger.debug("Skip push agent to apps for not enabled")
            return
        }
        TimeLogger.start("pushAgentToApps")
        data.apks.forEach {
            JuggJvmtiAgentManager(adb, logger).pushAgentToApp(it.applicationId)
        }
        TimeLogger.end("pushAgentToApps", logger)
    }

}