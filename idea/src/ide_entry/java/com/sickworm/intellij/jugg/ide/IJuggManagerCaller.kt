package com.sickworm.intellij.jugg.ide

import com.intellij.execution.ExecutionResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup

/**
 * API that IDE will call to interact with JuggManager.
 */
interface IJuggManagerCaller: Disposable {

    fun init()

    fun onSyncEvent(syncEvent: SyncEvent)

    fun runTask(
        options: JuggRunConfigurationOptions,
        isForceGradleCompile: Boolean = false,
        isForceReinstallNextTime: Boolean = false,
    ): ExecutionResult

    fun gradleCompile()

    fun restartApp()

    fun reportIssue()

    fun getMoreOptions(options: JuggRunConfigurationOptions): ActionGroup
}