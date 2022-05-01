package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.project.ChangedFile
import java.io.File

/**
 * Manage deployment history for a project.
 */
interface IDeployHistoryManager {

    /**
     * this object is available for use.
     */
    val isAvailable: Boolean

    /**
     * @return List of not deployed files. Null if not available
     */
    fun getChangedFilesSinceLastDeployed(): List<File>?

    /**
     * invoke this method to reset deploy history after project complete compiling by gradle.
     */
    fun onAfterFullCompiled()

    /**
     * invoke this method to update deploy history after project complete deploying by Jugg.
     */
    fun onAfterDeployed(deployedFiles: List<ChangedFile>)
}
