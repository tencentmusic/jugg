package com.sickworm.intellij.jugg.project.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Centralizes Jugg-owned global files.
 *
 * Prefers `~/.jugg`. If that directory cannot be created or written, falls back to
 * `${java.io.tmpdir}/jugg-<user>` so compile/runtime caches stay available.
 */
object JuggGlobalPathManager {

    /** Active global storage root, overridable before or during a runtime session. */
    @Volatile
    var rootDir = resolveRootDir()

    val hotUpdateDir: File
        get() = hotUpdateDir(rootDir)

    val deployCacheDbFile: File
        get() = deployCacheDbFile(rootDir)

    val actionDbFile: File
        get() = File(rootDir, "action.db")

    val settingsFile: File
        get() = settingsFile(rootDir)

    val currentUserHome: File
        get() = File(System.getProperty("user.home"))

    fun configuredRootDir(userHome: File): File {
        return try {
            val text = Files.readString(File(userHome, ".juggdir").toPath()).trim()
            require(!text.contains('\n') && !text.contains('\r'))
            require(text.substringBefore(':').trim() == "juggdir")
            val path = text.substringAfter(':', "").trim()
            require(path.isNotEmpty() && !path.contains('\u0000'))
            File(path).also { require(it.isAbsolute) }
        } catch (_: Exception) {
            preferredRootDir(userHome)
        }
    }

    fun resolveRootDir(): File = System.getProperty("jugg.root.dir")?.let(::File)
        ?: resolveWritableRoot(configuredRootDir(currentUserHome), fallbackRootDir())

    fun writeRootLocation(root: File, userHome: File) {
        require(root.isAbsolute && !root.path.contains('\n') && !root.path.contains('\r'))
        val locator = File(userHome, ".juggdir")
        val temp = Files.createTempFile(userHome.toPath(), ".juggdir-", ".tmp")
        try {
            Files.writeString(temp, "juggdir: ${root.path}\n")
            Files.move(temp, locator.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun processLeaseDir(userHome: File): File =
        File(userHome, ".jugg-instances")

    fun standaloneDir(root: File = rootDir): File = File(root, "standalone")

    fun standaloneLaunchLock(root: File = rootDir): File = File(root, "locks/standalone.launch.lock")

    fun preferredRootDir(userHome: File): File {
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
        val realHome = currentUserHome
        return if (samePath(userHome, realHome)) {
            rootDir
        } else {
            configuredRootDir(userHome)
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

    fun settingsFile(rootDir: File = this.rootDir): File = File(rootDir, "settings.json")

    fun globalLockFile(rootDir: File = this.rootDir): File = File(rootDir, "locks/global.lock")

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
