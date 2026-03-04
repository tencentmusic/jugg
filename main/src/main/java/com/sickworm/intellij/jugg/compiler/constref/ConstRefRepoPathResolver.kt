package com.sickworm.intellij.jugg.compiler.constref

import java.io.File

/**
 * Resolve stable repository identity for const-ref cache keys.
 *
 * It normalizes Git worktree layout by reading `.git` + `commondir`, so files from different
 * worktrees of one repository share the same `repoKey`.
 */
internal object ConstRefRepoPathResolver {

    fun resolve(path: String): RepoFileIdentity? {
        return resolve(File(path))
    }

    fun resolve(file: File): RepoFileIdentity? {
        val absoluteFile = file.absoluteFile
        var currentDir = if (absoluteFile.isDirectory) absoluteFile else absoluteFile.parentFile
        while (currentDir != null) {
            val gitRef = File(currentDir, ".git")
            if (gitRef.exists()) {
                val gitDir = resolveGitDir(currentDir, gitRef) ?: return null
                val repoKey = resolveRepoKey(gitDir)
                val worktreeRoot = currentDir.absoluteFile
                val relativePath = runCatching {
                    absoluteFile.relativeTo(worktreeRoot).invariantSeparatorsPath
                }.getOrElse {
                    return null
                }
                return RepoFileIdentity(
                    repoKey = repoKey,
                    worktreeKey = worktreeRoot.canonicalPath,
                    relativePath = relativePath,
                    worktreeRoot = worktreeRoot,
                )
            }
            currentDir = currentDir.parentFile
        }
        return null
    }

    private fun resolveGitDir(worktreeRoot: File, gitRef: File): File? {
        return if (gitRef.isDirectory) {
            gitRef.canonicalFile
        } else if (gitRef.isFile) {
            val gitPath = gitRef.readText()
                .lineSequence()
                .firstOrNull()
                ?.substringAfter("gitdir:", "")
                ?.trim()
                .orEmpty()
            if (gitPath.isEmpty()) {
                null
            } else {
                val resolved = if (File(gitPath).isAbsolute) File(gitPath) else File(worktreeRoot, gitPath)
                resolved.canonicalFile
            }
        } else {
            null
        }
    }

    private fun resolveRepoKey(gitDir: File): String {
        val commonDirFile = File(gitDir, "commondir")
        if (!commonDirFile.exists()) {
            return gitDir.canonicalPath
        }
        val commonDir = commonDirFile.readText().trim()
        if (commonDir.isEmpty()) {
            return gitDir.canonicalPath
        }
        val commonGitDir = if (File(commonDir).isAbsolute) {
            File(commonDir)
        } else {
            File(gitDir, commonDir)
        }
        return commonGitDir.canonicalPath
    }
}

internal data class RepoFileIdentity(
    val repoKey: String,
    val worktreeKey: String,
    val relativePath: String,
    val worktreeRoot: File,
) {
    fun absolutePathInWorktree(): String {
        return if (relativePath.isBlank()) {
            worktreeRoot.toStdPath()
        } else {
            File(worktreeRoot, relativePath).toStdPath()
        }
    }
}
