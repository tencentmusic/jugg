package com.sickworm.intellij.jugg.ide.logic

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.logger.JuggLogger
import java.text.SimpleDateFormat
import java.util.Locale


/**
 * Handle problems that some IDEs don't return Sync Start and Sync Success
 * Solution: detect and read after build finished
 */
class IdeSyncProblemResolver(project: Project) {

    private val logger = JuggLogger.getInstance(project, "IdeSyncProblemResolver")

    private val propertiesComponent = PropertiesComponent.getInstance(project)
    private val lastSyncSuccessTimeKey = "lastSyncSuccessTime_${AsDeployerCompat.ideVersion}"

    private var lastSyncSuccessTime: Long
        get() {
            return propertiesComponent.getLong(lastSyncSuccessTimeKey, 0L)
        }
        set(value) {
            propertiesComponent.setValue(lastSyncSuccessTimeKey, value.toString())
        }

    fun isNeedSyncAfterBuild(): Boolean {
        logger.debug("lastSyncSuccessTime: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(lastSyncSuccessTime)}")
        return lastSyncSuccessTime <= 0 // never synced
    }

    fun onIdeSyncSucceeded() {
        lastSyncSuccessTime = System.currentTimeMillis()
    }
}
