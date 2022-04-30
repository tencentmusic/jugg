package com.sickworm.intellij.jugg.git

import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitManagerTest {

    @Test
    fun test() {
        val gitManager = GitManager(assetsAndroidDir)

        gitManager.deleteGit()
        assertFalse(gitManager.hasInit())

        gitManager.init()
        assertTrue(gitManager.hasInit())

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
            gitManager.addAllAndCommit("commit $commitCount")
            val uncommittedFile = gitManager.getUncommittedFiles()
            assertTrue(uncommittedFile.isEmpty())
            assertEquals(commitCount, gitManager.getCurrentBranchCommitSize())
            val commit = gitManager.getLastCommitHash()
            assertEquals(40, commit?.length)
        }
        commitFile.delete()

        gitManager.deleteGit()
    }
}