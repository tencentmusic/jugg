package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals

class StandaloneCompileEnvironmentSourceTest {

    @Test
    fun `android sdk falls back to project local properties`() {
        val projectDir = Files.createTempDirectory("jugg-standalone-sdk").toFile()
        val sdkDir = projectDir.resolve("android-sdk").apply { mkdirs() }
        projectDir.resolve("local.properties").writeText("sdk.dir=${sdkDir.path}\n")

        val androidHome = StandaloneCompileEnvironmentSource(projectDir, emptyMap())
            .getAndroidHome(Logger.getInstance("StandaloneCompileEnvironmentSourceTest"))

        assertEquals(sdkDir.canonicalFile, androidHome)
    }
}
