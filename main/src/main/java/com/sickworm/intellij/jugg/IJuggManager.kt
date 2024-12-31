package com.sickworm.intellij.jugg

import com.android.ddmlib.IDevice
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.ide.IProcessHandler
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.SyncEvent

interface IJuggManager: Disposable {

    fun init()

    fun onSyncEvent(syncEvent: SyncEvent)

    fun runTask(
        options: JuggGradleCompileOptions,
        processHandler: IProcessHandler,
        isForceGradleCompile: Boolean = false,
    )

    fun cancelCurrentTask(processHandler: IProcessHandler, onFinish: () -> Unit)

    fun gradleCompile()

    fun restartApp()

    fun enableInjectGradleCompilation()

    fun markAsSyncedAndReInitCompiler()

    fun enableReadProjectFromGradle()

    fun setForceCompatDevice(adb: IDeviceAdb)

    fun forceReInstallNextTime()

    fun markAsGradleCompiledAndReInitCompiler(compileOptions: JuggGradleCompileOptions)

    fun copyGeneratedSourceToLocal()

    fun setCustomServerUrl()

    fun enableCompatibleDeploymentMode()

    fun setEnableBackupClasspath()

    fun dumpLogcatErrorLogs(): String

    fun getDeviceList(): List<IDevice>
}