package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
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
        val isAgentPushed = agents.any { it.startsWith(JuggJvmtiAgentManager.AGENT_SO_NAME_PREFIX) }
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

    fun attachAgentToApps(adb: IDeviceAdb, data: JuggDeployData) {
        val isEnable = JuggSettings.finalIsEnableCompatibleDeploymentMode
        if (!isEnable) {
            logger.debug("Skip attach agent to apps for not enabled")
            return
        }
        TimeLogger.start("attachAgentToApps")
        data.apks.forEach {
            JuggJvmtiAgentManager(adb, logger).attachAgentToApp(it.applicationId)
        }
        TimeLogger.end("attachAgentToApps", logger)
    }

    fun isHasJvmtiCompatIssue(adb: IDeviceAdb, data: JuggDeployData, maxWaitTimeSecond: Long = 3): Boolean {
        if (CompatDeployHelper(logger).isEnableCompatDeploy(adb, data)) {
            // already in compatible mode, no need check
            logger.debug("device isEnableCompatDeploy, no need check")
            return false
        }
        val isEnable = JuggSettings.finalIsEnableCompatibleDeploymentMode
        if (!isEnable) {
            logger.debug("function is disable, no need check")
            return false
        }

        TimeLogger.start("isHasJvmtiCompatIssue")
        var waitedTimeMillSecond = 0L
        val waitGapMillSecond = 100L
        var isHasJvmtiCompatIssue = false
        while (waitedTimeMillSecond < maxWaitTimeSecond * 1000L) {
            if (waitedTimeMillSecond % 1000L == 0L) {
                val waitedTimeSecond = waitedTimeMillSecond / 1000
                logger.info("(${waitedTimeSecond + 1}/$maxWaitTimeSecond) detecting JVMTI status...")
            }
            Thread.sleep(waitGapMillSecond)
            waitedTimeMillSecond += waitGapMillSecond

            val isJvmtiAvailableResults = data.apks.map {
                val result = isJvmtiAvailable(adb, it.applicationId)
                logger.debug("isJvmtiAvailable=$result for ${it.applicationId}")
                if (result == true) {
                    CompatDeployHelper(logger).clearCompatDeviceRecord(adb, listOf(it.applicationId))
                } else if (result == false) {
                    CompatDeployHelper(logger).recordCompatDeviceRecord(adb, listOf(it.applicationId))
                }
                result
            }

            if (isJvmtiAvailableResults.all { it != null }) {
                isHasJvmtiCompatIssue = isJvmtiAvailableResults.any { it == false }
                break
            }

        }
        logger.debug("Detect finished, isHasJvmtiCompatIssue=$isHasJvmtiCompatIssue")
        TimeLogger.end("isHasJvmtiCompatIssue", logger)
        if (!isHasJvmtiCompatIssue) {
            logger.info("Detect finished, JVMTI is available.")
        }

        return isHasJvmtiCompatIssue
    }

    /**
     * @return null if not sure
     */
    private fun isJvmtiAvailable(adb: IDeviceAdb, packageName: String): Boolean? {
        val cmd = "run-as $packageName ls -a code_cache"
        val result = adb.execAdbShellCmd(cmd)
        if (result.contains("No such file or directory")) {
            return null
        }
        // priority read not available flag file
        if (result.contains(BuildConfig.JVMTI_NOT_AVAILABLE_FLAG_FILE)) {
            return false
        }
        if (result.contains(BuildConfig.JVMTI_AVAILABLE_FLAG_FILE)) {
            return true
        }
        return null
    }
}