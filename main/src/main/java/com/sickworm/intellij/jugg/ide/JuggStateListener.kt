package com.sickworm.intellij.jugg.ide

import com.sickworm.intellij.jugg.deploy.JuggDeployState
import java.io.File

/**
 * Listener for Jugg state changes.
 */
interface JuggStateListener {
    fun onDeployStateUpdate(state: JuggDeployState)
    fun onFileStatesUpdate(infos: List<ChangedFileInfo>)
    fun onDeployed(isInstall: Boolean, files: List<File>)

    companion object {
        val emptyImpl = object : JuggStateListener {
            override fun onDeployStateUpdate(state: JuggDeployState) = Unit

            override fun onFileStatesUpdate(infos: List<ChangedFileInfo>) = Unit

            override fun onDeployed(isInstall: Boolean, files: List<File>) = Unit
        }
    }
}

data class ChangedFileInfo(
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