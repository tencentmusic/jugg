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

/**
 * JuggJvmtiAgentManager manages JVMTI agent push/setup/attach/cleanup in app sandboxes for compatibility deploy flows.
 */
class JuggJvmtiAgentManager(private val adb: IDeviceAdb, loggerArg: Logger) : IJuggJvmtiAgentManager {

    private val logger = loggerArg.getInstance("JuggJvmtiAgentManager")

    private var lastError: String? = null

    private val agentBundleFile: File by lazy { getAgentBundle() }

    private val juggTempDirPath = "/data/local/tmp/jugg"
    private val agentDirPathOnDevice: String get() = "$juggTempDirPath/${BuildConfig.AGENT_VERSION}"
    private val agentInAppPath = "code_cache/startup_agents"
    private val agentSoDestPathStartsWith = "$agentInAppPath/$AGENT_SO_NAME_PREFIX"
    private val instrumentationJarInAppPath =
        "$agentInAppPath/${BuildConfig.AGENT_VERSION}-jugg-instruments.jar"

    override fun getCurrentAgentsInApp(packageName: String): List<String> {
        val subCmd = "ls -1 $agentInAppPath" // -1 for file per line
        val result = AppSandboxExecutor(adb, packageName, logger).exec(subCmd).trim()
        if (result.contains("No such file or directory")) {
            return emptyList()
        }
        return result.split("\n")
    }

    @Synchronized
    override fun pushAgentToApp(packageName: String): Boolean {
        return pushAgentToAppInternal(packageName, null)
    }

    @Synchronized
    fun pushAgentToApp(packageName: String, sandboxExecutor: AppSandboxExecutor): Boolean {
        return pushAgentToAppInternal(packageName, sandboxExecutor)
    }

    private fun pushAgentToAppInternal(packageName: String, sandboxExecutor: AppSandboxExecutor?): Boolean {
        val isAgentBundlePushed = isAgentBundlePushed()
        logger.debug("pushAgentBundle isAgentBundlePushed: $isAgentBundlePushed")
        if (!isAgentBundlePushed) {
            logger.debug("going to push agent bundle")
            if (!pushAgentBundle()) {
                logger.warn("[WARN ONLY] Push JVMTI agent bundle failed, $WARN_REASON. Failed reason: $lastError")
                return false
            }
        }

        val isAgentPushed = isAgentPushed(packageName, sandboxExecutor)
        if (!isAgentPushed) {
            logger.debug("going to setup agent")
            if (!setupAgent(packageName, sandboxExecutor)) {
                logger.warn("[WARN ONLY] Push JVMTI agent to App failed, $WARN_REASON. Failed reason: $lastError")
                return false
            }
        }
        if (sandboxExecutor != null && !pushInstrumentationJarToApp(sandboxExecutor)) {
            logger.warn("[WARN ONLY] Push instrumentation JAR to App failed, $WARN_REASON. Failed reason: $lastError")
            return false
        }
        logger.debug("Push JVMTI agent to App success")
        return true
    }

    private fun pushInstrumentationJarToApp(sandbox: AppSandboxExecutor): Boolean {
        val output = sandbox.exec(
            "mkdir -p $agentInAppPath && " +
                "cp -f $agentDirPathOnDevice/jugg-instruments.jar $instrumentationJarInAppPath && " +
                "chmod 0600 $instrumentationJarInAppPath && echo success || echo failed",
            repairCodeCache = true,
        )
        return parseSuccess(output)
    }

    override fun removeAllAgents(): Boolean {
        // agent won't init if instruments.jar not exists, so just remove agent dir
        val cmd = "rm -rf $juggTempDirPath"
        return execAdbShellCmd(cmd)
    }

    override fun attachAgentToApp(packageName: String): Boolean {
        val sandbox = AppSandboxExecutor(adb, packageName, logger)
        val agentSuffix = if (is32AgentPushed(packageName)) "_alt.so" else ".so"
        val appDir = sandbox.absolutePath("")?.removeSuffix("/") ?: return false
        val agentPath = sandbox.absolutePath("${agentSoDestPathStartsWith}${agentSuffix}") ?: return false
        val cmd = "am attach-agent $packageName $agentPath=$appDir"
        return execAdbShellCmd(cmd)
    }

