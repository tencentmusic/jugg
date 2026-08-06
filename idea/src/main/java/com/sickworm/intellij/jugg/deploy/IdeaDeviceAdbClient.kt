package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.SyncException
import com.android.ddmlib.TimeoutException
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.sickworm.intellij.jugg.deploy.api.IDevice
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * IDevice-backed ADB operations used by Jugg runtime code outside Android Studio deployer compat.
 */
internal class IdeaDeviceAdbClient(
    deviceArg: IDevice,
    private val logger: com.android.utils.ILogger,
) {
    private val device = deviceArg.toStudioDevice()
    fun shell(
        args: Array<String>,
        input: InputStream? = null,
        timeout: Long,
        timeUnit: TimeUnit,
    ): ByteArray {
        return try {
            val receiver = ByteArrayOutputReceiver()
            device.executeShellCommand(args.joinToString(" "), receiver, timeout, timeUnit, input)
            receiver.toByteArray()
        } catch (e: ShellCommandUnresponsiveException) {
            throw IOException(e)
        } catch (e: TimeoutException) {
            throw IOException(e)
        } catch (e: AdbCommandRejectedException) {
            throw IOException(e)
        }
    }

    fun push(from: String, to: String): Boolean {
        return try {
            device.pushFile(from, to)
            true
        } catch (e: TimeoutException) {
            throw IOException(e)
        } catch (e: AdbCommandRejectedException) {
            throw IOException(e)
        } catch (e: SyncException) {
            throw IOException(e)
        }
    }

    fun uninstall(packageName: String): Boolean {
        return try {
            device.uninstallPackage(packageName)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getPids(packageName: String): List<Int> {
        if (!device.supportsFeature(com.android.ddmlib.IDevice.Feature.REAL_PKG_NAME)) {
            throw IllegalStateException("Device ${device.serialNumber}, do not support REAL_PKG_NAME")
        }
        return device.clients
            .filter { packageName == it.clientData.packageName }
            .map { it.clientData.pid }
    }

    fun getArch(pids: List<Int>): Deploy.Arch {
        var result = Deploy.Arch.ARCH_UNKNOWN
        pids.forEach { pid ->
            val current = getArch(pid)
            if (result == Deploy.Arch.ARCH_UNKNOWN) {
                result = current
            } else if (current != Deploy.Arch.ARCH_UNKNOWN && result != current) {
                logger.warning("Mixed ABIs detected: %s and %s", result, current)
            }
        }
        return result
    }

    private fun getArch(pid: Int): Deploy.Arch {
        val abi = device.clients
            .firstOrNull { it.clientData.pid == pid }
            ?.clientData
            ?.abi
            ?: return Deploy.Arch.ARCH_UNKNOWN
        return when {
            abi.startsWith("32-bit") -> Deploy.Arch.ARCH_32_BIT
            abi.startsWith("64-bit") -> Deploy.Arch.ARCH_64_BIT
            else -> Deploy.Arch.ARCH_UNKNOWN
        }
    }

    private class ByteArrayOutputReceiver : IShellOutputReceiver {
        private val stream = ByteArrayOutputStream()

        override fun addOutput(data: ByteArray, offset: Int, length: Int) {
            stream.write(data, offset, length)
        }

        override fun flush() = Unit

        override fun isCancelled(): Boolean = false

        fun toByteArray(): ByteArray = stream.toByteArray()
    }
}
