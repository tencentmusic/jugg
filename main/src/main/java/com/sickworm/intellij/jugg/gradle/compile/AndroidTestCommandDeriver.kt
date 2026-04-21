package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger

/**
 * AndroidTestCommandDeriver derives the androidTest compile command and APK glob from the
 * app-oriented counterparts stored in the user's RunConfig, so that the RunConfig itself
 * is never mutated.
 *
 * Rules:
 *  - compileCommand: appends `:<module>:assemble<Variant>AndroidTest` for every assemble task found.
 *    Falls back to appending `:app:assembleDebugAndroidTest` for non-assemble commands (e.g. bundle).
 *    Idempotent: already-present AndroidTest tasks are not duplicated.
 *  - outputApkName: for each `<module>/build/outputs/apk/<variant>/&#42;.apk` segment appends
 *    `<module>/build/outputs/apk/androidTest/<variant>/&#42;.apk`. Idempotent. Segments are `;`-separated.
 */
object AndroidTestCommandDeriver {

    private val logger: Logger by lazy { Logger.getInstance(AndroidTestCommandDeriver::class.java) }

    private val assembleTaskRegex = Regex(""":(\w+):assemble(\w+)""")

    fun deriveCompileCommand(appCompileCommand: String): String {
        if (appCompileCommand.contains("AndroidTest")) {
            return appCompileCommand
        }

        val matches = assembleTaskRegex.findAll(appCompileCommand).toList()
        if (matches.isEmpty()) {
            logger.warn("AndroidTestCommandDeriver: no assemble task found in '$appCompileCommand'; appending :app:assembleDebugAndroidTest as fallback")
            return "$appCompileCommand :app:assembleDebugAndroidTest"
        }

        val testTasks = matches.joinToString(" ") { m ->
            val module = m.groupValues[1]
            val variant = m.groupValues[2]
            ":$module:assemble${variant}AndroidTest"
        }
        return "$appCompileCommand $testTasks"
    }

    /**
     * Derives the androidTest APK glob(s) from [appOutputApkName].
     * Segments are separated by `;`.
     */
    fun deriveOutputApkName(appOutputApkName: String): String {
        val apkOutputPattern = Regex("""([\w/.\-*]+/build/outputs/apk/)(\w+/)?(\w+/\*\.apk)""")
        val segments = appOutputApkName.split(";").map { it.trim() }.filter { it.isNotEmpty() }

        val allSegments = mutableListOf<String>()
        allSegments.addAll(segments)

        for (segment in segments) {
            if (segment.contains("androidTest")) continue

            val match = apkOutputPattern.find(segment)
            if (match != null) {
                val prefix = match.groupValues[1]     // e.g. "app/build/outputs/apk/"
                val flavorPart = match.groupValues[2] // e.g. "development/" or ""
                val variantFile = match.groupValues[3] // e.g. "debug/*.apk"
                val testGlob = "${prefix}androidTest/${flavorPart}${variantFile}"
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
}
