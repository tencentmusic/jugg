package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ide.logic.PluginVersionComparator
import com.sickworm.intellij.jugg.ide.ui.JuggCommonNotification
import com.sickworm.intellij.jugg.loader.JuggHotUpdateBootstrap
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.server.protocols.HotUpdateData
import com.sickworm.intellij.jugg.runtime.PluginInfoReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Coordinates IDEA update checks, notifications, plugin installation, restart, and project reopening. */
class IdeaHotUpdateCoordinator(
    private val juggServer: JuggServer,
    loggerArg: Logger,
) {

    private val logger = loggerArg.getInstance("IdeaHotUpdateCoordinator")
    private val juggHotUpdateManager = JuggHotUpdateManager(
        juggServer,
        JuggHotUpdateBootstrap.currentEmbeddedBuildTime,
        JuggHotUpdateBootstrap.hotUpdateDir,
        logger,
    )
    private val ideaPluginDescriptor: IdeaPluginDescriptor?
        get() = PluginManagerCore.getPlugin(PluginId.getId("com.sickworm.intellij.jugg"))

    fun init(project: Project) {
        publishEmbeddedIfNeeded()
        start()
        processHotUpdateNotification(project)
        notifyInstallUpdateIfNeeded(project)
    }

    private fun processHotUpdateNotification(project: Project) {
        notifyHotUpdateIfNeeded(project)
        val referencedJarNames = JuggHotUpdateBootstrap.activeLoadManifest
            ?.jarFileNames
            ?.toSet()
            .orEmpty()
        juggHotUpdateManager.cleanupExpiredJars(referencedJarNames).forEach { logEvent("delete expired hot update jar: ${it.absolutePath}") }
    }

    private fun publishEmbeddedIfNeeded() {
        if (!juggHotUpdateManager.hotUpdateDir.exists()) {
            return
        }
        val embeddedLibDir = ideaPluginDescriptor?.pluginPath?.resolve("lib")?.toFile() ?: return
        if (juggHotUpdateManager.publishEmbeddedIfNeeded(embeddedLibDir)) {
            logEvent("publish embedded hot update: ${JuggHotUpdateBootstrap.currentEmbeddedBuildTime}")
        }
    }

    private fun start() {
        lastRequestTime = 0L // refresh request frequency limit

        juggServer.launchSafe {
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
        val isHotUpdate = juggHotUpdateManager.hasHotUpdateNotification()
        logger.debug("notifyHotUpdateIfNeeded $isHotUpdate")
        if (!isHotUpdate) {
            return
        }
        if (installPluginForLowerVersion()) {
            return
        }

        try {
            val currentHotUpdateData = juggHotUpdateManager.consumeHotUpdateNotification() ?: return
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
        val currentHotUpdateData = juggHotUpdateManager.readInstallUpdateNotification()
        logger.debug("notifyInstallUpdateIfNeeded ${currentHotUpdateData != null}")
        if (currentHotUpdateData == null) return
        try {
            logger.debug("notifyInstallUpdateIfNeeded isNeedReInstall=true, " +
                    "pluginVersion: ${ideaPluginDescriptor?.version}, " +
                    "targetVersion: ${currentHotUpdateData.targetVersion}")
            if (ideaPluginDescriptor?.version != currentHotUpdateData.targetVersion) {
                // it's not installed update by [installPlugin]
                logger.debug("notifyInstallUpdateIfNeeded not install update, return")
                if (!isUpdatedThisRuntime) {
                    // which means IDE has been rebooted, maybe update failed or override by other installation
                    juggHotUpdateManager.clearInstallUpdateNotification(currentHotUpdateData)
                }
                return
            }
            if (!juggHotUpdateManager.clearInstallUpdateNotification(currentHotUpdateData)) return
            juggHotUpdateManager.activateReinstallCandidate(PluginInfoReader.getPluginCompileTimestamp())
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

        val updateResult = juggHotUpdateManager.prepareUpdate(hotUpdateData)

        // Install remains IDEA-only; standalone loads the prepared manifest on its next daemon start.
        logEvent("downloadHotUpdate install plugin")
        val isInstallSuccess = installPlugin(updateResult, hotUpdateData.targetVersion)
        if (!isInstallSuccess && hotUpdateData.isNeedReinstall) {
            logEvent("downloadHotUpdate install failed")
            return
        }

        if (!juggHotUpdateManager.publishUpdateNotification(hotUpdateData)) {
            logEvent("downloadHotUpdate superseded by another update")
            return
        }

        val detailMap = mapOf(
            "from_version" to updateResult.previousVersion,
            "to_version" to hotUpdateData.targetVersion,
        )
        juggServer.report {
            action = "hot_update"
            detail = Gson().toJson(detailMap)
        }
        isUpdatedThisRuntime = true
        logEvent("downloadHotUpdate success! version: ${hotUpdateData.targetVersion}")
    }

    @Synchronized
    private fun installPlugin(updateResult: JuggHotUpdateResult, targetVersion: String): Boolean {
        logEvent("downloadHotUpdate install")
        val zipFile = File(juggHotUpdateManager.hotUpdateDir, "jugg_plugin_${System.currentTimeMillis()}.zip")
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            updateResult.jarFiles.forEach { file ->
                zip.putNextEntry(ZipEntry("jugg/lib/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            updateResult.standaloneBundleCandidate?.let { bundle ->
                zip.putNextEntry(ZipEntry("jugg/standalone/jugg-standalone-$targetVersion.zip"))
                bundle.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
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
            try {
                PluginInstaller.installAfterRestart(ideaPluginDescriptor, zipFile.toPath(),
                    ideaPluginDescriptor.pluginPath, true)
            } catch (e: Throwable) {
                logEvent("downloadHotUpdate install failed, try old api. error: $e")
                val clazz = PluginInstaller::class.java
                val method = clazz.getMethod("installAfterRestart",
                    Path::class.java, Boolean::class.java, Path::class.java, IdeaPluginDescriptor::class.java
                )
                method.invoke(null, zipFile.toPath(), true, ideaPluginDescriptor.pluginPath, ideaPluginDescriptor)
            }
            logEvent("downloadHotUpdate install success")
            return true
        } catch (e: Throwable) {
            logEvent("downloadHotUpdate install failed: $e")
            return false
        }
    }

    private fun installPluginForLowerVersion(): Boolean {
        if (isUpdatedThisRuntime) {
            return false
        }
        val installedVersion = ideaPluginDescriptor?.version
        val isNeedInstallOneTime = installedVersion != null && PluginVersionComparator.compare(installedVersion, "3.4.0") < 0
        logger.debug("installPluginForLowerVersion installedVersion $installedVersion, isNeedInstallOneTime $isNeedInstallOneTime")
        if (!isNeedInstallOneTime) {
            return false
        }
        logEvent("installPluginForLowerVersion requires a complete Marketplace or official plugin ZIP")
        return true
    }

    companion object {
        private const val START_DELAY_MILL = 2 * 60 * 1000L // 2 minutes
        /** request every hour */
        private const val REQUEST_DURATION_MILL = 4 * 60 * 60 * 1000L // 4 hours
        /** Jugg has multiple instances, each instance will request isolate. avoid request too frequency */
        private const val REQUEST_MIN_DURATION_MILL = 1 * 60 * 60 * 1000L // 1 hour

        private var lastRequestTime = 0L

        private val globalLogger = JuggLogger.getGlobalLogger("IdeaHotUpdateCoordinator")

        private fun logEvent(msg: String, e: Throwable? = null) {
            globalLogger.debug(msg, e)
        }

        @Volatile
        private var isUpdatedThisRuntime: Boolean = false
    }

}
