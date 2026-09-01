package com.sickworm.intellij.jugg.project.change

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class GitFileChangesDetectorTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun updateChangedFiles_notifiesDeletedMissingUndeployedFiles() {
        val deployHistoryManager = mock<IDeployHistoryManager>()
        val deployFileManager = mock<DeployFileManager>()
        val detector = GitFileChangesDetector(
            deployHistoryManager,
            deployFileManager,
            mock<TaskRunnerManager>(),
            Logger.getInstance("GitFileChangesDetectorTest"),
        )
        val missingFile = File(temporaryFolder.root, "PayAlertDismissManager.kt")
        val newFile = temporaryFolder.newFile("GuideToExploreManager.kt")
        whenever(deployHistoryManager.getChangedFilesSinceLastFullCompiled()).thenReturn(listOf(newFile))
        whenever(deployFileManager.getUndeployedFiles()).thenReturn(
            listOf(ChangedFile(CompileFile.Type.Kotlin, missingFile, temporaryFolder.root, ModuleInfo.virtualModule))
        )
        var notifiedChanged: List<File> = emptyList()
        var notifiedDeleted: List<File> = emptyList()
        detector.startListen(object : FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                notifiedChanged = changedFiles
                notifiedDeleted = deletedFiles
            }
        })

        detector.updateChangedFiles()

        assertEquals(listOf(newFile), notifiedChanged)
        assertEquals(listOf(missingFile), notifiedDeleted)
        assertTrue(!missingFile.exists())
    }
}
