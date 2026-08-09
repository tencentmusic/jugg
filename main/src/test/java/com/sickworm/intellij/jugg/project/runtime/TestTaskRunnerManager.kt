package com.sickworm.intellij.jugg.project.runtime

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

internal fun createTestTaskRunnerManager(coroutineScope: CoroutineScope): TaskRunnerManager {
    return TaskRunnerManager(
        logger = mock<Logger>(),
        deployStateManager = mock<IDeployStateManager>(),
        juggServer = mock<JuggServer>(),
        hostTaskExecutor = object : IHostTaskExecutor {
            override val isOnEdt: Boolean = false

            override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
                action.run()
            }
        },
        executionLockManager = object : IExecutionLockManager {
            override fun <T> withProjectLock(command: String, action: () -> T): T = action()

            override fun <T : Any> tryWithProjectLock(command: String, action: () -> T): T? = action()

            override fun readProjectLockOwner(): ExecutionLockOwner? = null
        },
        coroutineScope = coroutineScope,
    )
}

internal fun createImmediateTestTaskRunnerManager(): TaskRunnerManager {
    val manager = Mockito.mock(TaskRunnerManager::class.java)
    doReturn(Dispatchers.Unconfined).whenever(manager).dispatcher
    doAnswer { invocation ->
        invocation.getArgument<Runnable>(4).run()
        Job()
    }.whenever(manager).runBackgroundSafe(
        any(),
        any(),
        any(),
        any(),
        any(),
    )
    return manager
}
