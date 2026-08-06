package com.sickworm.intellij.jugg.deploy.run.flow

import com.sickworm.intellij.jugg.deploy.run.JuggDeployRunTaskRequest
import com.sickworm.intellij.jugg.deploy.run.LaunchResult

/**
 * Executes a single device deploy run task for [com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper].
 */
interface IJuggDeployRunTaskExecutor {
    fun execute(request: JuggDeployRunTaskRequest): LaunchResult
}
