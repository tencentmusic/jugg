package com.sickworm.intellij.jugg.deploy.run

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.api.IDevice

/** Connects the shared deploy task to Android Studio debugger redefiners. */
class IdeaDeployDebugger(private val project: Project, private val asDeployerCompat: IAsDeployerCompat) : IDeployDebugger {
    override fun makeDebuggerRedefiners(device: IDevice, fallback: Boolean): Map<Int, JuggClassRedefiner> {
        return asDeployerCompat.makeDebuggerRedefiners(project, device, fallback)
    }
}
