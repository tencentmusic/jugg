package com.sickworm.intellij.jugg

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.project.runtime.IHostTaskExecutor
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.mockito.Mockito
import java.lang.Runnable

internal fun createTestTaskRunnerManager(pathManager: JuggPathManager): TaskRunnerManager {
    return TaskRunnerManager(
        logger = Mockito.mock(Logger::class.java),
        deployStateManager = Mockito.mock(IDeployStateManager::class.java),
        juggServer = Mockito.mock(JuggServer::class.java),
        hostTaskExecutor = object : IHostTaskExecutor {
            override val isOnEdt: Boolean = false

            override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
                action.run()
            }
        },
        pathManager = pathManager,
        runtimeType = "idea",
        runtimeVersion = "test",
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}
