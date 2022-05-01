package com.sickworm.intellij.jugg.git

import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitManagerTest {

    @Before
    fun checkoutDir() {
        Runtime.getRuntime().exec("git checkout $assetsAndroidDir").waitFor()
    }

    @Test
    fun testBasicOperation() {
        val gitManager = GitManager(assetsAndroidDir)

        gitManager.deleteGit()
        assertFalse(gitManager.isGitAvailable())

        gitManager.init()
        assertTrue(gitManager.isGitAvailable())

        val uncommittedFiles = gitManager.getUncommittedFiles()
        assertTrue(uncommittedFiles.isNotEmpty())
        assertEquals(0, gitManager.getCurrentBranchCommitSize())
        assertEquals(null, gitManager.getLastCommitHash())

        gitManager.addAllAndCommit("first commit")
        val uncommittedFilesNew = gitManager.getUncommittedFiles()
        assertTrue(uncommittedFilesNew.isEmpty())
        assertEquals(1, gitManager.getCurrentBranchCommitSize())
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
            val commit = gitManager.getLastCommitHash()
            assertEquals(40, commit?.length)
        }
        commitFile.delete()

        gitManager.deleteGit()
    }

    @Test
    fun testDiff() {
        val gitManager = GitManager(assetsAndroidDir)

        repeat(100) { index ->
            val commitFile = File(gitManager.rootDir, "commit_file_$index.txt")
            commitFile.delete()
        }

        gitManager.deleteGit()
        gitManager.init()
        gitManager.addAllAndCommit("first commit")
        val firstHash = gitManager.getLastCommitHash()
        assertNotNull(firstHash)

        var lastHash: String = firstHash
        repeat(100) { index ->
            val commitFile = File(gitManager.rootDir, "commit_file_$index.txt")
            assertTrue(!commitFile.exists())

            commitFile.writeText("$index")
            gitManager.addAllAndCommit("commit $index")

            val newCommitHash = gitManager.getLastCommitHash()
            assertNotNull(newCommitHash)
            val changedFilesInOneCommit = gitManager.getChangedFiles(lastHash, newCommitHash)
            assertEquals(1, changedFilesInOneCommit.size)
            val changedFilesInAllCommit = gitManager.getChangedFiles(firstHash, newCommitHash)
            assertEquals(index + 1, changedFilesInAllCommit.size)
            lastHash = newCommitHash
        }

        repeat(100) { index ->
            val commitFile = File(gitManager.rootDir, "commit_file_$index.txt")
            commitFile.delete()
        }
    }
}