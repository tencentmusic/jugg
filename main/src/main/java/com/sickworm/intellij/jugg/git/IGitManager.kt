package com.sickworm.intellij.jugg.git

import java.io.File

/**
 * API that used in Jugg
 */
interface IGitManager {

    /**
     * workspace dir to use git
     */
    val rootDir: File

    /**
     * whether [rootDir] has git
     */
    val hasInitGit: Boolean

    /**
     * get git project name in .git/config
     * something like: git config --local remote.origin.url|sed -n 's#.*\/\([^.]*\)\.git#\1#p'
     */
    val name: String?

    /**
     * get git username in .git/config or ~/.gitconfig
     */
    val userName: String?

    /**
     * git status
     */
    fun getUncommittedFiles(): List<File>

    /**
     * git --no-pager diff --name-only [oldCommit] [newCommit]
     */
    fun getChangedFiles(oldCommit: String, newCommit: String): List<File>

    /**
     * git show -s --format=%H
     * null if [rootDir] does not have any commits yet
     */
    fun getLastCommitHash(): String?

    /**
     * git diff [commitHash] [files]
     */
    fun filterChangedFiles(commitHash: String, files: List<File>): List<File>

    /**
     * git show -s --format=%ci
     */
    fun getLastCommitFileContent(commitId: String, file: File, outputFile: File): Boolean
}

/**
 * API that used in Jugg test
 */
interface IGitManagerEx : IGitManager {

    /**
     * git init
     */
    fun init()

    /**
     * rm -rf .git
     */
    fun deleteGit()

    /**
     * git add . && git commit -m "[message]"
     */
    fun addAllAndCommit(message: String)

    /**
     * git rev-list --full-history --all | wc -l
     */
    fun getCurrentBranchCommitSize(): Int

}