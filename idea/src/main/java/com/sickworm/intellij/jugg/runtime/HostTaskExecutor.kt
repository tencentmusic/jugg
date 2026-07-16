package com.sickworm.intellij.jugg.runtime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.project.runtime.IHostTaskExecutor
import java.lang.Runnable

/** Executes shared runtime tasks through IDEA background tasks and progress indicators. */
class HostTaskExecutor(
    private val project: Project,
) : IHostTaskExecutor {

    @Volatile
    var currentIndicator: ProgressIndicator? = null
        private set

    override val isOnEdt: Boolean
        get() = ApplicationManager.getApplication().isDispatchThread

    override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
        object : Task.Backgroundable(project, title, false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (showIndicator) {
                        indicator.text = "$title..."
                        indicator.isIndeterminate = true
                        currentIndicator = indicator
                    }
                    action.run()
                } finally {
                    if (showIndicator) {
                        indicator.stop()
                        currentIndicator = null
                    }
                }
            }
        }.setCancelText(cancelText).queue()
    }
}