    /**
     * Copies the installed Jugg agent to a request-specific path for dynamic attach.
     */
    fun prepareHotReloadAgent(
        packageName: String,
        requestDir: String,
        sandboxExecutor: AppSandboxExecutor? = null,
    ): String? {
        if (!pushAgentToAppInternal(packageName, sandboxExecutor)) {
            return null
        }
        val sandbox = sandboxExecutor ?: AppSandboxExecutor(adb, packageName, logger)
        val agentSuffix = if (is32AgentPushed(packageName, sandbox)) "_alt.so" else ".so"
        val source = "${agentSoDestPathStartsWith}${agentSuffix}"
        val destination = "$requestDir/jugg_jvmti_agent.so"
        val output = sandbox.exec(
            "cp -f $source $destination && chmod 0700 $destination && echo success || echo failed",
            repairCodeCache = true,
        )
        if (!output.trim().endsWith("success")) {
            lastError = output
            return null
        }
        return sandbox.absolutePath(destination)
    }

    fun attachHotReloadAgent(pid: Int, agentPath: String, requestDir: String): Boolean {
        return execAdbShellCmd(
            "am attach-agent $pid $agentPath=jugg_hot_reload:$requestDir && echo success || echo failed",
        )
    }

    private fun isAgentBundlePushed(): Boolean {
        val cmd = "[ -d $agentDirPathOnDevice ] && [ \$(find $agentDirPathOnDevice -maxdepth 1 -type f -printf '.' | wc -c) -eq 4 ] && echo success || echo failed"
        return execAdbShellCmd(cmd)
    }

    private fun isAgentPushed(packageName: String, sandboxExecutor: AppSandboxExecutor? = null): Boolean {
        val sandbox = sandboxExecutor ?: AppSandboxExecutor(adb, packageName, logger)
        val result = sandbox.exec(
            "ls ${agentSoDestPathStartsWith}*.so >/dev/null 2>&1 && echo success || echo failed",
        )
        return parseSuccess(result)
    }

    private fun is32AgentPushed(packageName: String, sandboxExecutor: AppSandboxExecutor? = null): Boolean {
        val sandbox = sandboxExecutor ?: AppSandboxExecutor(adb, packageName, logger)
        val result = sandbox.exec(
            "ls ${agentSoDestPathStartsWith}_alt.so >/dev/null 2>&1 && echo success || echo failed",
        )
        return parseSuccess(result)
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

    private fun setupAgent(packageName: String, sandboxExecutor: AppSandboxExecutor? = null): Boolean {
        val sandbox = sandboxExecutor ?: AppSandboxExecutor(adb, packageName, logger)
        val scriptPath = "code_cache/jugg_agent_setup.sh"
        val pushScriptOutput = sandbox.exec(
            "mkdir -p code_cache && cp $agentDirPathOnDevice/jugg_agent_setup.sh $scriptPath && " +
                "chmod 0700 $scriptPath && echo success || echo failed",
            repairCodeCache = true,
        )
        val isPushScriptSuccess = parseSuccess(pushScriptOutput)
        if (!isPushScriptSuccess) {
            return false
        }

        val arch = adb.getArch(packageName)
        val runScriptOutput = sandbox.exec(
            "$scriptPath ${BuildConfig.AGENT_VERSION} $arch && echo success || echo failed",
            repairCodeCache = true,
        )
        return parseSuccess(runScriptOutput)
    }

    private fun execAdbShellCmd(cmd: String): Boolean {
        val result = adb.execAdbShellCmd(cmd).trim()
        val isSuccess = parseSuccess(result)
        if (!isSuccess) {
            lastError = result
        }
        return isSuccess
    }

    private fun parseSuccess(result: String): Boolean {
        val isSuccess = result.trim().endsWith("success")
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
