package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.change.ChangedFile
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.File

class DeployFileManagerProjectWriteTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val taskRunnerManager = mock<TaskRunnerManager>()

    @Test
    fun `source file update should run as project write`() {
        val projectDir = temporaryFolder.newFolder("update-project")
        val sourceDir = File(projectDir, "src").apply { mkdirs() }
        val sourceFile = File(sourceDir, "Source.kt").apply { writeText("class Source") }
        val deployFileManager = createDeployFileManager(projectDir)

        deployFileManager.addChangedFile(
            listOf(
                ChangedFile(
                    type = CompileFile.Type.Kotlin,
                    file = sourceFile,
                    baseDir = sourceDir,
                    module = ModuleInfo.virtualModule,
                )
            )
        )

        verify(taskRunnerManager).runBackgroundSafe(
            eq("DeployFileManager#updateSourceFiles"),
            eq(0L),
            eq(true),
            eq(true),
            any(),
        )
    }

    @Test
    fun `source file removal should run as project write`() {
        val projectDir = temporaryFolder.newFolder("remove-project")
        val deployFileManager = createDeployFileManager(projectDir)
        val deletedFile = File(projectDir, "Deleted.kt")

        deployFileManager.removeChangedFile(listOf(deletedFile))

        verify(taskRunnerManager).runBackgroundSafe(
            eq("DeployFileManager#removeSourceFiles"),
            eq(0L),
            eq(true),
            eq(true),
            any(),
        )
    }

    private fun createDeployFileManager(projectDir: File): DeployFileManager {
        return DeployFileManager(
            pathManager = JuggPathManager(projectDir),
            taskRunnerManager = taskRunnerManager,
            logger = logger,
        )
    }
}
