package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ide.ui.JuggCommonNotification
import com.sickworm.intellij.jugg.loader.JuggHotUpdateManager
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.server.protocols.HotUpdateData
import com.sickworm.intellij.jugg.server.protocols.NotificationData
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Download hot update jars from Jugg server.
 * Jugg has one isolate instance for each project, but only one instance will do the download work.
 */
class JuggHotUpdateDownloader(private val juggServer: JuggServer, loggerArg: Logger) {

    private val logger = loggerArg.getInstance("JuggHotUpdateDownloader")
    private val hotUpdateDataFile = File(JuggHotUpdateManager.hotUpdateDir, "hot_update_data.json")
    private val firstUpdateFlag = File(JuggHotUpdateManager.hotUpdateDir, "first_update_flag")

    private val listener = object : HotUpdateListener {
        override fun onEvent(msg: String, e: Throwable?) {
            logger.debug(msg, e)
        }
    }

    fun init(project: Project) {
        start()
        notifyHotUpdateIfNeeded(project)
    }

    private fun start() {
        hotUpdateListeners.add(WeakReference(listener)) // listen hot update event
        lastRequestTime = 0L // refresh request frequency limit

        juggServer.launch {
            while (juggServer.isActive) {
                try {
                    val hotUpdateData = checkHotUpdate()
                    if (hotUpdateData != null && hotUpdateData.isNeedUpdate) {
                        downloadHotUpdate(hotUpdateData)
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
        if (!firstUpdateFlag.exists()) {
            return
        }
        try {
            val currentHotUpdateData = Gson().fromJson(hotUpdateDataFile.readText(), HotUpdateData::class.java)
            firstUpdateFlag.delete()
            val notificationData = currentHotUpdateData.updateInfo
            if (notificationData != null) {
                JuggCommonNotification(project).show(notificationData)
            }
        } catch (e: Exception) {
            logEvent("Error while notifyHotUpdateIfNeeded: ", e)
            return
        }
    }

    @Synchronized
    private fun checkHotUpdate(): HotUpdateData? {
        logger.debug("checkHotUpdate")
        val durationSinceLastUpdate = System.currentTimeMillis() - lastRequestTime
        if (durationSinceLastUpdate < REQUEST_MIN_DURATION_MILL) {
            logger.debug("Too frequent request, skip this time. durationSinceLastUpdate: $durationSinceLastUpdate")
            return null
        }

        val hotUpdateData: HotUpdateData? = juggServer.checkHotUpdate()
        logEvent("checkHotUpdate: $hotUpdateData")
        return hotUpdateData
    }

    @Synchronized
    private fun downloadHotUpdate(hotUpdateData: HotUpdateData) {
        // 1. compare with current hot update data
        logEvent("downloadHotUpdate start")
        var currentHotUpdateData: HotUpdateData? = null
        if (hotUpdateDataFile.exists()) {
            try {
                currentHotUpdateData = Gson().fromJson(hotUpdateDataFile.readText(), HotUpdateData::class.java)
            } catch (e: Exception) {
                logEvent("downloadHotUpdate get currentHotUpdateData failed: $e")
            }
        }
        val needDownloadJars = hotUpdateData.uniqueNames.toMutableSet()
        currentHotUpdateData?.uniqueNames?.forEach {
            needDownloadJars.remove(it)
        }

        // 2. download missing jars
        logEvent("downloadHotUpdate needDownloadJars: $needDownloadJars")
        val downloadFiles = mutableListOf<File>()
        needDownloadJars.forEach { key ->
            val url = hotUpdateData.jarFileInfos.first { it.uniqueName == key }.url
            val tmpDownloadFile = File(JuggHotUpdateManager.storageDir, "$key.tmp")
            tmpDownloadFile.delete()
            logEvent("download $key start, url $url")
            try {
                juggServer.downloadFile(url, tmpDownloadFile)
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
        val currentJarFiles = JuggHotUpdateManager.storageDir.listFiles()!!.map { it.path }
        val missingJarFiles = expectJarFiles.filter { !currentJarFiles.contains(it) }
        if (missingJarFiles.isNotEmpty()) {
            logEvent("jar file missing after downloaded: $missingJarFiles")
            throw IllegalStateException("jar file missing after downloaded: $missingJarFiles")
        }

        // 4. record new hot update data
        logEvent("downloadHotUpdate write new hot update data")

        val tmpHotUpdateDataFile = File("${hotUpdateDataFile.path}.tmp")
        tmpHotUpdateDataFile.writeText(Gson().toJson(hotUpdateData))
        val tmpLoadListFile = File("${JuggHotUpdateManager.loadListFile.path}.tmp")
        tmpLoadListFile.writeText(hotUpdateData.uniqueNames.joinToString("\n"))

        hotUpdateDataFile.delete()
        JuggHotUpdateManager.loadListFile.delete()

        tmpHotUpdateDataFile.renameTo(hotUpdateDataFile)
        tmpLoadListFile.renameTo(JuggHotUpdateManager.loadListFile)
        firstUpdateFlag.createNewFile()

        val detailMap = mapOf(
            "from_version" to currentHotUpdateData?.targetVersion,
            "to_version" to hotUpdateData.targetVersion,
        )
        juggServer.report {
            action = "hot_update"
            detail = Gson().toJson(detailMap)
        }
        logEvent("downloadHotUpdate success! version: ${hotUpdateData.targetVersion}")
    }

    private val HotUpdateData.uniqueNames get() = jarFileInfos.map { it.uniqueName }

    companion object {
        /** request every hour */
        private const val REQUEST_DURATION_MILL = 60 * 60 * 1000L // 1 hour
        /** Jugg has multiple instances, each instance will request isolate. avoid request too frequency */
        private const val REQUEST_MIN_DURATION_MILL = 30 * 60 * 1000L // 30 minutes

        private var lastRequestTime = 0L

        private val hotUpdateListeners = CopyOnWriteArrayList<WeakReference<HotUpdateListener>>()
        private fun logEvent(msg: String, e: Throwable? = null) {
            hotUpdateListeners.forEach {
                it.get()?.onEvent(msg, e)
            }
        }
    }

    /**
     * Use to log hot update event for all projects. Only one instance will do the download work.
     */
    private interface HotUpdateListener {
        fun onEvent(msg: String, e: Throwable?)
    }
}
