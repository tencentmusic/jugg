package com.sickworm.intellij.jugg.git

import java.io.File

/**
 * A matched full commit SHA and the number of first-parent edges from the target.
 * A target that is itself a candidate has distance zero.
 */
data class FirstParentCommitMatch(val matchedCommit: String, val distance: Int)

/** Repository queries used by Jugg compilation and CI orchestration. */
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
     * get remote.origin.url in .git/config
     */
    val originRemoteUrl: String?

    /**
     * get remote urls in .git/config
     */
    val remoteUrls: List<String>

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
     * Resolve a revision accepted by JGit to its full object ID.
     * @throws IllegalArgumentException if the revision cannot be resolved.
     */
    fun resolveCommit(commit: String): String

    /**
     * Find the nearest candidate by walking only parent zero, starting at [targetCommit].
     * Candidate revisions are resolved before traversal; input order does not affect selection.
     * Returns null when the first-parent chain contains no candidate.
     * @throws IllegalArgumentException if the target or a candidate cannot be resolved.
     */
    fun findNearestFirstParentCommit(targetCommit: String, candidateCommits: Set<String>): FirstParentCommitMatch?

    /**
     * git show -s --format=%H
     * null if [rootDir] does not have any commits yet
     */
    fun getLastCommitHash(): String?

    /**
     * git diff [commitHash] [files]
     * +
     * git untracked files (including files inside untracked directories)
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
