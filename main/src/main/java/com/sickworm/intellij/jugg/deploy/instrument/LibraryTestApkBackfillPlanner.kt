package com.sickworm.intellij.jugg.deploy.instrument

import com.sickworm.intellij.jugg.gradle.script.camelCompat
import com.sickworm.intellij.jugg.project.data.ModuleInfo

/**
 * Derives the narrow Gradle build task and APK lookup pattern for one missing library Test APK.
 */
object LibraryTestApkBackfillPlanner {

    fun plan(module: ModuleInfo): LibraryTestApkBackfillPlan {
        val ownerModule = module.name.substringBefore(".androidTest")
        val testVariant = module.buildVariant.removeSuffix("AndroidTest")
        val buildPath = module.buildPathInfo.buildDir.relativeTo(module.projectRootDir).path.replace('\\', '/')
        val variantPath = testVariant.camelToPath()
        return LibraryTestApkBackfillPlan(
            gradleTask = ":" + ownerModule.replace('.', ':') + ":assemble${testVariant.camelCompat}AndroidTest",
            outputApkPattern = "$buildPath/outputs/apk/androidTest/$variantPath/*.apk",
        )
    }

    private fun String.camelToPath(): String {
        return fold(StringBuilder()) { builder, char ->
            if (char.isUpperCase() && builder.isNotEmpty()) {
                builder.append('/')
            }
            builder.append(char.lowercaseChar())
        }.toString()
    }
}

data class LibraryTestApkBackfillPlan(
    val gradleTask: String,
    val outputApkPattern: String,
)
