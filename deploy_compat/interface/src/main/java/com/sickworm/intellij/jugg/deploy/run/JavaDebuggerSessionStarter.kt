package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.Client
import com.intellij.execution.ExecutionException
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

/**
 * Starts Android Studio Java debugger sessions through the versioned internal debugger API.
 */
class JavaDebuggerSessionStarter(
    private val startClassName: String = START_CLASS_NAME,
    private val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {

    fun attachExistingProcess(project: Project, client: Client): Any {
        val session = start(project, client, console = null, detachIsDefault = true)
        return session ?: throw ExecutionException("Java Debug Session is not started")
    }

    private fun start(
        project: Project,
        client: Client,
        console: ConsoleView?,
        detachIsDefault: Boolean,
    ): Any? {
        val method = Class.forName(startClassName).getMethod(
            "startAndroidJavaDebuggerSession",
            Project::class.java,
            Client::class.java,
            ConsoleView::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val promise = unwrapInvocationTargetException {
            method.invoke(null, project, client, console, detachIsDefault)
        } ?: throw ExecutionException("Java Debug Session promise is not created")
        return blockingGet(promise)
    }

    private fun blockingGet(promise: Any): Any? {
        val method = promise.javaClass.getMethod(
            "blockingGet",
            Int::class.javaPrimitiveType,
            TimeUnit::class.java,
        )
        return unwrapExecutionException {
            unwrapInvocationTargetException {
                method.invoke(promise, timeoutSeconds, TimeUnit.SECONDS)
            }
        }
    }

    private fun unwrapInvocationTargetException(action: () -> Any?): Any? {
        return try {
            action()
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun unwrapExecutionException(action: () -> Any?): Any? {
        return try {
            action()
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        }
    }

    companion object {
        private const val START_CLASS_NAME =
            "com.android.tools.idea.execution.common.debug.impl.java.StartJavaDebuggerSessionKt"
        private const val DEFAULT_TIMEOUT_SECONDS = 15
    }
}
