package com.sickworm.intellij.jugg.compiler

/**
 * Triggers one-shot Gradle fallback compile and optional clean-reinstall flow.
 */
abstract class ForceGradleCompileHelper {

    companion object {
        var isForceGradleCompileNextTime = false
        var isCleanAndReinstallNextTime = false
        var isGradleCacheRefreshNextTime = false
    }

    abstract fun executeGradleCompile(autoConfirm: Boolean = false, useCleanAndReinstall: Boolean = false)

    abstract fun executeGradleCompileBlocking(
        autoConfirm: Boolean = true,
        useCleanAndReinstall: Boolean = false,
    ): GradleCompileExecutionResult

    /** Executes the Gradle fallback for one request-scoped device when the Host supports deployment. */
    open fun executeGradleCompileBlockingForDevice(
        autoConfirm: Boolean = true,
        useCleanAndReinstall: Boolean = false,
        targetDeviceSerial: String?,
    ): GradleCompileExecutionResult {
        return executeGradleCompileBlocking(autoConfirm, useCleanAndReinstall)
    }

    abstract fun resolveExecutionType(): String

    abstract fun requestRemoteSshInfo(
        requestedBy: String,
        reason: String,
    ): RemoteSshInfoResult
}

data class GradleCompileExecutionResult(
    val status: String,
    val message: String,
    val isCompileSuccess: Boolean,
    val isDeploySuccess: Boolean,
    val detail: String = "",
)

data class RemoteSshInfoResult(
    val approved: Boolean,
    val message: String,
    val user: String? = null,
    val ip: String? = null,
    val port: Int? = null,
    val password: String? = null,
    val sshLoginCommand: String? = null,
)
