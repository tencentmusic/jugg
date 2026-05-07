package com.sickworm.intellij.jugg.deploy.instrument

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Selects the instrumentation APK for one AndroidTestRunSpec.
 */
object AndroidTestApkSelector {

    fun select(
        spec: AndroidTestRunSpec,
        apks: List<ApkInfo>,
        projectDir: File,
        modules: Collection<ModuleInfo>,
    ): ApkInfo? {
        val sourcePath = spec.sourcePath
        if (!sourcePath.isNullOrBlank()) {
            return AndroidTestTargetResolver.resolve(sourcePath, projectDir, modules, apks).testApk
        }
        return apks.firstOrNull { it.isTestApk }
    }
}
