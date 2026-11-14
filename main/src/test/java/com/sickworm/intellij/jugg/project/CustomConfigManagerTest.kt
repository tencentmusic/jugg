package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.project.change.IFileChangesHandler
import com.sickworm.intellij.jugg.project.runtime.ProjectCustomConfigManager
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/** Verifies that incomplete project JSON preserves configured compiler extensions. */
class CustomConfigManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `custom compiler config without build rules retains compiler`() {
        val configDir = temporaryFolder.newFolder()
        configDir.resolve("custom_config.json").writeText("""
            {"customCompilers":[{"jarFileName":"adapter.jar","path":"adapter.jar","md5":"abc"}]}
        """.trimIndent())
        val handler = mock<IFileChangesHandler>()
        val compilers = mock<CustomCompilerManager>()
        val manager = ProjectCustomConfigManager(configDir, mock(), mock(), handler, mock(), mock(), compilers)

        assertTrue(manager.refresh())
        verify(handler).updateBuildFileRules(emptyList(), emptyList())
        verify(compilers).updateCustomCompilers(argThat { single().path == "adapter.jar" })
    }

    @Test
    fun `complete config retains its build rules`() {
        val configDir = temporaryFolder.newFolder()
        configDir.resolve("custom_config.json").writeText("""
            {"buildFileList":["old.gradle"],"buildFileRules":["*.gradle"],
             "dontFilterIgnoredFileRules":["generated.gradle"]}
        """.trimIndent())
        val handler = mock<IFileChangesHandler>()
        val manager = ProjectCustomConfigManager(configDir, mock(), mock(), handler, mock(), mock(), mock())

        assertTrue(manager.refresh())
        verify(handler).updateBuildFileRules(listOf("*.gradle"), emptyList())
    }
}
