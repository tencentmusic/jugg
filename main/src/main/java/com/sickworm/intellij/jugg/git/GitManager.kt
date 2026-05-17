package com.sickworm.intellij.jugg.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.IndexDiff
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.submodule.SubmoduleWalk
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import java.io.File
import java.io.IOException
import java.nio.charset.Charset


/**
 * GitManager-backed repository service for change detection and commit-content queries.
 * Collaboration: Instantiated directly or through [createGitManagerAndTrySearchParent], and delegates worktree repository creation to [WorktreeRepositoryBuilder].
 * Data Contract: [rootDir] is the working root; APIs return safe fallbacks (null/false/empty) when repository state is unavailable.
 */
class GitManager (
    override val rootDir: File,
): IGitManagerEx {

    /**
     * typically:
     * isWorkTree=true -> .git/worktrees/xxx
     * isWorkTree=false -> .git
     *
     * read every time to read the newest structure
     */
    private val targetGitDir: File get() = getGitDir(rootDir)
    private val isWorkTree: Boolean get() = File(targetGitDir, "commondir").exists()

    private val gitDir: File get() = if (isWorkTree) {
        File(targetGitDir, File(targetGitDir, "commondir").readText().trim())
    } else {
        targetGitDir
    }

    private val repository: Repository? get() {
        // creates every time to read the newest structure
        return try {
            if (isWorkTree) {
                WorktreeRepositoryBuilder()
                    .setGitDir(gitDir)
                    .setWorktreeGitDir(targetGitDir)
                    .setWorkTree(rootDir)
                    .setMustExist(true)
                    .build()
            } else {
                RepositoryBuilder()
                    .setGitDir(gitDir)
                    .setWorkTree(rootDir)
                    .setMustExist(true)
                    .build()
            }
        } catch (e: Exception) {
            // throw RepositoryNotFoundException if structure is incorrect
            null
        }
    }

    private fun getGit(): Git {
        return Git.wrap(repository)
    }

    override val hasInitGit: Boolean get() {
        if (!gitDir.exists()) {
            return false
        }
        return repository != null
    }

    override val name: String? by lazy {
        if (!hasInitGit) {
            return@lazy null
        }
        try {
            val regex = Regex("^\\surl\\s=.*/(.+)$")
            val urlSetting = File(gitDir, "config")
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

    override val userName: String? by lazy {
        try {
            repository?.config?.getString("user", null, "name")
        } catch (e: Exception) {
            "get_git_username_failed_${e.message}"
        }
    }

    override val originRemoteUrl: String? get() {
        return try {
            repository?.config?.getString("remote", "origin", "url")
        } catch (e: Exception) {
            null
        }
    }

    override val remoteUrls: List<String> get() {
        return try {
            val config = repository?.config ?: return emptyList()
            config.getSubsections("remote").flatMap { remoteName ->
                config.getStringList("remote", remoteName, "url").toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun init() {
        Git.init().setDirectory(rootDir)
            .also {
                if (rootDir != gitDir.parentFile) {
                    it.setGitDir(gitDir)
                }
            }
            .call()
    }

    override fun deleteGit() {
        gitDir.deleteRecursively()
    }

    override fun getUncommittedFiles(): List<File> {
        getGit().use { git ->
            val status = diffIndex(git.repository, "HEAD")
            val uncommittedFiles = status.untracked.toList() + status.modified.toList() + status.removed.toList() + status.added.toList()
            return uncommittedFiles.toSet().map {
                File(rootDir, it)
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun diffIndex(repository: Repository, revString: String): Status {
        // refer StatusCommand
        try {
            val diff = IndexDiff(repository, revString, FileTreeIterator(repository))
            // setIgnoreSubmoduleMode(false) will only tell you submodules is changed, no details
            diff.setIgnoreSubmoduleMode(SubmoduleWalk.IgnoreSubmoduleMode.ALL)
            diff.diff()
            return Status(diff)
        } catch (var2: IOException) {
            val e = var2
            throw JGitInternalException(e.message, e)
        }
    }

    override fun getChangedFiles(oldCommit: String, newCommit: String): List<File> {
        getGit().use { git ->
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
        getGit().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage(message).call()
        }
    }

    override fun getCurrentBranchCommitSize(): Int {
        getGit().use { git ->
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
            getGit().use { git ->
                val head = git.repository.resolve("HEAD") ?: return null
                val commit = git.repository.resolve(head.name())
                return commit.name()
            }
        } catch (e: RepositoryNotFoundException) {
            return null
        }
    }

    override fun filterChangedFiles(commitHash: String, files: List<File>): List<File> {
        if (files.isEmpty()) {
            return emptyList()
        }
        val relativePaths = files.map { file ->
            file.relativeToOrSelf(rootDir).path.replace('\\', '/')
        }
        val diff = IndexDiff(repository, commitHash, FileTreeIterator(repository))
        diff.setIgnoreSubmoduleMode(SubmoduleWalk.IgnoreSubmoduleMode.ALL)
        diff.setFilter(PathFilterGroup.createFromStrings(relativePaths))
        diff.diff()
        val status = Status(diff)

        val changedFiles = status.uncommittedChanges.map { File(rootDir, it) }
        val untrackedFiles = status.untracked.map { File(rootDir, it) }
        val untrackedDirs = status.untrackedFolders
        val filesInUntrackedDirs = files.filter { file ->
            val relPath = file.relativeToOrSelf(rootDir).path.replace('\\', '/')
            untrackedDirs.any { dir ->
                relPath.startsWith("$dir/") || relPath == dir
            }
        }
        return (changedFiles + untrackedFiles + filesInUntrackedDirs).distinct()
    }

    override fun getLastCommitFileContent(commitId: String, file: File, outputFile: File): Boolean {
        try {
            val filePath = file.relativeToOrSelf(rootDir).path
            getGit().use { git ->
                val lastCommitHash = git.repository.resolve(commitId) ?: return false
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
                        outputFile.parentFile.mkdirs()
                        outputFile.delete()
                        outputFile.createNewFile()
                        outputFile.outputStream().use { output ->
                            loader.copyTo(output)
                        }
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            return false
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

        private fun getGitDir(dir: File): File {
            var gitDir = File(dir, ".git")
            if (gitDir.isFile) {
                // submodule or subtree
                val content = gitDir.readText()
                if (content.startsWith("gitdir:")) {
                    val path = content.substringAfter("gitdir:").trim()
                    if (path.isNotEmpty()) {
                        gitDir = if (File(path).isAbsolute) {
                            File(path)
                        } else {
                            File(dir, path)
                        }
                    }
                }
            }
            return gitDir
        }
    }
}
