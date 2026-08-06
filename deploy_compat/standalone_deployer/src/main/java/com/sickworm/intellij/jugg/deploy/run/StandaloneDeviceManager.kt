package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IShellOutputReceiver
import com.android.tools.deployer.common.AdbClient
import com.android.utils.StdLogger
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.sickworm.intellij.jugg.deploy.api.IDevice
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Provides the standalone ddmlib device boundary used by the shared deploy lifecycle. */
class StandaloneDeviceManager(adbFile: File) : AutoCloseable {
    private val converter = StandaloneDeployApiConverter()
    private val bridge = acquireBridge(adbFile)
    private var closed = false

    fun devices(): List<IDevice> = bridge.devices.map(converter::toJuggDevice)

    fun findDevice(serial: String): IDevice? = bridge.devices.firstOrNull { it.serialNumber == serial }
        ?.let(converter::toJuggDevice)

    fun deviceOperations(device: IDevice): StandaloneDeviceOperations =
        StandaloneDeviceOperations(converter.toStudioDevice(device))

    override fun close() {
        if (closed) return
        closed = true
        releaseBridge()
    }

    class StandaloneDeviceOperations internal constructor(private val device: com.android.ddmlib.IDevice) {
        private val adbClient = AdbClient(device, StdLogger(StdLogger.Level.ERROR))
        val displayName: String
            get() = listOfNotNull(device.getProperty("ro.product.manufacturer"), device.getProperty("ro.product.model"))
                .joinToString(" ").ifBlank { device.name }
        val api: Int get() = device.version.apiLevel
        val serial: String get() = device.serialNumber
        val isOnline: Boolean get() = device.isOnline

        fun shell(cmd: String): String {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand(cmd, receiver, 30, TimeUnit.SECONDS)
            return receiver.output.trim()
        }

        fun shellScript(cmd: String): String {
            val escaped = cmd.replace("'", "'\\''")
            return shell("sh -c '$escaped'")
        }

        fun shellStreaming(cmd: String, lineConsumer: (String) -> Unit, cancelSignal: () -> Boolean) {
            val receiver = object : IShellOutputReceiver {
                private val buffer = StringBuilder()

                override fun addOutput(data: ByteArray, offset: Int, length: Int) {
                    buffer.append(String(data, offset, length, StandardCharsets.UTF_8))
                    var newlineIndex = buffer.indexOf('\n')
                    while (newlineIndex >= 0) {
                        lineConsumer(buffer.substring(0, newlineIndex).trimEnd('\r'))
                        buffer.delete(0, newlineIndex + 1)
                        newlineIndex = buffer.indexOf('\n')
                    }
                }

                override fun flush() {
                    if (buffer.isNotEmpty()) lineConsumer(buffer.toString().trimEnd('\r'))
                    buffer.clear()
                }

                override fun isCancelled(): Boolean = cancelSignal()
            }
            device.executeShellCommand(cmd, receiver, 1, TimeUnit.HOURS)
        }

        fun push(from: File, to: String) {
            device.pushFile(from.path, to)
        }

        fun pull(from: String, to: File) {
            to.parentFile?.mkdirs()
            device.pullFile(from, to.path)
        }

        fun deployArch(packageName: String): Deploy.Arch {
            return runCatching {
                Deploy.Arch.valueOf(adbClient.getArch(adbClient.getPids(packageName)).name)
            }.getOrDefault(Deploy.Arch.ARCH_UNKNOWN)
        }

        fun getProperty(name: String): String? = device.getProperty(name)
    }

    companion object {
        private var bridge: AndroidDebugBridge? = null
        private var referenceCount = 0

        @Synchronized
        private fun acquireBridge(adbFile: File): AndroidDebugBridge {
            AndroidDebugBridge.initIfNeeded(false)
            val result = bridge ?: AndroidDebugBridge.createBridge(adbFile.path, false).also { bridge = it }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!result.hasInitialDeviceList() && System.nanoTime() < deadline) Thread.sleep(50)
            check(result.hasInitialDeviceList()) { "ADB initial device list did not become ready within 10 seconds." }
            referenceCount++
            return result
        }

        @Synchronized
        private fun releaseBridge() {
            referenceCount--
            if (referenceCount > 0) return
            bridge = null
            referenceCount = 0
            AndroidDebugBridge.disconnectBridge()
            AndroidDebugBridge.terminate()
        }
    }
}
