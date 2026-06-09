package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.Client
import com.android.ddmlib.ClientData
import com.android.ddmlib.IDevice
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import java.lang.reflect.InvocationTargetException

/**
 * Waits for Android Studio to expose an app process that is ready for Java debugger attachment.
 */
class AndroidDebugClientReadyWaiter(
    private val waitClassName: String = WAIT_CLASS_NAME,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {

    fun waitForWaitingDebuggerClient(device: IDevice, packageName: String): Client {
        return waitForClientReadyForDebug(
            device = device,
            appIds = listOf(packageName),
            indicator = DumbProgressIndicator(),
            waitingProcessState = ClientData.DebuggerStatus.WAITING,
        )
    }

    private fun waitForClientReadyForDebug(
        device: IDevice,
        appIds: Collection<String>,
        indicator: ProgressIndicator,
        waitingProcessState: ClientData.DebuggerStatus,
    ): Client {
        val method = Class.forName(waitClassName).getMethod(
            "waitForClientReadyForDebug",
            IDevice::class.java,
            Collection::class.java,
            Long::class.javaPrimitiveType,
            ProgressIndicator::class.java,
            ClientData.DebuggerStatus::class.java,
        )
        return unwrapInvocationTargetException {
            method.invoke(null, device, appIds, timeoutSeconds, indicator, waitingProcessState)
        } as Client
    }

    private fun unwrapInvocationTargetException(action: () -> Any?): Any? {
        return try {
            action()
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    companion object {
        private const val WAIT_CLASS_NAME = "com.android.tools.idea.execution.common.debug.utils.UtilsKt"
        private const val DEFAULT_TIMEOUT_SECONDS = 15L
    }
}
