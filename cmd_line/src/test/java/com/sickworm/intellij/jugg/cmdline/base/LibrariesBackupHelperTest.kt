package com.sickworm.intellij.jugg.cmdline.base

import com.sickworm.intellij.jugg.cmdline.StdLogger
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibrariesBackupHelperTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun backupPreservesProjectMetadata() {
        val projectDir = temporaryFolder.newFolder("project")
        val agpR8Classpath = temporaryFolder.newFile("builder.jar")
        val projectInfo = JuggProjectInfo(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(name = "app")),
            agpR8Classpath = agpR8Classpath,
        )

        val result = LibrariesBackupHelper(
            pathManager = JuggPathManager(projectDir),
            projectInfo = projectInfo,
            logger = StdLogger("LibrariesBackupHelperTest"),
        ).backup()

        assertEquals(agpR8Classpath, result.agpR8Classpath)
    }
}
