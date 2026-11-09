package com.sickworm.intellij.jugg.project.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Centralizes Jugg-owned global files.
 *
 * Prefers `~/.jugg`. If that directory cannot be created or written, falls back to
 * `${java.io.tmpdir}/jugg-<user>` so compile/runtime caches stay available.
 */
object JuggGlobalPathManager {

    /** Current user's home directory used for Jugg-owned locator and client paths. */
    val currentUserHome: File = File(System.getProperty("user.home"))

    /** Immutable global storage root selected when this Runtime starts. */
    val rootDir = resolveRootDir()

    /** Shared hot-update metadata, manifests, and immutable runtime JAR storage. */
    val hotUpdateDir: File = File(rootDir, "hot_update")

    /** Shared action history database. */
    val actionDbFile: File = File(rootDir, "action.db")

    /** Persisted Runtime settings for the active global root. */
    val settingsFile: File = File(rootDir, "settings.json")

    /** Per-process lease directory under the current user's home. */
    val processLeaseDir: File = File(currentUserHome, ".jugg-instances")

    /** Configured Jugg root from the current user's `.juggdir`, or the preferred default. */
    val configuredRootDir: File
        get() = readConfiguredRootDir(currentUserHome)

    /** Preferred Jugg root under the current user's home directory. */
    val preferredRootDir: File = File(currentUserHome, ".jugg")

    /** Standalone installation directory consumed by the standalone Python scripts. */
    val standaloneDir: File = File(rootDir, "standalone")

    /** Standalone launch lock consumed by the standalone Python scripts. */
    val standaloneLaunchLock: File = File(rootDir, "locks/standalone.launch.lock")

    /** CLI binaries installed into the active Jugg root. */
    val binDir: File = File(rootDir, "bin")

    /** Bundled and prepared skills stored in the active Jugg root. */
    val skillsDir: File = File(rootDir, "skills")

    /** Hook scripts stored with the active Jugg skills. */
    val hooksDir: File = File(skillsDir, "hooks")

    /** cc-switch integration files stored in the active Jugg root. */
    val ccSwitchDir: File = File(rootDir, "cc-switch")

    /** Test-mode marker under the active Jugg root. */
    val testFlagDir: File = File(rootDir, "test_flag")

    /** Shared global-resource lock for the active Jugg root. */
    val globalLockFile: File = File(rootDir, "locks/global.lock")

    private fun readConfiguredRootDir(userHome: File): File {
        return try {
            val text = Files.readString(File(userHome, ".juggdir").toPath()).trim()
            require(!text.contains('\n') && !text.contains('\r'))
            require(text.substringBefore(':').trim() == "juggdir")
            val path = text.substringAfter(':', "").trim()
            require(path.isNotEmpty() && !path.contains('\u0000'))
            File(path).also { require(it.isAbsolute) }
        } catch (_: Exception) {
            File(userHome, ".jugg")
        }
    }

    @JvmStatic
    fun resolveRootDir(): File = System.getProperty("jugg.root.dir")?.let(::File)
        ?: resolveWritableRoot(readConfiguredRootDir(currentUserHome), fallbackRootDir())

    fun writeRootLocation(root: File) {
        require(root.isAbsolute && !root.path.contains('\n') && !root.path.contains('\r'))
        val locator = File(currentUserHome, ".juggdir")
        val temp = Files.createTempFile(currentUserHome.toPath(), ".juggdir-", ".tmp")
        try {
            Files.writeString(temp, "juggdir: ${root.path}\n")
            Files.move(temp, locator.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun fallbackRootDir(
        tmpDir: File = File(System.getProperty("java.io.tmpdir")),
        userName: String = System.getProperty("user.name", "user"),
    ): File {
        val safeName = userName.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "user" }
        return File(tmpDir, "jugg-$safeName")
    }

    private fun resolveWritableRoot(preferred: File, fallback: File): File {
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

    fun resourceFile(resourcePath: String): File {
        val relativePath = resourcePath.trimStart('/', File.separatorChar)
        return File(File(rootDir, "resources"), relativePath)
    }

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

}
