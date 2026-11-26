package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.IDevice

/** Supplies optional debugger redefiners without exposing IDEA project APIs to shared deploy code. */
interface IDeployDebugger {
    fun makeDebuggerRedefiners(device: IDevice, fallback: Boolean): Map<Int, JuggClassRedefiner>
}

object NoDeployDebugger : IDeployDebugger {
    override fun makeDebuggerRedefiners(device: IDevice, fallback: Boolean): Map<Int, JuggClassRedefiner> = emptyMap()
}
