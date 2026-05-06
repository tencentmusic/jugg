package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger

/**
 * AndroidTestCommandDeriver derives the androidTest compile command and APK glob from the
 * app-oriented counterparts stored in the user's RunConfig, so that the RunConfig itself
 * is never mutated.
 *
 * Rules:
 *  - compileCommand: no longer modified here; readProjectInfo.gradle.kts injects androidTest tasks.
 *  - outputApkName: for each `<module>/build/outputs/apk/<variant>/&#42;.apk` segment appends
 *    `<module>/build/outputs/apk/androidTest/<variant>/&#42;.apk`. Idempotent. Segments are `;`-separated.
 */
object AndroidTestCommandDeriver {

    private val logger: Logger by lazy { Logger.getInstance(AndroidTestCommandDeriver::class.java) }

    fun deriveCompileCommand(appCompileCommand: String): String {
        return appCompileCommand
    }

    /**
     * Derives the androidTest APK glob(s) from [appOutputApkName].
     * Segments are separated by `;`.
     */
    fun deriveOutputApkName(appOutputApkName: String): String {
        val segments = appOutputApkName.split(";").map { it.trim() }.filter { it.isNotEmpty() }

        val allSegments = mutableListOf<String>()
        allSegments.addAll(segments)

        for (segment in segments) {
            if (segment.contains("androidTest")) continue

            val testGlob = deriveTestApkGlob(segment)
            if (testGlob != null) {
                if (!allSegments.contains(testGlob)) {
                    allSegments.add(testGlob)
                }
            } else {
                logger.warn("AndroidTestCommandDeriver: cannot parse outputApkName segment '$segment'; appending *-androidTest.apk fallback")
                val fallback = "*-androidTest.apk"
                if (!allSegments.contains(fallback)) {
                    allSegments.add(fallback)
                }
            }
        }

        return allSegments.joinToString(";")
    }

    private fun deriveTestApkGlob(segment: String): String? {
        val apkOutputMarker = "/build/outputs/apk/"
        val markerIndex = segment.indexOf(apkOutputMarker)
        if (markerIndex < 0) {
            return null
        }

        val prefix = segment.substring(0, markerIndex + apkOutputMarker.length)
        val outputParts = segment.substring(prefix.length).split("/").filter { it.isNotEmpty() }
        if (outputParts.size < 2) {
            return null
        }

        val variantDirs = outputParts.dropLast(1)
        return "${prefix}androidTest/${variantDirs.joinToString("/")}/*.apk"
    }
}
