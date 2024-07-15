package com.sickworm.intellij.jugg.deploy.run

/**
 * @see [com.android.tools.idea.run.tasks.LaunchResult]
 */
class LaunchResult(
    val success: Boolean,
    val errorId: Int,
    val consoleError: String?,
    val overlayIds: Map<String, String>, // applicationId -> overlayId
)