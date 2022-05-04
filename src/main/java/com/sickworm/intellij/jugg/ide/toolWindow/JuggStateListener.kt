package com.sickworm.intellij.jugg.ide.toolWindow

import com.sickworm.intellij.jugg.deploy.JuggDeployState
import java.io.File

/**
 * Listener for Jugg state changes.
 */
interface JuggStateListener {
    fun onDeployStateUpdate(state: JuggDeployState)
    fun onFileStatesUpdate(infos: List<ChangedFileInfo>)
    fun onDeployed()
}

class ChangedFileInfo(
    val file: File,
    val state: State
) {

    enum class State {
        MODIFIED,
        COMPILING,
        COMPILED,
        COMPILE_FAILED
    }
}