package com.sickworm.intellij.jugg.git

import com.sickworm.intellij.jugg.mock.tempCompileDir
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

open class GitManagerTest {

    protected var gitManager = GitManager(
        Files.createTempDirectory("jugg-git-manager-test").toFile().also {
            File(it, "seed.txt").writeText("seed")
            it.deleteOnExit()
        },
    )

    @Before
    @After
    open fun deleteGit() {
        repeat(100) { index ->
            val commitFile = File(gitManager.rootDir, "commit_file_$index.txt")
            commitFile.delete()
        }
        val commitFile = File(gitManager.rootDir, "commit_file.txt")
        commitFile.delete()
        // cleanup files created by filterChangedFiles tests
        File(gitManager.rootDir, "untracked_1.txt").delete()
        File(gitManager.rootDir, "untracked_2.txt").delete()
        File(gitManager.rootDir, "tracked_file.txt").delete()
        File(gitManager.rootDir, "tracked_mixed.txt").delete()
        File(gitManager.rootDir, "untracked_mixed.txt").delete()
        File(gitManager.rootDir, "untracked_dir").deleteRecursively()
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

        val startCommitSize = gitManager.getCurrentBranchCommitSize()

        val commitFile = File(gitManager.rootDir, "commit_file.txt")
        repeat(100) { index ->
            val commitCount = startCommitSize + 1 + index
            commitFile.writeText("$commitCount")
            val uncommittedFileBeforeCommit = gitManager.getUncommittedFiles()
            assertEquals(1, uncommittedFileBeforeCommit.size)
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

    @Test
    fun testGetLastCommitFileContent() {
        testInit()
        assertTrue(gitManager.hasInitGit)

        val commitFile = File(gitManager.rootDir, "commit_file.txt")
        val random = Random()
        val commitList = mutableMapOf<String, String>()
        gitManager.addAllAndCommit("commit init")
        repeat(10) {
            val text = random.nextInt().toString()
            commitFile.writeText(text)
            gitManager.addAllAndCommit("commit $text")
            commitList[gitManager.getLastCommitHash()!!] = text
        }

        commitList.forEach { (hash, text) ->
            val outputFile = File(tempCompileDir, "output.txt")
            val isSuccess = gitManager.getLastCommitFileContent(hash, commitFile, outputFile)
            assertTrue(isSuccess)
            val content = outputFile.readText()
            assertEquals(text, content)
        }

        commitFile.delete()
        gitManager.deleteGit()
    }

    @Test
    fun testFilterChangedFilesReturnsUntrackedFiles() {
        testInit()
        gitManager.addAllAndCommit("initial commit")
        val commitHash = gitManager.getLastCommitHash()
        assertNotNull(commitHash)

        // create untracked files (not git added)
        val untrackedFile1 = File(gitManager.rootDir, "untracked_1.txt")
        val untrackedFile2 = File(gitManager.rootDir, "untracked_2.txt")
        untrackedFile1.writeText("untracked content 1")
        untrackedFile2.writeText("untracked content 2")

        try {
            val result = gitManager.filterChangedFiles(
                commitHash,
                listOf(untrackedFile1, untrackedFile2)
            )
            assertEquals(
                listOf(untrackedFile1, untrackedFile2).sortedBy { it.name },
                result.sortedBy { it.name }
            )
        } finally {
            untrackedFile1.delete()
            untrackedFile2.delete()
        }
    }

    @Test
    fun testFilterChangedFilesIncludesModifiedTrackedFiles() {
        testInit()
        gitManager.addAllAndCommit("initial commit")
        val commitHash = gitManager.getLastCommitHash()
        assertNotNull(commitHash)

        // create a file and commit it (tracked + committed)
        val trackedFile = File(gitManager.rootDir, "tracked_file.txt")
        trackedFile.writeText("tracked content")
        gitManager.addAllAndCommit("add tracked file")

        // modify the tracked file (modified but still tracked)
        trackedFile.writeText("modified content")

        try {
            val result = gitManager.filterChangedFiles(commitHash, listOf(trackedFile))
            // modified tracked file should be returned (uncommittedChanges includes it)
            assertEquals(listOf(trackedFile), result)
        } finally {
            trackedFile.delete()
        }
    }

    @Test
    fun testFilterChangedFilesMixedTrackedAndUntracked() {
        testInit()
        gitManager.addAllAndCommit("initial commit")
        val commitHash = gitManager.getLastCommitHash()
        assertNotNull(commitHash)

        // tracked file
        val trackedFile = File(gitManager.rootDir, "tracked_mixed.txt")
        trackedFile.writeText("tracked")
        gitManager.addAllAndCommit("add tracked")

        // modify tracked file
        trackedFile.writeText("modified tracked")

        // untracked file
        val untrackedFile = File(gitManager.rootDir, "untracked_mixed.txt")
        untrackedFile.writeText("untracked")

        try {
            val result = gitManager.filterChangedFiles(
                commitHash,
                listOf(trackedFile, untrackedFile)
            )
            // both modified tracked file and untracked file should be returned
            assertEquals(
                listOf(trackedFile, untrackedFile).sortedBy { it.name },
                result.sortedBy { it.name }
            )
        } finally {
            trackedFile.delete()
            untrackedFile.delete()
        }
    }

    @Test
    fun testFilterChangedFilesUntrackedDirectory() {
        testInit()
        gitManager.addAllAndCommit("initial commit")
        val commitHash = gitManager.getLastCommitHash()
        assertNotNull(commitHash)

        // create an untracked directory with files
        val untrackedDir = File(gitManager.rootDir, "untracked_dir")
        untrackedDir.mkdirs()
        val fileInDir1 = File(untrackedDir, "file_in_dir_1.txt")
        val fileInDir2 = File(untrackedDir, "file_in_dir_2.txt")
        fileInDir1.writeText("content 1")
        fileInDir2.writeText("content 2")

        try {
            val result = gitManager.filterChangedFiles(
                commitHash,
                listOf(fileInDir1, fileInDir2)
            )
            assertEquals(
                listOf(fileInDir1, fileInDir2).sortedBy { it.name },
                result.sortedBy { it.name }
            )
        } finally {
            untrackedDir.deleteRecursively()
        }
    }

    @Test
    fun testFilterChangedFilesEmptyList() {
        testInit()
        gitManager.addAllAndCommit("initial commit")
        val commitHash = gitManager.getLastCommitHash()
        assertNotNull(commitHash)

        val result = gitManager.filterChangedFiles(commitHash, emptyList())
        assertEquals(emptyList(), result)
    }
}
