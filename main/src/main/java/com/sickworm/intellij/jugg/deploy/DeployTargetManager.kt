package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException

/**
 * Manage device, run config, built apk.
 */
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
            logger.error("restartApp failed", e)
            false
        }
    }

    override fun restartApp(device: IDevice): Boolean {
        return try {
            AdbCmdHelper(device, logger).startDefaultApp(getPackageName(), apks, isRestart = true)
            true
        } catch (e: Exception) {
            logger.error("restartApp failed", e)
            false
        }
    }

    override fun isAppForeground(device: IDevice): Boolean {
        return try {
            AdbCmdHelper(device, logger).isAppForeground(getPackageName())
        } catch (e: Exception) {
            logger.error("isAppForeground failed", e)
            false
        }
    }

    private fun getPackageName(): String {
        val apks = getApks()
        if (apks.isEmpty()) {
            throw JuggInternalException.getPackageNameFailedApkNotFound()
        }
        if (apks.size > 1) {
            throw JuggException.notSupportMultiApk()
        }
        return apks.first().applicationId
    }

}
