package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.Variant
import org.junit.Assert.assertEquals
import org.junit.Test

class UtilsTest {

    @Test
    fun `prefers profile variant named by requested task when debug tasks also execute`() {
        val variants = listOf(
            Variant("debug", "debug"),
            Variant("profile", "debug"),
            Variant("release", "release"),
        )
        val taskNames = setOf(
            "processDebugManifest",
            "processProfileManifest",
        )

        val result = guessBuildVariant(
            moduleName = "app",
            variants = variants,
            taskNames = taskNames,
            startTaskNames = listOf(":app:assembleProfile"),
        )

        assertEquals("profile", result)
    }
}
