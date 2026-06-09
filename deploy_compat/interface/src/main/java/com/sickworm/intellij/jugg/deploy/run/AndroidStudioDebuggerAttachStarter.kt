package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.Client
import com.intellij.openapi.project.Project
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * Starts Android Studio's native attach debugger flow so XDebugger owns the debug session.
 */
class AndroidStudioDebuggerAttachStarter(
    private val connectDebuggerClassName: String = CONNECT_DEBUGGER_CLASS_NAME,
    private val javaDebuggerClassName: String = JAVA_DEBUGGER_CLASS_NAME,
) {

    fun attachExistingProcess(project: Project, client: Client) {
        val javaDebugger = createJavaDebugger()
        val method = findCloseOldSessionAndRunMethod(javaDebugger)
        unwrapInvocationTargetException {
            method.invoke(null, project, javaDebugger, client, null)
        }
    }

    private fun createJavaDebugger(): Any {
        val constructor = Class.forName(javaDebuggerClassName).getDeclaredConstructor()
        constructor.isAccessible = true
        return unwrapInvocationTargetException {
            constructor.newInstance()
        } ?: throw IllegalStateException("Android Java debugger is not created")
    }

    private fun findCloseOldSessionAndRunMethod(javaDebugger: Any): java.lang.reflect.Method {
        val connectDebuggerClass = Class.forName(connectDebuggerClassName)
        return connectDebuggerClass.methods.firstOrNull { method ->
            method.name == "closeOldSessionAndRun" &&
                Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.size == 4 &&
                method.parameterTypes[0].isAssignableFrom(Project::class.java) &&
                method.parameterTypes[1].isAssignableFrom(javaDebugger.javaClass) &&
                method.parameterTypes[2].isAssignableFrom(Client::class.java)
        } ?: throw NoSuchMethodException("$connectDebuggerClassName.closeOldSessionAndRun")
    }

    private fun unwrapInvocationTargetException(action: () -> Any?): Any? {
        return try {
            action()
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    companion object {
        private const val CONNECT_DEBUGGER_CLASS_NAME =
            "com.android.tools.idea.execution.common.debug.utils.AndroidConnectDebugger"
        private const val JAVA_DEBUGGER_CLASS_NAME =
            "com.android.tools.idea.execution.common.debug.impl.java.AndroidJavaDebugger"
    }
}
