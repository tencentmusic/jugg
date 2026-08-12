package com.sickworm.intellij.jugg.git

import org.junit.After
import org.junit.Before
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitManagerWorktreeTest : GitManagerTest() {

    private val originGitManager = gitManager
    private val applicationWorktreeDir = File(
        originGitManager.rootDir.parentFile,
        "${originGitManager.rootDir.name}_worktree",
    )

    init {
        gitManager = GitManager(applicationWorktreeDir)
    }

    override fun testInit() {
        applicationWorktreeDir.deleteRecursively()
        originGitManager.init()
        originGitManager.addAllAndCommit("first commit")

        // init gitManager with worktree by cmd
        val cmd = "git worktree add -b my_worktree $applicationWorktreeDir"
        println(cmd)
        val process = ProcessBuilder()
            .directory(originGitManager.rootDir)
            .command(cmd.split(" "))
            .start()
        println(process.inputStream.bufferedReader().readText())
        println(process.errorStream.bufferedReader().readText())
        val result = process.waitFor()
        assertEquals(0, result)
        assertTrue(applicationWorktreeDir.isDirectory)

        // make some difference with origin branch
        assertTrue(gitManager.hasInitGit)
        val newFile = File(applicationWorktreeDir, "worktree_diff.txt")
        val newFile2 = File(applicationWorktreeDir, "commit_file.txt")
        val newFile3 = File(applicationWorktreeDir, "commit_file_1.txt")
        newFile.writeText("worktree diff")
        newFile2.writeText("worktree diff2")
        newFile3.writeText("worktree diff3")
        gitManager.addAllAndCommit("second commit")
    }

    @Before
    @After
    override fun deleteGit() {
        super.deleteGit()
        originGitManager.deleteGit()
    }
}
