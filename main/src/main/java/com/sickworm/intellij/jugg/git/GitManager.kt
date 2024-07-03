package com.sickworm.intellij.jugg.git

import org.gradle.internal.impldep.org.eclipse.jgit.api.Git
import org.gradle.internal.impldep.org.eclipse.jgit.api.errors.NoHeadException
import org.gradle.internal.impldep.org.eclipse.jgit.errors.RepositoryNotFoundException
import org.gradle.internal.impldep.org.eclipse.jgit.lib.ObjectId
import org.gradle.internal.impldep.org.eclipse.jgit.lib.ObjectLoader
import org.gradle.internal.impldep.org.eclipse.jgit.revwalk.RevCommit
import org.gradle.internal.impldep.org.eclipse.jgit.revwalk.RevWalk
import org.gradle.internal.impldep.org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.gradle.internal.impldep.org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.gradle.internal.impldep.org.eclipse.jgit.treewalk.TreeWalk
import org.gradle.internal.impldep.org.eclipse.jgit.treewalk.filter.PathFilter
import org.gradle.internal.impldep.org.eclipse.jgit.treewalk.filter.PathFilterGroup
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets


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

    override val name: String? by lazy {
        if (!hasInitGit) {
            return@lazy null
        }
        try {
            val regex = Regex("^\\surl\\s=.*/(.+)$")
            val urlSetting = File(rootDir, ".git/config")
                .readLines(Charset.defaultCharset())
                .find {
                    it.matches(regex)
                } ?: return@lazy null
            var name = regex.find(urlSetting)?.groups?.get(1)?.value ?: return@lazy null
            if (name.endsWith('/')) {
                name = name.substring(0, name.length - 1)
            }
            if (name.endsWith(".git")) {
                name = name.substring(0, name.length - 4)
            }
            return@lazy name
        } catch (e: Exception) {
            return@lazy null
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
            val uncommittedFiles = status.untracked.toList() + status.modified.toList() + status.removed.toList() + status.added.toList()
            return uncommittedFiles.toSet().map {
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

    override fun filterChangedFiles(commitHash: String, files: List<File>): List<File> {
        Git.open(rootDir).use { git ->
            val oldCommitTree = getCanonicalTreeParser(git, commitHash)
            val diffResult = git.diff()
                .setPathFilter(PathFilterGroup.createFromStrings(files.map { file ->
                    file.relativeToOrSelf(rootDir).path
                }))
                .setShowNameAndStatusOnly(true)
                .setOldTree(oldCommitTree)
                .call()
            return diffResult.map { File(rootDir, it.newPath) }
        }
    }

    override fun getLastCommitFileContent(commitId: String, file: File): String? {
        try {
            val filePath = file.relativeToOrSelf(rootDir).path
            Git.open(rootDir).use { git ->
                val lastCommitHash = git.repository.resolve(commitId) ?: return null
                RevWalk(git.repository).use { revWalk ->
                    val commit: RevCommit = revWalk.parseCommit(lastCommitHash)
                    // and using commits tree find the path
                    val tree = commit.tree
                    TreeWalk(git.repository).use { treeWalk ->
                        treeWalk.addTree(tree)
                        treeWalk.isRecursive = true
                        treeWalk.filter = PathFilter.create(filePath)
                        check(treeWalk.next()) { "Did not find expected file 'README.md'" }

                        val objectId: ObjectId = treeWalk.getObjectId(0)
                        val loader: ObjectLoader = git.repository.open(objectId)

                        // and then one can the loader to read the file
                        val content = String(loader.bytes, StandardCharsets.UTF_8)
                        revWalk.dispose()
                        return content
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    companion object {

        fun createGitManagerAndTrySearchParent(dir: File): IGitManager {
            var rootDir: File? = dir
            while (rootDir != null) {
                val gitManager = GitManager(rootDir)
                if (gitManager.hasInitGit) {
                    return gitManager
                }
                rootDir = rootDir.parentFile
            }
            return GitManager(dir)
        }
    }
}