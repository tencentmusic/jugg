package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions

/**
 * Builds the APK lookup plan so required RunConfig outputs and optional history outputs keep separate failure semantics.
 */
internal object ApkLookupPlanner {

    fun build(options: JuggGradleCompileOptions): ApkLookupPlan {
        return ApkLookupPlan(
            requiredPatterns = splitPatterns(options.outputApkName),
            optionalLibraryTestPatterns = if (options.buildTarget == BuildTarget.ANDROID_TEST) {
                options.libraryTestApkOutputPatterns.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            } else {
                emptyList()
            },
        )
    }

    fun isFoundRemoteApkPath(apkPath: String?): Boolean {
        return !apkPath.isNullOrBlank()
    }

    private fun splitPatterns(patterns: String): List<String> {
        return patterns.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    }
}

internal data class ApkLookupPlan(
    val requiredPatterns: List<String>,
    val optionalLibraryTestPatterns: List<String>,
)
