package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.copyResource
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File

/**
 * Push Jugg JVMTI agent to specific App.
 * The JVMTI agent of Jugg is used to compat deploy function when Apply changes not working.
 * See module jvmti_agent
 */
interface IJuggJvmtiAgentManager {

    fun getCurrentAgentsInApp(packageName: String): List<String>

    fun pushAgentToApp(packageName: String): Boolean

    fun removeAllAgents(): Boolean

    fun attachAgentToApp(packageName: String): Boolean

}

class JuggJvmtiAgentManager(private val adb: IDeviceAdb, loggerArg: Logger) : IJuggJvmtiAgentManager {

    private val logger = loggerArg.getInstance("JuggJvmtiAgentManager")

    private var lastError: String? = null

    private val agentBundleFile: File by lazy { getAgentBundle() }

    private val juggTempDirPath = "/data/local/tmp/jugg"
    private val agentDirPathOnDevice: String get() = "$juggTempDirPath/${BuildConfig.AGENT_VERSION}"
    private val agentInAppPath = "code_cache/startup_agents"
    private val agentSoDestPathStartsWith = "$agentInAppPath/$AGENT_SO_NAME_PREFIX"

    override fun getCurrentAgentsInApp(packageName: String): List<String> {
        val subCmd = "ls -1 $agentInAppPath" // -1 for file per line
        val cmd = "run-as $packageName \"$subCmd\""
        val result = adb.execAdbShellCmd(cmd).trim()
        if (result.contains("No such file or directory")) {
            return emptyList()
        }
        return result.split("\n")
    }

    @Synchronized
    override fun pushAgentToApp(packageName: String): Boolean {
        val isAgentBundlePushed = isAgentBundlePushed()
        logger.debug("pushAgentBundle isAgentBundlePushed: $isAgentBundlePushed")
        if (!isAgentBundlePushed) {
            logger.debug("going to push agent bundle")
            if (!pushAgentBundle()) {
                logger.warn("[WARN ONLY] Push JVMTI agent bundle failed, $WARN_REASON. Failed reason: $lastError")
                return false
            }
        }

        val isAgentPushed = isAgentPushed(packageName)
        if (!isAgentPushed) {
            logger.debug("going to setup agent")
            if (!setupAgent(packageName)) {
                logger.warn("[WARN ONLY] Push JVMTI agent to App failed, $WARN_REASON. Failed reason: $lastError")
                return false
            }
        }
        logger.debug("Push JVMTI agent to App success")
        return true
    }

    override fun removeAllAgents(): Boolean {
        // agent won't init if instruments.jar not exists, so just remove agent dir
        val cmd = "rm -rf $juggTempDirPath"
        return execAdbShellCmd(cmd)
    }

    override fun attachAgentToApp(packageName: String): Boolean {
        val agentSuffix = if (is32AgentPushed(packageName)) "_alt.so" else ".so"
        val appDir = "/data/data/$packageName"
        val cmd = "am attach-agent $packageName $appDir/${agentSoDestPathStartsWith}${agentSuffix}=$appDir"
        return execAdbShellCmd(cmd)
    }

    private fun isAgentBundlePushed(): Boolean {
        val cmd = "[ -d $agentDirPathOnDevice ] && [ \$(find $agentDirPathOnDevice -maxdepth 1 -type f -printf '.' | wc -c) -eq 4 ] && echo success || echo failed"
        return execAdbShellCmd(cmd)
    }

    private fun isAgentPushed(packageName: String): Boolean {
        val cmd = "run-as $packageName ls $agentSoDestPathStartsWith && echo success || echo failed"
        return execAdbShellCmd(cmd)
    }

    private fun is32AgentPushed(packageName: String): Boolean {
        val cmd = "run-as $packageName ls ${agentSoDestPathStartsWith}_alt.so && echo success || echo failed"
        return execAdbShellCmd(cmd)
    }

    private fun pushAgentBundle(): Boolean {
        val toPath = "$juggTempDirPath/${agentBundleFile.name}"
        val pushResult = adb.push(agentBundleFile, toPath)
        if (!pushResult) {
            return false
        }
        val cmd = "rm -rf $agentDirPathOnDevice"
            .then("cd $juggTempDirPath")
            .and("mkdir $agentDirPathOnDevice")
            .and("unzip ${agentBundleFile.name} -d $agentDirPathOnDevice")
            .and("echo success")
            .or("echo failed")
        return execAdbShellCmd(cmd)
    }

    private fun setupAgent(packageName: String): Boolean {
        val scriptPath = "code_cache/jugg_agent_setup.sh"
        // caution: run-as will back to normal user after execute first cmd, so don't execute multiple commands
        // that needs package permission
        val pushScriptCmd = "cp $agentDirPathOnDevice/jugg_agent_setup.sh $scriptPath"
            .and("echo success")
            .or("echo failed")
        val isPushScriptSuccess = execAdbShellCmd("run-as $packageName \"$pushScriptCmd\"")
        if (!isPushScriptSuccess) {
            return false
        }

        val arch = adb.getArch(packageName)
        val runScriptCmd = "run-as $packageName $scriptPath ${BuildConfig.AGENT_VERSION} $arch"
        return execAdbShellCmd(runScriptCmd)
    }

    private fun execAdbShellCmd(cmd: String): Boolean {
        val result = adb.execAdbShellCmd(cmd).trim()
        val isSuccess = result.endsWith("success")
        if (!isSuccess) {
            lastError = result
        }
        return isSuccess
    }


    companion object {
        private const val WARN_REASON = "some device e.g. HarmonyOS 4.2 may not run correctly"

        const val AGENT_SO_NAME_PREFIX = "${BuildConfig.AGENT_VERSION}-jugg_jvmti_agent" // .so or _alt.so

        private fun getAgentBundle(): File {
            return copyResource(BuildConfig.AGENT_BUNDLE_PATH)
        }
    }

    private fun String.and(arg: String): String {
        return "$this && $arg"
    }

    private fun String.then(arg: String): String {
        return "$this ; $arg"
    }

    private fun String.or(arg: String): String {
        return "$this || $arg"
    }
}