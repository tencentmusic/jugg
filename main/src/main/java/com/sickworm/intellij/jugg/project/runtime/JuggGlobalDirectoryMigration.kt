package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.JsonParser
import java.io.File
import com.sickworm.intellij.jugg.ai.skills.JuggHookInstaller
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Properties

/** Copies global storage before publication and removes the previous tree after restart. */
class JuggGlobalDirectoryMigration(
    private val root: File = JuggGlobalPathManager.rootDir,
    private val userHome: File = File(System.getProperty("user.home")),
) {
    private val marker get() = File(root, ".migration.properties")

    fun prepare(destination: File) {
        check(JuggProcessLease.liveProcesses("idea", root).size <= 1) {
            "Close other Android Studio instances before moving Jugg storage."
        }
        check(!marker.exists()) { "Restart Android Studio to finish the pending migration first." }
        val source = root.canonicalFile
        val target = destination.canonicalFile
        require(!target.toPath().startsWith(source.toPath()) && !source.toPath().startsWith(target.toPath())) {
            "The old and new directories must not contain each other."
        }
        require(!target.exists() || target.isDirectory && target.listFiles()?.isEmpty() == true) {
            "Choose an empty destination directory."
        }
        check(JuggGlobalPathManager.ensureWritableDirectory(target)) { "Destination is not writable: $target" }
        try {
            copyTree(source, target)
            verifyLoadManifests(target)
            val data = Properties().apply {
                setProperty("old", source.path)
                setProperty("oldAlias", root.absolutePath)
                setProperty("new", target.path)
                setProperty("pid", ProcessHandle.current().pid().toString())
            }
            target.resolve(".migration.properties").outputStream().use { data.store(it, "Jugg directory migration") }
            JuggGlobalPathManager.writeRootLocation(target)
        } catch (e: Exception) {
            try { removeTree(target) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    private fun verifyLoadManifests(target: File) {
        val storage = target.resolve("hot_update/jars").canonicalFile.toPath()
        listOf("load_manifest.json", "standalone_load_manifest.json").forEach { name ->
            val manifest = target.resolve("hot_update/$name")
            if (!manifest.isFile) return@forEach
            val json = JsonParser.parseString(manifest.readText()).asJsonObject
            json.getAsJsonArray("jarFileNames")?.forEach { entry ->
                val jar = storage.resolve(entry.asString).normalize()
                check(jar.startsWith(storage) && Files.isRegularFile(jar)) { "Missing copied runtime JAR: $jar" }
            }
        }
    }

    private fun copyTree(source: File, target: File) {
        Files.walkFileTree(source.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == source.resolve("locks").toPath()) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(target.toPath().resolve(source.toPath().relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                val file = path.toFile()
                if (file.name.endsWith("-wal") || file.name.endsWith("-shm") || file.name.endsWith("-journal")) return FileVisitResult.CONTINUE
                val output = target.toPath().resolve(source.toPath().relativize(path)).toFile()
                if (attrs.isSymbolicLink) copyLink(file, output, source, target)
                else copyFile(file, output)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun copyLink(file: File, output: File, source: File, target: File) {
        val link = Files.readSymbolicLink(file.toPath())
        val absoluteLink = file.parentFile.toPath().resolve(link).toFile().canonicalFile.toPath()
        val relocated = if (link.isAbsolute && absoluteLink.startsWith(source.toPath())) {
            target.toPath().resolve(source.toPath().relativize(absoluteLink))
        } else link
        Files.createSymbolicLink(output.toPath(), relocated)
    }

    private fun copyFile(file: File, output: File) {
        if (isDatabase(file)) {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { it.executeUpdate("backup to '${output.absolutePath.replace("'", "''")}'") }
            }
            DriverManager.getConnection("jdbc:sqlite:${output.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA quick_check").use { check(it.next() && it.getString(1) == "ok") { "Invalid database copy: $file" } }
                }
            }
        } else {
            Files.copy(file.toPath(), output.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            if (!file.path.contains("${File.separator}log${File.separator}")) {
                check(digest(file).contentEquals(digest(output))) { "File changed while copying: $file" }
            }
        }
    }

    private fun isDatabase(file: File): Boolean = file.inputStream().use {
        val header = ByteArray(16)
        it.read(header) == 16 && header.toString(Charsets.US_ASCII) == "SQLite format 3\u0000"
    }

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var count = input.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest()
    }

    fun cleanupPreviousRoot(): Unit = synchronized(JuggGlobalDirectoryMigration::class.java) {
        if (!marker.isFile) return
        val data = Properties().apply { marker.inputStream().use { load(it) } }
        val old = File(data.getProperty("old")).canonicalFile
        check(File(data.getProperty("new")).canonicalFile == root.canonicalFile)
        check(!old.toPath().startsWith(root.canonicalFile.toPath()) && !root.canonicalFile.toPath().startsWith(old.toPath()))
        if (data.getProperty("pid")?.toLongOrNull() == ProcessHandle.current().pid()) return
        check(JuggProcessLease.liveProcesses("idea", old).isEmpty()) { "Another Android Studio still uses $old" }
        JuggProcessLease.stopStandaloneProcesses(old)
        val alias = File(data.getProperty("oldAlias", old.path))
        listOf(old, alias).distinct().forEach { previous ->
            repairLaunchers(previous)
            JuggHookInstaller.relocateManagedPaths(previous, root, userHome)
        }
        com.sickworm.intellij.jugg.ai.skills.JuggSkillInstaller.relocateWindowsCliPath(root, userHome)
        val rootLink = alias.toPath().takeIf { Files.isSymbolicLink(it) }
        val removeAlias = rootLink != null && rootLink.parent.resolve(Files.readSymbolicLink(rootLink)).toFile().canonicalFile == old
        if (old.exists()) removeTree(old)
        if (removeAlias) Files.deleteIfExists(rootLink)
        check(marker.delete()) { "Failed to clear migration marker: $marker" }
    }

    private fun removeTree(directory: File) {
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exception: java.io.IOException?): FileVisitResult {
                if (exception != null) throw exception
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun launcherPath(path: String, windows: Boolean): String =
        if (windows) path.replace("%", "%%") else path.replace("\\", "\\\\")
            .replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")

    private fun repairLaunchers(old: File) {
        // Only generated launchers are rewritten; user scripts and settings remain untouched.
        listOf(root.resolve("standalone/bin"), root.resolve("bin")).forEach { dir ->
            dir.listFiles().orEmpty().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() < 1024 * 1024 }
                .filter { it.name in setOf("jugg", "jugg.cmd", "jugg.bat", "jugg-standalone", "jugg-standalone.bat") }
                .forEach { file ->
                    val windows = file.extension in setOf("cmd", "bat")
                    val path = launcherPath(root.canonicalPath, windows)
                    var content = file.readText()
                    listOf(launcherPath(old.path, windows), old.path).distinct().forEach { previous ->
                        content = content.replace(previous + File.separator, path + File.separator)
                            .replace(previous + "\"", path + "\"")
                    }
                    if (file.name.startsWith("jugg-standalone") && !content.contains("-Djugg.root.dir=")) {
                        val java = if (windows) "\"%JAVA_EXE%\"" else "exec \"\$JAVA_CMD\""
                        content = content.replace("$java -cp", "$java \"-Djugg.root.dir=$path\" -cp")
                    }
                    file.writeText(content)
                }
        }
        val link = userHome.resolve(".local/bin/jugg")
        if (Files.isSymbolicLink(link.toPath())) {
            val resolved = link.toPath().parent.resolve(Files.readSymbolicLink(link.toPath())).toFile().canonicalFile.toPath()
            if (resolved.startsWith(old.canonicalFile.toPath())) {
                Files.delete(link.toPath())
                Files.createSymbolicLink(link.toPath(), root.canonicalFile.toPath().resolve(old.canonicalFile.toPath().relativize(resolved)))
            }
        }
    }
}
