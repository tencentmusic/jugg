package com.sickworm.intellij.jugg

import com.intellij.execution.ExecutionResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.SyncEvent

interface IJuggManager: Disposable {

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