package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * DeployStateManager derives deploy readiness from shared project state and host-provided device state.
 */
class DeployStateManager(
    private val deployTargetManager: IDeployTargetManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val hostDeployStateResolver: IHostDeployStateResolver,
    private val logger: Logger,
) : IDeployStateManager {

    @Volatile
    override var deployState = JuggDeployState(
        JuggDeployState.State.NOTHING_CAN_DO,
        "jugg not initialized",
        IdeDeployState.ok,
    )
        private set

    private var deployStateMap = mapOf<String, JuggDeployState>()

    @Volatile
    override var isBuildFileChanged = false

    @Volatile
    override var whatBuildFileChanged: String = ""

    @Volatile
    override var isInitializingIncrementalCompile = false

    private val pendingFileProcessingCount = AtomicInteger(0)
    private val fileProcessingLock = ReentrantLock()
    private val fileProcessingDone = fileProcessingLock.newCondition()

    override fun beginFileProcessing() {
        val pendingCount = pendingFileProcessingCount.incrementAndGet()
        if (pendingCount == 1 || pendingCount % 20 == 0) {
            logger.trace("begin file processing, pendingCount=$pendingCount")
        }
    }

    override fun endFileProcessing() {
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

    override fun hasPendingFileProcessing(): Boolean = pendingFileProcessingCount.get() > 0

    override fun waitForPendingFileProcessing(timeoutMs: Long): FileProcessingWaitResult {
        val initialPendingCount = pendingFileProcessingCount.get()
        if (initialPendingCount == 0) {
            return FileProcessingWaitResult(false, 0, 0L, 0)
        }

        val waitStart = System.currentTimeMillis()
        val isTimeout = awaitFileProcessing(timeoutMs)
        val waitedMs = System.currentTimeMillis() - waitStart
        val pendingCount = pendingFileProcessingCount.get()
        if (isTimeout) {
            logger.debug(
                "waitForPendingFileProcessing timeout, timeoutMs=$timeoutMs, " +
                    "pendingCount=$pendingCount, initialPendingCount=$initialPendingCount, waitedMs=$waitedMs"
            )
        }
        return FileProcessingWaitResult(isTimeout, pendingCount, waitedMs, initialPendingCount)
    }

    private fun awaitFileProcessing(timeoutMs: Long): Boolean {
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
        return isTimeout
    }

    override fun updateDeployState(): JuggDeployState {
        var lastState = deployState
        deployState = getNewDeployState()
        while (lastState != deployState) {
            logger.debug("deploy state changed: $lastState -> $deployState")
            lastState = deployState
            deployState = getNewDeployState()
            Thread.sleep(10)
        }
        return deployState
    }

    override fun getDeployState(device: IDevice): JuggDeployState {
        return deployStateMap[device.serialNumber] ?: getNewDeployState(device)
    }

    override fun updateDeployState(device: IDevice): JuggDeployState {
        val state = getNewDeployState(device)
        deployStateMap = deployStateMap + (device.serialNumber to state)
        return state
    }

    private fun getNewDeployState(): JuggDeployState {
        deployStateMap = deployTargetManager.getSelectedDevices().associate {
            it.serialNumber to getNewDeployState(it)
        }
        return if (deployStateMap.isEmpty()) {
            getNewDeployState(null)
        } else {
            deployStateMap.minByOrNull { it.value.state.ordinal }!!.value
        }
    }

    private fun getNewDeployState(device: IDevice?): JuggDeployState {
        val hostState = hostDeployStateResolver.resolve(device, deployTargetManager.getPackageNameOrNull())
        logger.debug("host deploy state: $hostState")
        return when {
            !deployHistoryManager.hasBeenFullCompiled -> JuggDeployState(
                JuggDeployState.State.READY_FULL_COMPILE,
                "not gradle compile yet",
                hostState,
            )
            deployHistoryManager.isLastFullCompileFailed -> JuggDeployState(
                JuggDeployState.State.READY_FULL_COMPILE,
                "last gradle compile not success",
                hostState,
            )
            isBuildFileChanged -> JuggDeployState(
                JuggDeployState.State.READY_FULL_COMPILE,
                "$whatBuildFileChanged changed",
                hostState,
            )
            hostState.state != IdeDeployState.State.OK -> JuggDeployState(
                JuggDeployState.State.READY_INCREMENTAL_COMPILE,
                hostState.message,
                hostState,
            )
            else -> JuggDeployState.READY
        }
    }
}

/**
 * Resolves device deployment state without coupling the shared manager to an IDE runtime.
 */
fun interface IHostDeployStateResolver {
    fun resolve(device: IDevice?, packageName: String?): IdeDeployState
}
