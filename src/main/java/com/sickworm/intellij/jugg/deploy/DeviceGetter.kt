package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.android.tools.idea.run.DeviceCount
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.editor.DeployTargetState
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.project.JuggException
import org.jetbrains.android.facet.AndroidFacet

class DeviceGetter(private val project: Project) {

    private val deployTargetContext: DeployTargetContext by lazy { DeployTargetContext() }

    private val isDebugging = true

    private fun supportMultipleDevices() = false

    fun getDevice(): IDevice {
        val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)
        val deployTargetState: DeployTargetState = deployTargetContext.currentDeployTargetState
        val module = ModuleManager.getInstance(project).modules.first()
        val facet = AndroidFacet.getInstance(module)
            ?: throw IllegalStateException("no android facet")

        val deviceFutures =
            deployTarget.getDevices(deployTargetState, facet, getDeviceCount(isDebugging), isDebugging, hashCode())
                ?: throw IllegalStateException("no device futures")

        // got ClassCastException if using DeviceFutures.get() for different ClassLoader
        val devices = deviceFutures.ifReady
        if (devices == null || devices.size == 0) {
            throw JuggException.deviceNotFound()
        }

        if (devices.size > 1) {
            throw JuggException.multipleDeviceFound()
        }

        return devices[0]
    }

    private fun getDeviceCount(@Suppress("SameParameterValue") debug: Boolean): DeviceCount {
        return DeviceCount.fromBoolean(supportMultipleDevices() && !debug)
    }
}