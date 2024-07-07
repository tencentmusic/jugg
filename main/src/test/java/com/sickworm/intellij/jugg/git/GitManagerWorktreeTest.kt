package com.sickworm.intellij.jugg.git

import com.sickworm.intellij.jugg.mock.buildDir
import org.junit.After
import org.junit.Before
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitManagerWorktreeTest : GitManagerTest() {

    private val originGitManager = gitManager
    private val applicationWorktreeDir = File(buildDir, "application_worktree")

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
    }

    @Before
    @After
    override fun deleteGit() {
        super.deleteGit()
        originGitManager.deleteGit()
    }
}