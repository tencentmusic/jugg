package com.sickworm.intellij.jugg.deploy.run.utils

import android.annotation.SuppressLint
import com.android.tools.idea.IdeInfo
import com.intellij.openapi.application.PathManager
import org.jetbrains.android.download.AndroidProfilerDownloader
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
        assert(IdeInfo.getInstance().isAndroidStudio) { "Bazel paths exist only in AndroidStudio development mode" }
        return File(
            PathManager.getHomePath(),
            "../../bazel-bin/tools/base/deploy/installer/android-installer"
        ).absolutePath
    }

    @SuppressLint("PrivateApi")
    private fun getOptionalIjPath(@Suppress("SameParameterValue") path: String): File? {
        // IJ does not bundle some large resources from android plugin, and downloads them on demand.
        try {
            val instance = AndroidProfilerDownloader.getInstance()
            instance.makeSureComponentIsInPlace()
            return instance.getHostDir(path)
        } catch (e: Throwable) { // NoClassDefFoundError | ClassNotFoundException
            // compat with Build #IU-243.22562.218
            val clazz = Class.forName("com.android.tools.idea.downloads.AndroidProfilerDownloader")
            val instance = clazz.getMethod("getInstance").invoke(null)
            clazz.getMethod("makeSureComponentIsInPlace").invoke(instance)
            return clazz.getMethod("getHostDir", String::class.java).invoke(instance, path) as File?
        }
    }
}