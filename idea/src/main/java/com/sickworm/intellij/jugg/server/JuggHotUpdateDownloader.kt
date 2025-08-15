package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.gradle.compile.zipFiles
import com.sickworm.intellij.jugg.ide.ui.JuggCommonNotification
import com.sickworm.intellij.jugg.loader.JuggHotUpdateManager
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.server.protocols.HotUpdateData
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * Download hot update jars from Jugg server.
 * Jugg has one isolate instance for each project, but only one instance will do the download work.
 */
class JuggHotUpdateDownloader(private val juggServer: JuggServer, loggerArg: Logger) {

    private val logger = loggerArg.getInstance("JuggHotUpdateDownloader")
    private val hotUpdateDataFile = File(JuggHotUpdateManager.hotUpdateDir, "hot_update_data.json")
    private val hotUpdateFlag = File(JuggHotUpdateManager.hotUpdateDir, "first_update_flag")
    private val installUpdateFlag = File(JuggHotUpdateManager.hotUpdateDir, "install_update_flag")

    private val ideaPluginDescriptor: IdeaPluginDescriptor?
        get() = PluginManagerCore.getPlugin(PluginId.getId("com.sickworm.intellij.jugg"))

    fun init(project: Project) {
        start()
        notifyHotUpdateIfNeeded(project)
        notifyInstallUpdateIfNeeded(project)
    }

    private fun start() {
        lastRequestTime = 0L // refresh request frequency limit

        juggServer.launch {
            delay(START_DELAY_MILL) // delay for first request
            while (juggServer.isActive) {
                try {
                    val hotUpdateData = checkHotUpdate(isPositiveCheck = false)
                    if (hotUpdateData != null && hotUpdateData.isNeedUpdate) {
                        downloadAndInstallUpdate(hotUpdateData)
                    }
                } catch (e: Exception) {
                    logEvent("Error while hot update", e)
                    logEvent("Error while hot update, exit hot update")
                }
                delay(REQUEST_DURATION_MILL)
            }
        }
    }

    private fun notifyHotUpdateIfNeeded(project: Project) {
        val isHotUpdate = hotUpdateFlag.exists()
        logger.debug("notifyHotUpdateIfNeeded $isHotUpdate")
        if (!isHotUpdate) {
            return
        }
        try {
            val currentHotUpdateData = Gson().fromJson(hotUpdateDataFile.readText(), HotUpdateData::class.java)
            hotUpdateFlag.delete()
            val notificationData = currentHotUpdateData.updateInfo
            logger.debug("show notifyHotUpdateIfNeeded ${currentHotUpdateData.updateInfo}")
            if (notificationData != null) {
                JuggCommonNotification(project).show(notificationData)
            }
        } catch (e: Exception) {
            logEvent("Error while notifyHotUpdateIfNeeded: ", e)
            return
        }
    }

    private fun notifyInstallUpdateIfNeeded(project: Project) {
        val isInstallUpdate = installUpdateFlag.exists()
        logger.debug("notifyInstallUpdateIfNeeded $isInstallUpdate")
        if (!isInstallUpdate) {
            return
        }
        try {
            val currentHotUpdateData = Gson().fromJson(hotUpdateDataFile.readText(), HotUpdateData::class.java)
            logger.debug("notifyInstallUpdateIfNeeded isNeedReInstall=true, " +
                    "pluginVersion: ${ideaPluginDescriptor?.version}, " +
                    "targetVersion: ${currentHotUpdateData.targetVersion}")
            if (ideaPluginDescriptor?.version != currentHotUpdateData.targetVersion) {
                // it's not installed update by [installPlugin]
                logger.debug("notifyInstallUpdateIfNeeded not install update, return")
                if (!isUpdatedThisRuntime) {
                    // which means IDE has been rebooted, maybe update failed or override by other installation
                    installUpdateFlag.delete()
                }
                return
            }
            installUpdateFlag.delete()
            val notificationData = currentHotUpdateData.updateInfo
            logger.debug("show notifyInstallUpdateIfNeeded ${currentHotUpdateData.updateInfo}")
            if (notificationData != null) {
                JuggCommonNotification(project).show(notificationData)
            }
        } catch (e: Exception) {
            logEvent("Error while notifyInstallUpdateIfNeeded: ", e)
            return
        }
    }

