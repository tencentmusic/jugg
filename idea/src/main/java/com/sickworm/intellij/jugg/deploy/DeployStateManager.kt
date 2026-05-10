package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.logger.JuggLogger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manage [JuggDeployState].
 */
class DeployStateManager(
    private val project: Project,
    private val deployTargetManager: IDeployTargetManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val ideDeployStateHelper: IIdeDeployStateHelper = IdeDeployStateHelper(project),
) : IDeployStateManager {

    private val logger = JuggLogger.getInstance(project, "DeployStateManager")

    var deployState = JuggDeployState(
        JuggDeployState.State.NOTHING_CAN_DO,
        "jugg not initialized",
        IdeDeployState.ok,
    )
        private set

    private var deployStateMap = mapOf<String, JuggDeployState>()

    @Volatile
    var isBuildFileChanged = false

    @Volatile
    var whatBuildFileChanged: String = ""

    @Volatile
    var isInitializingIncrementalCompile = false

    /** Count of pending async file-processing tasks. Incremented synchronously before dispatch. */
    private val pendingFileProcessingCount = AtomicInteger(0)
    private val fileProcessingLock = ReentrantLock()
    private val fileProcessingDone = fileProcessingLock.newCondition()

    /**
     * Must be called synchronously (on the callback thread) before dispatching the async file-processing task.
     * This guarantees the counter is non-zero before any compile check runs.
     */
    fun beginFileProcessing() {
        val pendingCount = pendingFileProcessingCount.incrementAndGet()
        if (pendingCount == 1 || pendingCount % 20 == 0) {
            logger.trace("begin file processing, pendingCount=$pendingCount")
        }
    }

    /**
     * Must be called in the finally block of the async file-processing task.
     */
    fun endFileProcessing() {
        var pendingCount = pendingFileProcessingCount.decrementAndGet()
        if (pendingCount < 0) {
            logger.warn("pendingFileProcessingCount is negative, value=$pendingCount, reset to 0")
            pendingFileProcessingCount.set(0)
            pendingCount = 0
        }
        if (pendingCount > 0 && pendingCount % 20 == 0) {
            logger.trace("end file processing, pendingCount=$pendingCount")
        }
        if (pendingCount == 0) {
            logger.trace("all file processing finished")
            fileProcessingLock.withLock {
                fileProcessingDone.signalAll()
            }
        }
    }

    fun hasPendingFileProcessing(): Boolean = pendingFileProcessingCount.get() > 0

    /**
     * Block until all pending file-processing tasks complete.
     * Returns immediately if none are pending.
     * @param timeoutMs maximum wait time in milliseconds (default 1s)
     */
    fun waitForPendingFileProcessing(timeoutMs: Long = 1_000L): FileProcessingWaitResult {
        val initialPendingCount = pendingFileProcessingCount.get()
        if (initialPendingCount == 0) {
            return FileProcessingWaitResult(isTimeout = false, pendingCount = 0, waitedMs = 0L, initialPendingCount = 0)
        }
        val waitStart = System.currentTimeMillis()
        var isTimeout = false
        fileProcessingLock.withLock {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (pendingFileProcessingCount.get() > 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    isTimeout = true
                    break
                }
                fileProcessingDone.await(remaining, TimeUnit.MILLISECONDS)
            }
        }
        val waitedMs = System.currentTimeMillis() - waitStart
        val pendingCount = pendingFileProcessingCount.get()
        if (isTimeout) {
            logger.debug(
                "waitForPendingFileProcessing timeout, timeoutMs=$timeoutMs, " +
                    "pendingCount=$pendingCount, initialPendingCount=$initialPendingCount, waitedMs=$waitedMs"
            )
        }
        return FileProcessingWaitResult(
            isTimeout = isTimeout,
            pendingCount = pendingCount,
            waitedMs = waitedMs,
            initialPendingCount = initialPendingCount,
        )
    }

    /**
     * Invoke when project need to update [JuggDeployState].
     */
    override fun updateDeployState(): JuggDeployState {
        var lastState = deployState
        deployState = getNewDeployState()
        while (lastState != deployState) {
            logger.debug("deploy state changed: $lastState -> $deployState")

            // deploy state not stable, need revoke again.
            // case:
            // first unplug/plug a device, first you will get unknown API level
            // then you will get app not detect
            // last you will get ready to deploy (if it is)
            lastState = deployState
            deployState = getNewDeployState()
            Thread.sleep(10)
        }

        // now deploy state is stable
        return deployState
    }

    fun getDeployState(device: IDevice): JuggDeployState {
        return deployStateMap[device.name] ?: getNewDeployState(device)
    }

    private fun getNewDeployState(): JuggDeployState {
        deployStateMap = deployTargetManager.getSelectedDevices().map {
            // name includes serial number
            it.name to getNewDeployState(it)
        }.associate { it }

        return if (deployStateMap.isEmpty()) {
            getNewDeployState(null)
        } else {
            deployStateMap.minBy { it.value.state.ordinal }.value
        }
    }

    private fun getNewDeployState(device: IDevice? = null): JuggDeployState {
        val ideDeployState = ideDeployStateHelper.getIdeDeployState(device, deployTargetManager.getPackageNameOrNull())
        logger.debug("ide deploy state: $ideDeployState")

        if (!deployHistoryManager.hasBeenFullCompiled) {
            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "not gradle compile yet", ideDeployState)
        }

        if (deployHistoryManager.isLastFullCompileFailed) {
            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "last gradle compile not success", ideDeployState)
        }

        if (isBuildFileChanged) {
            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "$whatBuildFileChanged changed", ideDeployState)
        }

        if (ideDeployState.state != IdeDeployState.State.OK) {
            return JuggDeployState(JuggDeployState.State.READY_INCREMENTAL_COMPILE,
                ideDeployState.message,
                ideDeployState,
            )
        }

        return JuggDeployState.READY
    }
}

data class FileProcessingWaitResult(
    val isTimeout: Boolean,
    val pendingCount: Int,
    val waitedMs: Long,
    val initialPendingCount: Int,
)

interface IIdeDeployStateHelper {
    fun getIdeDeployState(device: IDevice?, packageName: String?): IdeDeployState
}

class IdeDeployStateHelper(
    private val project: Project,
) : IIdeDeployStateHelper {

    override fun getIdeDeployState(device: IDevice?, packageName: String?): IdeDeployState {
        return AsDeployerCompat.getIdeDeployStateResult(project, device, packageName)
    }

}
