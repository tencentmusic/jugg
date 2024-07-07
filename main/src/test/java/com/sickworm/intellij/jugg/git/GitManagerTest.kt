package com.sickworm.intellij.jugg.git

import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

open class GitManagerTest {

    protected var gitManager = GitManager(assetsAndroidDir)

    @Before
    @After
    open fun deleteGit() {
        repeat(100) { index ->
            val commitFile = File(gitManager.rootDir, "commit_file_$index.txt")
            commitFile.delete()
        }
        val commitFile = File(gitManager.rootDir, "commit_file.txt")
        commitFile.delete()
        gitManager.deleteGit()
    }

    @Test
    open fun testInit() {
        gitManager.deleteGit()
        assertFalse(gitManager.hasInitGit)
        gitManager.init()
        assertTrue(gitManager.hasInitGit)

        val uncommittedFiles = gitManager.getUncommittedFiles()
        assertTrue(uncommittedFiles.isNotEmpty())
        assertEquals(0, gitManager.getCurrentBranchCommitSize())
        assertEquals(null, gitManager.getLastCommitHash())

        gitManager.addAllAndCommit("first commit")
        val uncommittedFilesNew = gitManager.getUncommittedFiles()
        assertTrue(uncommittedFilesNew.isEmpty())
        assertEquals(1, gitManager.getCurrentBranchCommitSize())
    }

    @Test
    fun testBasicOperation() {
        testInit()
        assertTrue(gitManager.hasInitGit)

        val firstCommit = gitManager.getLastCommitHash()
        assertEquals(40, firstCommit?.length)

        val commitFile = File(gitManager.rootDir, "commit_file.txt")
        repeat(100) { index ->
            val commitCount = 2 + index // we have one commit already, so starts with 2
            commitFile.writeText("$commitCount")
            val uncommittedFileBeforeCommit = gitManager.getUncommittedFiles()
            assertTrue(uncommittedFileBeforeCommit.isNotEmpty())
            gitManager.addAllAndCommit("commit $commitCount")
            val uncommittedFileAfterCommit = gitManager.getUncommittedFiles()
            assertTrue(uncommittedFileAfterCommit.isEmpty())
            assertEquals(commitCount, gitManager.getCurrentBranchCommitSize())
            val commitHash = gitManager.getLastCommitHash()
            assertEquals(40, commitHash?.length)
        }
        commitFile.delete()

        gitManager.deleteGit()
    }

    @Test
    fun testDiff() {
        testInit()
        assertTrue(gitManager.hasInitGit)

        gitManager.addAllAndCommit("first commit")
        val firstHash = gitManager.getLastCommitHash()
        assertNotNull(firstHash)

        var lastHash: String = firstHash
        val allFiles = mutableListOf<File>()
        repeat(100) { index ->
            val commitFile = File(gitManager.rootDir, "commit_file_$index.txt")
            allFiles.add(commitFile)
            assertTrue(!commitFile.exists())

            commitFile.writeText("$index")
            gitManager.addAllAndCommit("commit $index")

            val newCommitHash = gitManager.getLastCommitHash()
            assertNotNull(newCommitHash)
            val changedFilesInOneCommit = gitManager.getChangedFiles(lastHash, newCommitHash)
            assertEquals(1, changedFilesInOneCommit.size)
            val changedFilesInAllCommit = gitManager.getChangedFiles(firstHash, newCommitHash)
            assertEquals(index + 1, changedFilesInAllCommit.size)

            val filterChangedFiles = gitManager.filterChangedFiles(firstHash, allFiles)
            assertEquals(allFiles.sortedBy { it.name }, filterChangedFiles.sortedBy { it.name })
            val lastFilterChangedFiles = gitManager.filterChangedFiles(lastHash, listOf(commitFile))
            assertEquals(listOf(commitFile), lastFilterChangedFiles)

            lastHash = newCommitHash
        }

        repeat(100) { index ->
            val commitFile = File(gitManager.rootDir, "commit_file_$index.txt")
            commitFile.delete()
        }
    }
}