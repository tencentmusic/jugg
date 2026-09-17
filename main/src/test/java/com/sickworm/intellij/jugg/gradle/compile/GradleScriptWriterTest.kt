package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleScriptWriterTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `replaces a stale project init script with the bundled plugin script`() {
        val pathManager = JuggPathManager(temporaryFolder.root)
        val initScript = pathManager.initGradleFilePath
        initScript.parentFile.mkdirs()
        initScript.writeText("collector.mustRunAfter(localTasks)")

        GradleScriptWriter(pathManager, Logger.getInstance(GradleScriptWriterTest::class.java))
            .writeInitGradleFile()

        val text = initScript.readText()
        assertTrue(text.contains("collector.dependsOn(localTaskPaths)"))
        assertTrue(text.contains("projectInfoReaderManager.configureExternalBuildInfoCollector()"))
        assertFalse(text.contains("mustRunAfter(localTasks)"))
    }
}
