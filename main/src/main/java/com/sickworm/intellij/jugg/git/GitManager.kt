package com.sickworm.intellij.jugg.git

import org.gradle.internal.impldep.org.eclipse.jgit.api.Git
import org.gradle.internal.impldep.org.eclipse.jgit.api.errors.NoHeadException
import org.gradle.internal.impldep.org.eclipse.jgit.errors.RepositoryNotFoundException
import org.gradle.internal.impldep.org.eclipse.jgit.revwalk.RevCommit
import org.gradle.internal.impldep.org.eclipse.jgit.revwalk.RevWalk
import org.gradle.internal.impldep.org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.gradle.internal.impldep.org.eclipse.jgit.treewalk.CanonicalTreeParser
import java.io.File
import java.io.IOException


class GitManager(override val rootDir: File): IGitManager {

    override val hasInitGit: Boolean get() {
        if (!File(rootDir, ".git").exists()) {
            return false
        }
        return try {
            // throw RepositoryNotFoundException if structure is incorrect
            Git.open(rootDir)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun init() {
        Git.init().setDirectory(rootDir).call()
    }

    override fun deleteGit() {
        if (!hasInitGit) {
            return
        }
        File(rootDir, ".git").deleteRecursively()
    }

    override fun getUncommittedFiles(): List<File> {
        Git.open(rootDir).use { git ->
            val status = git.status().call()
            val uncommittedFiles = status.untracked.toList() + status.modified.toList() + status.removed.toList()
            return uncommittedFiles.map {
                File(rootDir, it)
            }
        }
    }

    override fun getChangedFiles(oldCommit: String, newCommit: String): List<File> {
        Git.open(rootDir).use { git ->
            val oldCommitTree = getCanonicalTreeParser(git, oldCommit)
            val newCommitTree = getCanonicalTreeParser(git, newCommit)
            val diffResult = git.diff()
                .setShowNameAndStatusOnly(true)
                .setOldTree(oldCommitTree)
                .setNewTree(newCommitTree)
                .call()
            return diffResult.map { File(rootDir, it.newPath) }
        }
    }

    @Throws(IOException::class)
    private fun getCanonicalTreeParser(git: Git, commitHash: String): AbstractTreeIterator {
        val commitId = git.repository.resolve(commitHash)
        RevWalk(git.repository).use { walk ->
            val commit: RevCommit = walk.parseCommit(commitId)
            val treeId = commit.tree.id
            git.repository.newObjectReader().use { reader -> return CanonicalTreeParser(null, reader, treeId) }
        }
    }

    override fun addAllAndCommit(message: String) {
        Git.open(rootDir).use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage(message).call()
        }
    }

    override fun getCurrentBranchCommitSize(): Int {
        Git.open(rootDir).use { git ->
            return try {
                val commits = git.log().call()
                return commits.count()
            } catch (e: NoHeadException) {
                0
            }
        }
    }

    override fun getLastCommitHash(): String? {
        try {
            Git.open(rootDir).use { git ->
                val head = git.repository.resolve("HEAD") ?: return null
                val commit = git.repository.resolve(head.name())
                return commit.name()
            }
        } catch (e: RepositoryNotFoundException) {
            return null
        }
    }
}