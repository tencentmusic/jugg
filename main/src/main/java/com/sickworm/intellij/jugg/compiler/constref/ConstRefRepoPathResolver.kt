package com.sickworm.intellij.jugg.compiler.constref

import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import java.io.File
import java.util.Locale

/**
 * Resolve stable repository identity for const-ref cache keys.
 *
 * Analyses share a `repoKey` derived from the normalized network origin URL, so independent clones and
 * worktrees of one repository reuse them. Without a usable origin, IDE keys fall back to the common Git
 * directory and baseline keys to the location relative to [checkoutRoot]. Current versions are tracked per
 * `worktreeKey`: the checkout path in the IDE, or the relative location in a baseline that moves between runners.
 */
class ConstRefRepoPathResolver(checkoutRoot: File? = null) {
    private val root = checkoutRoot?.canonicalFile
    private val origins = mutableMapOf<File, String>()

    internal fun resolve(path: String): RepoFileIdentity? {
        return resolve(File(path))
    }

    internal fun resolve(file: File): RepoFileIdentity? {
        val absoluteFile = file.absoluteFile
        var currentDir = if (absoluteFile.isDirectory) absoluteFile else absoluteFile.parentFile
        while (currentDir != null) {
            val gitRef = File(currentDir, ".git")
            if (gitRef.exists()) {
                val gitDir = resolveGitDir(currentDir, gitRef) ?: return null
                val worktreeRoot = currentDir.absoluteFile
                val portableKey = root?.let {
                    val relativeRoot = worktreeRoot.canonicalFile.relativeToOrNull(it) ?: worktreeRoot.canonicalFile
                    "baseline:${relativeRoot.invariantSeparatorsPath}"
                }
                val relativePath = runCatching {
                    absoluteFile.relativeTo(worktreeRoot).invariantSeparatorsPath
                }.getOrElse {
                    return null
                }
                val commonGitDir = resolveCommonGitDir(gitDir)
                val origin = origins.getOrPut(commonGitDir) { readOriginKey(commonGitDir).orEmpty() }
                return RepoFileIdentity(
                    repoKey = origin.ifEmpty { portableKey ?: commonGitDir.path },
                    worktreeKey = portableKey ?: worktreeRoot.canonicalPath,
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

    private fun resolveCommonGitDir(gitDir: File): File {
        val commonDirFile = File(gitDir, "commondir")
        if (!commonDirFile.exists()) {
            return gitDir.canonicalFile
        }
        val commonDir = commonDirFile.readText().trim()
        if (commonDir.isEmpty()) {
            return gitDir.canonicalFile
        }
        val commonGitDir = if (File(commonDir).isAbsolute) {
            File(commonDir)
        } else {
            File(gitDir, commonDir)
        }
        return commonGitDir.canonicalFile
    }

    private fun readOriginKey(commonGitDir: File): String? {
        return runCatching {
            val config = FileBasedConfig(File(commonGitDir, "config"), FS.DETECTED)
            config.load()
            val origin = config.getString("remote", "origin", "url") ?: return null
            val uri = URIish(origin)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val defaultPort = when (scheme) {
                null, "ssh", "git+ssh", "ssh+git" -> 22
                "https" -> 443
                "http" -> 80
                "git" -> 9418
                else -> return null
            }
            val path = uri.path?.substringBefore('?')?.substringBefore('#')
                ?.trim('/')?.removeSuffix(".git").orEmpty()
            if (path.isEmpty()) return null
            val port = uri.port.takeIf { it > 0 && it != defaultPort }
            "origin:$host${port?.let { ":$it" }.orEmpty()}/$path"
        }.getOrNull()
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
