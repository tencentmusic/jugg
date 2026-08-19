package com.sickworm.intellij.jugg.project

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Centralizes Jugg-owned global files.
 *
 * Prefers `~/.jugg`. If that directory cannot be created or written, falls back to
 * `${java.io.tmpdir}/jugg-<user>` so compile/runtime caches stay available.
 */
object JuggGlobalPathManager {

    private data class ResolvedRoot(
        val dir: File,
        val usedFallback: Boolean,
    )

    private val resolvedRoot: ResolvedRoot by lazy {
        val preferred = preferredRootDir()
        val fallback = fallbackRootDir()
        val dir = resolveWritableRoot(preferred, fallback)
        ResolvedRoot(dir, !samePath(dir, preferred))
    }

    val rootDir: File
        get() = resolvedRoot.dir

    val hotUpdateDir: File
        get() = hotUpdateDir(rootDir)

    val deployCacheDbFile: File
        get() = deployCacheDbFile(rootDir)

    val actionDbFile: File
        get() = File(rootDir, "action.db")

    fun preferredRootDir(userHome: File = File(System.getProperty("user.home"))): File {
        return File(userHome, ".jugg")
    }

    fun fallbackRootDir(
        tmpDir: File = File(System.getProperty("java.io.tmpdir")),
        userName: String = System.getProperty("user.name", "user"),
    ): File {
        val safeName = userName.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "user" }
        return File(tmpDir, "jugg-$safeName")
    }

    fun resolveWritableRoot(preferred: File, fallback: File): File {
        if (ensureWritableDirectory(preferred)) {
            return preferred
        }
        if (ensureWritableDirectory(fallback)) {
            return fallback
        }
        throw IllegalStateException(
            "Unable to create a writable Jugg directory at ${preferred.absolutePath} or ${fallback.absolutePath}",
        )
    }

    fun rootDirFor(userHome: File): File {
        val realHome = File(System.getProperty("user.home"))
        return if (samePath(userHome, realHome)) {
            rootDir
        } else {
            preferredRootDir(userHome)
        }
    }

    fun binDir(userHome: File = File(System.getProperty("user.home"))): File {
        return File(rootDirFor(userHome), "bin")
    }

    fun skillsDir(userHome: File = File(System.getProperty("user.home"))): File {
        return File(rootDirFor(userHome), "skills")
    }

    fun hooksDir(userHome: File = File(System.getProperty("user.home"))): File {
        return File(skillsDir(userHome), "hooks")
    }

    fun ccSwitchDir(userHome: File = File(System.getProperty("user.home"))): File {
        return File(rootDirFor(userHome), "cc-switch")
    }

    fun testFlagDir(): File {
        return File(rootDir, "test_flag")
    }

    fun resourceFile(resourcePath: String, rootDir: File = this.rootDir): File {
        val relativePath = resourcePath.trimStart('/', File.separatorChar)
        return File(File(rootDir, "resources"), relativePath)
    }

    fun hotUpdateDir(rootDir: File = this.rootDir): File = File(rootDir, "hot_update")

    fun deployCacheDbFile(rootDir: File = this.rootDir): File = File(rootDir, "deploy_cache/.deploy_cache.db")

    internal fun ensureWritableDirectory(dir: File): Boolean {
        return try {
            Files.createDirectories(dir.toPath())
            if (!dir.isDirectory) {
                return false
            }
            val probe = Files.createTempFile(dir.toPath(), ".jugg_write_probe", null)
            Files.deleteIfExists(probe)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun samePath(left: File, right: File): Boolean {
        return try {
            left.canonicalFile == right.canonicalFile
        } catch (_: IOException) {
            left.absoluteFile.normalize() == right.absoluteFile.normalize()
        }
    }
}
