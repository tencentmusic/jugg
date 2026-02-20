package com.sickworm.intellij.jugg.deploy

/**
 * IDeployStateManager computes and updates current deploy mode/state from runtime conditions.
 */
interface IDeployStateManager {

    fun updateDeployState(): JuggDeployState
}
