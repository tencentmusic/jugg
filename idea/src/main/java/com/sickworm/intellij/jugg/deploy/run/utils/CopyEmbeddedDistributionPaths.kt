package com.sickworm.intellij.jugg.deploy.run.utils

import android.annotation.SuppressLint
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.sickworm.intellij.jugg.logger.JuggLogger
import java.io.File

/**
 * Copied from EmbeddedDistributionPaths.getInstance().findEmbeddedInstaller()
 * because this method only exists in Intellij Idea
 */
class CopyEmbeddedDistributionPaths {

    fun get(): String {
        val path = "plugins/android/resources/installer"
        var file: File? = File(PathManager.getHomePath(), path)
        if (file!!.exists()) {
            return file.absolutePath
        }

        file = getOptionalIjPath(path)
        if (file != null && file.exists()) {
            return file.absolutePath
        }
        // Development mode
        assert(isAndroidStudio()) { "Bazel paths exist only in AndroidStudio development mode" }
        return File(
            PathManager.getHomePath(),
            "../../bazel-bin/tools/base/deploy/installer/android-installer"
        ).absolutePath
    }

    private fun isAndroidStudio(): Boolean {
        return try {
            val clazz = Class.forName("com.android.tools.idea.IdeInfo")
            val instance = clazz.getMethod("getInstance").invoke(null)
            clazz.getMethod("isAndroidStudio").invoke(instance) as Boolean
        } catch (e: Throwable) {
            JuggLogger.getGlobalLogger("CopyEmbeddedDistributionPaths").debug("IdeInfo unavailable; using IDE product code", e)
            ApplicationInfo.getInstance().build.productCode == "AI"
        }
    }

    @SuppressLint("PrivateApi")
    private fun getOptionalIjPath(@Suppress("SameParameterValue") path: String): File? {
        // IJ does not bundle some large resources from android plugin, and downloads them on demand.
        var lastError: Throwable? = null
        for (className in DOWNLOADER_CLASS_NAMES) {
            try {
                val clazz = Class.forName(className)
                val instance = clazz.getMethod("getInstance").invoke(null)
                clazz.getMethod("makeSureComponentIsInPlace").invoke(instance)
                return clazz.getMethod("getHostDir", String::class.java).invoke(instance, path) as File?
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: ClassNotFoundException("AndroidProfilerDownloader not found")
    }

    companion object {
        private val DOWNLOADER_CLASS_NAMES = listOf(
            "org.jetbrains.android.download.AndroidProfilerDownloader",
            "com.android.tools.idea.downloads.AndroidProfilerDownloader",
        )
    }
}
