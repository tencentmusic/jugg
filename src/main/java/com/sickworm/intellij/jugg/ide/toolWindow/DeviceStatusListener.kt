package com.sickworm.intellij.jugg.ide.toolWindow

import com.sickworm.intellij.jugg.deploy.DeployState

interface DeviceStatusListener {
    fun updateStatus(state: DeployState)
}