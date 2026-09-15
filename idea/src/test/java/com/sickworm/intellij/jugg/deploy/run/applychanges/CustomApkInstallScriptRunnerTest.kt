package com.sickworm.intellij.jugg.deploy.run.applychanges

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CustomApkInstallScriptRunnerTest {

    @Test
    fun `script path includes Android SDK platform tools and preserves IDE environment`() {
        val androidHome = Files.createTempDirectory("jugg_android_home").toFile()
        val platformTools = File(androidHome, "platform-tools").apply { mkdirs() }
        try {
            val environment = CustomApkInstallScriptRunner.withAndroidSdkPlatformTools(listOf(
                "PATH=/usr/bin",
                "ANDROID_HOME=${androidHome.path}",
                "CUSTOM_VALUE=kept",
            )).associate { it.substringBefore('=') to it.substringAfter('=') }

            assertEquals(
                listOf(platformTools.path, "/usr/bin"),
                environment.getValue("PATH").split(File.pathSeparator),
            )
            assertEquals("kept", environment["CUSTOM_VALUE"])
        } finally {
            androidHome.deleteRecursively()
        }
    }
}
