package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException

class DeployTargetManager(
    private val project: Project,
): IDeployTargetManager {

    private val logger = JuggLogger.getInstance(project, "DeployTargetManager")

    private var apks: List<ApkInfo> = emptyList()

    override fun setApks(apks: List<ApkInfo>) {
        logger.debug("setApks: ${apks.map { it.files.firstOrNull()?.apkFile?.absolutePath }}")
        this.apks = apks
    }

    override fun getApks(): List<ApkInfo> {
        return apks
    }

    override fun getDevices(): List<IDevice> {
        try {
            val devices = AsDeployerCompat.getDevices(project)
            if (devices.isNullOrEmpty()) {
                return emptyList()
            }

            return devices
        } catch (e: Exception) {
            if (e is JuggException) {
                logger.debug("getDevice failed: ${e.message}")
            } else {
                logger.error("getDevice failed", e)
            }
            throw e
        }
    }

    override fun startApp(device: IDevice): Boolean {
        return try {
            AdbCmdHelper(device, logger).startDefaultApp(getPackageName(), apks, isRestart = false)
            true
        } catch (e: Exception) {
            logger.error("startApp failed", e)
            false
        }
    }

    override fun restartApp(device: IDevice): Boolean {
        return try {
            AdbCmdHelper(device, logger).startDefaultApp(getPackageName(), apks, isRestart = true)
            true
        } catch (e: Exception) {
            logger.error("RestartApp failed, got exception: ", e)
            false
        }
    }

    override fun isAppForeground(device: IDevice): Boolean {
        return try {
            AdbCmdHelper(device, logger).isAppForeground(getPackageName())
        } catch (e: Exception) {
            logger.error("isAppForeground failed, got exception:", e)
            false
        }
    }

    override fun getPackageName(): String {
        val apks = getApks()
        if (apks.isEmpty()) {
            throw JuggInternalException.getPackageNameFailedApkNotFound()
        }
        if (apks.size > 1) {
            throw JuggException.notSupportMultiApk()
        }
        return apks.first().applicationId
    }

    override fun dumpErrorLogs(): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append("[Dump error logs start]\n")
        val devices = getDevices()
        stringBuilder.append("Devices: ${devices.map { it.name }}\n")
        devices.forEach { device ->
            stringBuilder.append("[Dump Device: ${device.name} start]\n")
            stringBuilder.append(AdbCmdHelper(device, logger).dumpErrorLog())
            stringBuilder.append("\n[Dump Device: ${device.name} end]\n")
        }
        stringBuilder.append("[Dump error logs end]\n")
        return stringBuilder.toString()
    }
}
