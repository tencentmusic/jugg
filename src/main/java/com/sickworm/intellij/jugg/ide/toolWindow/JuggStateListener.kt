package com.sickworm.intellij.jugg.ide.toolWindow

import com.sickworm.intellij.jugg.deploy.JuggDeployState

/**
 * Listener for Jugg state changes.
 */
interface JuggStateListener {
    fun onDeployStateUpdate(state: JuggDeployState)
}