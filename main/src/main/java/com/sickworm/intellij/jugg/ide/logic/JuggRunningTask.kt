package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.progress.ProgressIndicator

/**
 * Implementation of compilation and deployment.
 * [run] will be called when user click "Run" button.
 */
interface IJuggRunningTask {
    val isRunning: Boolean
    fun run(indicator: ProgressIndicator)
    fun cancel(onFinishListener: () -> Unit)
}