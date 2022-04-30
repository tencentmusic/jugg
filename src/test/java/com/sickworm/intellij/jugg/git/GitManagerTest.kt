package com.sickworm.intellij.jugg.git

import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import org.junit.Test
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
        val commit1 = gitManager.getLastCommitHash()
        assertEquals(40, commit1?.length)

        gitManager.deleteGit()
    }
}