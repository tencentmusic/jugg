package com.sickworm.intellij.jugg.loader

import com.android.ddmlib.IDevice
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdb
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.JuggRunningTask
import com.sickworm.intellij.jugg.ide.SyncEvent
import com.sickworm.intellij.jugg.ide.ui.SimpleProcessHandler

interface IJuggManager: Disposable {

    fun init()

    fun onSyncEvent(syncEvent: SyncEvent)

    fun createRunningTask(
        options: JuggGradleCompileOptions,
        processHandler: SimpleProcessHandler,
        isForceGradleCompile: Boolean = false,
    ): JuggRunningTask

    fun cancelCurrentTask(processHandler: ProcessHandler, onFinish: () -> Unit)

    fun gradleCompile()

    fun restartApp()

    fun enableInjectGradleCompilation()

    fun markAsSyncedAndReInitCompiler()

    fun enableReadProjectFromGradle()

    fun setForceCompatDevice(adb: IdeaDeviceAdb)

    fun forceReInstallNextTime()

    fun markAsGradleCompiledAndReInitCompiler(options: JuggRunConfigurationOptions)

    fun copyGeneratedSourceToLocal()

    fun setCustomServerUrl()

    fun enableCompatibleDeploymentMode()

    fun setEnableBackupClasspath()

    fun dumpLogcatErrorLogs(): String

    fun getDeviceList(): List<IDevice>
}