    fun checkHotUpdate(isPositiveCheck: Boolean): HotUpdateData? {
        logger.debug("checkHotUpdate")

        val durationSinceLastUpdate = System.currentTimeMillis() - lastRequestTime
        if (durationSinceLastUpdate < REQUEST_MIN_DURATION_MILL) {
            logger.debug("Too frequent request, skip this time. durationSinceLastUpdate: $durationSinceLastUpdate")
            return null
        }

        val hotUpdateData: HotUpdateData? = juggServer.checkHotUpdate(isPositiveCheck)
        logEvent("checkHotUpdate: $hotUpdateData")
        return hotUpdateData
    }

    @Synchronized
    fun downloadAndInstallUpdate(hotUpdateData: HotUpdateData) {
        // 0. check has been installed and not reboot yet
        if (isUpdatedThisRuntime) {
            logEvent("downloadHotUpdate isUpdatedThisRuntime=true, just mark it success")
            return
        }

        // 1. compare with current hot update data
        logEvent("downloadHotUpdate start, target dir: ${JuggHotUpdateManager.storageDir}")
        var currentHotUpdateData: HotUpdateData? = null
        if (hotUpdateDataFile.exists()) {
            try {
                currentHotUpdateData = Gson().fromJson(hotUpdateDataFile.readText(), HotUpdateData::class.java)
            } catch (e: Exception) {
                logEvent("downloadHotUpdate get currentHotUpdateData failed: $e")
            }
        }
        val oldCurrentJarFiles = JuggHotUpdateManager.storageDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val needDownloadJars = hotUpdateData.uniqueNames.toMutableSet()
        oldCurrentJarFiles.forEach {
            needDownloadJars.remove(it)
        }

        // 2. download missing jars
        logEvent("downloadHotUpdate needDownloadJars: $needDownloadJars")
        val downloadFiles = mutableListOf<File>()
        needDownloadJars.forEach { key ->
            val jarFileInfo = hotUpdateData.jarFileInfos.first { it.uniqueName == key }
            val tmpDownloadFile = File(JuggHotUpdateManager.storageDir, "$key.tmp")
            tmpDownloadFile.delete()
            logEvent("download $key start, url ${jarFileInfo.url}")
            try {
                juggServer.downloadFile(jarFileInfo.url, tmpDownloadFile)
                val fileMd5 = tmpDownloadFile.md5()
                if (fileMd5 != jarFileInfo.md5) {
                    throw IllegalStateException("md5 check failed, expect: ${jarFileInfo.md5}, actual: $fileMd5")
                }
            } catch (e: Exception) {
                logEvent("download $key failed: $e")
                throw e
            }
            logEvent("download $key finished")
            val downloadFile = File(JuggHotUpdateManager.storageDir, key)
            downloadFile.delete()
            tmpDownloadFile.renameTo(downloadFile)
            downloadFiles.add(downloadFile)
        }

        // 3. check whether jar files is complete
        val expectJarFiles = hotUpdateData.uniqueNames.map {
            File(JuggHotUpdateManager.storageDir, it).path
        }
        val currentJarFiles = JuggHotUpdateManager.storageDir.listFiles()?.map { it.path } ?: emptySet()
        val missingJarFiles = expectJarFiles.filter { !currentJarFiles.contains(it) }
        if (missingJarFiles.isNotEmpty()) {
            logEvent("jar file missing after downloaded: $missingJarFiles")
            throw IllegalStateException("jar file missing after downloaded: $missingJarFiles")
        }

        // 4. record new hot update data
        logEvent("downloadHotUpdate write new hot update data")

        val tmpHotUpdateDataFile = File("${hotUpdateDataFile.path}.tmp")
        tmpHotUpdateDataFile.writeText(Gson().toJson(hotUpdateData))
        hotUpdateDataFile.delete()
        tmpHotUpdateDataFile.renameTo(hotUpdateDataFile)

        if (hotUpdateData.isNeedReinstall) {
            // is not compatible with hot update, do not update loadListFile, just installPlugin
        } else {
            JuggHotUpdateManager.loadListFile.delete()
            val tmpLoadListFile = File("${JuggHotUpdateManager.loadListFile.path}.tmp")
            tmpLoadListFile.writeText(hotUpdateData.uniqueNames.joinToString("\n"))
            tmpLoadListFile.renameTo(JuggHotUpdateManager.loadListFile)
        }

        // 5. zip and install, also install it if is hot update
        logEvent("downloadHotUpdate install plugin")
        val isInstallSuccess = installPlugin(expectJarFiles.map { File(it) })
        if (!isInstallSuccess && hotUpdateData.isNeedReinstall) {
            logEvent("downloadHotUpdate install failed")
            return
        }

        if (hotUpdateData.isNeedReinstall) {
            installUpdateFlag.createNewFile()
            hotUpdateFlag.delete()
        } else {
            installUpdateFlag.delete()
            hotUpdateFlag.createNewFile()
        }

        val detailMap = mapOf(
            "from_version" to currentHotUpdateData?.targetVersion,
            "to_version" to hotUpdateData.targetVersion,
        )
        juggServer.report {
            action = "hot_update"
            detail = Gson().toJson(detailMap)
        }
        isUpdatedThisRuntime = true
        logEvent("downloadHotUpdate success! version: ${hotUpdateData.targetVersion}")
    }

