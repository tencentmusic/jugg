package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeployFileStateTrackerTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun addChangedFiles_ignoresSameSnapshotAfterSuccessfulCompile() {
        val tracker = DeployFileStateTracker()
        val sourceFile = createSourceFile("MainActivity.kt", "class MainActivity")
        val changedFile = changedFile(sourceFile)

        tracker.addChangedFiles(listOf(changedFile))
        tracker.updateUncompiledFiles(listOf(compileFile(sourceFile)), emptyList())
        val lateIdeEventFile = changedFile(sourceFile)

        val newFiles = tracker.addChangedFiles(listOf(lateIdeEventFile))

        assertTrue(newFiles.isEmpty())
        assertTrue(tracker.getUncompiledFiles().isEmpty())
        assertEquals(listOf(changedFile), tracker.getCompiledFiles())
        assertTrue(tracker.isNoFileChanges())
    }

    @Test
    fun addChangedFiles_reopensFileWhenSnapshotChangesAfterSuccessfulCompile() {
        val tracker = DeployFileStateTracker()
        val sourceFile = createSourceFile("MainActivity.kt", "class MainActivity")
        tracker.addChangedFiles(listOf(changedFile(sourceFile)))
        tracker.updateUncompiledFiles(listOf(compileFile(sourceFile)), emptyList())

        Thread.sleep(2)
        sourceFile.writeText("class MainActivity { fun changed() = Unit }")
        val nextChangedFile = changedFile(sourceFile)

        val newFiles = tracker.addChangedFiles(listOf(nextChangedFile))

        assertEquals(listOf(nextChangedFile), newFiles)
        assertEquals(listOf(nextChangedFile), tracker.getUncompiledFiles())
        assertTrue(tracker.getCompiledFiles().isEmpty())
    }

    private fun createSourceFile(name: String, content: String): File {
        return temporaryFolder.newFile(name).apply {
            writeText(content)
        }
    }

    private fun changedFile(file: File): ChangedFile {
        return ChangedFile(
            type = CompileFile.Type.Kotlin,
            file = file.absoluteFile,
            baseDir = temporaryFolder.root.absoluteFile,
            module = ModuleInfo.virtualModule,
        )
    }

    private fun compileFile(file: File): CompileFile {
        return CompileFile(
            type = CompileFile.Type.Kotlin,
            file = file.absoluteFile,
            baseDir = temporaryFolder.root.absoluteFile,
            module = ModuleInfo.virtualModule,
        )
    }
}
