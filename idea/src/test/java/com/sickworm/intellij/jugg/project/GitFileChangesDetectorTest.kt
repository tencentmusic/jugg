package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployContextRecoverInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
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
        val taskRunnerManager = mock<TaskRunnerManager>()
        val detector = GitFileChangesDetector(
            deployHistoryManager,
            deployFileManager,
            taskRunnerManager,
            TestGlobal.getLogger(),
        )

        val missingFile = File(temporaryFolder.root, "PayAlertDismissManager.kt")
        val newFile = temporaryFolder.newFile("GuideToExploreManager.kt")
        val recoverInfo = DeployContextRecoverInfo(
            changedFiles = listOf(newFile),
            compileContextInfo = CompileContextInfo(emptyList(), emptyMap()),
            deployedFiles = emptyList(),
        )
        whenever(deployHistoryManager.tryGetContextRecoverInfoFromDb(isOnInit = false)).thenReturn(recoverInfo)
        whenever(deployFileManager.getUndeployedFiles()).thenReturn(
            listOf(
                ChangedFile(
                    CompileFile.Type.Kotlin,
                    missingFile,
                    temporaryFolder.root,
                    ModuleInfo.virtualModule,
                ),
            ),
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

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
        }
    }
}
