package com.sickworm.intellij.jugg.git

import org.gradle.internal.impldep.org.eclipse.jgit.api.Git
import org.gradle.internal.impldep.org.eclipse.jgit.api.errors.NoHeadException
import java.io.File

class GitManager(override val rootDir: File): IGitManager {

    override fun hasInit(): Boolean {
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
        if (!hasInit()) {
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
        Git.open(rootDir).use { git ->
            val head = git.repository.resolve("HEAD") ?: return null
            val commit = git.repository.resolve(head.name())
            return commit.name()
        }
    }
}