    private fun installPlugin(expectJarFiles: List<File>): Boolean {
        logEvent("downloadHotUpdate install")
        val zipFile = File(JuggHotUpdateManager.hotUpdateDir, "jugg_plugin_${System.currentTimeMillis()}.zip")
        zipFile.zipFiles(expectJarFiles, "jugg/lib/")
        if (!zipFile.exists() || zipFile.length() <= 0) {
            logEvent("downloadHotUpdate zip file is invalid, skip install")
            return false
        }

        try {
            val ideaPluginDescriptor =  ideaPluginDescriptor ?: run {
                logEvent("downloadHotUpdate install failed, plugin not found") // this should not happen
                return false
            }
            logEvent("install from $zipFile to ${ideaPluginDescriptor.pluginPath}")
            @Suppress("UnstableApiUsage")
            PluginInstaller.installAfterRestart(ideaPluginDescriptor, zipFile.toPath(),
                ideaPluginDescriptor.pluginPath, true)
            return true
        } catch (e: Exception) {
            logEvent("downloadHotUpdate install failed: $e")
            return false
        }
    }

    private val HotUpdateData.uniqueNames get() = jarFileInfos.map { it.uniqueName }

    companion object {
        private const val START_DELAY_MILL = 2 * 60 * 1000L // 2 minutes
        /** request every hour */
        private const val REQUEST_DURATION_MILL = 4 * 60 * 60 * 1000L // 4 hours
        /** Jugg has multiple instances, each instance will request isolate. avoid request too frequency */
        private const val REQUEST_MIN_DURATION_MILL = 1 * 60 * 60 * 1000L // 1 hour

        private var lastRequestTime = 0L

        private val globalLogger = JuggLogger.getGlobalLogger("JuggHotUpdateDownloader")

        private fun logEvent(msg: String, e: Throwable? = null) {
            globalLogger.debug(msg, e)
        }

        private fun File.md5(): String {
            val md = MessageDigest.getInstance("MD5")
            md.update(readBytes())
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private var isUpdatedThisRuntime: Boolean = false
    }

}
