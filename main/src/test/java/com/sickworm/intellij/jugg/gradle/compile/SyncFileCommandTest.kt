package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.logger.JuggLogger
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncFileCommandTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sourceSync_shouldPreserveFilesFiltersDeletionAndIncrementalUpdates() {
        assumeFalse(isWindows)
        val source = temporaryFolder.newFolder("Source Project")
        val destination = temporaryFolder.newFolder("Remote Project")
        val unchanged = writeFile(source, "src/plain.txt", "plain source")
        writeFile(source, ".gradle/jugg/config.txt", "gradle config")
        writeFile(source, ".gradle/cache/ignored.txt", "cache")
        writeFile(source, "build/jugg/config/config.txt", "jugg config")
        writeFile(source, "build/ignored.txt", "build output")
        writeFile(source, "local.properties", "local SDK")
        writeFile(destination, "obsolete.txt", "obsolete")

        assertEquals(0, sync(source, destination, excludes = listOf("local.properties")).first)
        assertEquals(unchanged.readText(), File(destination, "src/plain.txt").readText())
        assertTrue(File(destination, ".gradle/jugg/config.txt").isFile)
        assertTrue(File(destination, "build/jugg/config/config.txt").isFile)
        assertFalse(File(destination, ".gradle/cache").exists())
        assertFalse(File(destination, "build/ignored.txt").exists())
        assertFalse(File(destination, "local.properties").exists())
        assertFalse(File(destination, "obsolete.txt").exists())

        val timestamp = File(destination, "src/plain.txt").lastModified()
        assertEquals(0, sync(source, destination, excludes = listOf("local.properties")).first)
        assertEquals(timestamp, File(destination, "src/plain.txt").lastModified())
        unchanged.writeText("updated source with different length")
        assertEquals(0, sync(source, destination, excludes = listOf("local.properties")).first)
        assertEquals(unchanged.readText(), File(destination, "src/plain.txt").readText())
    }

    @Test
    fun sourceSync_shouldPreserveRelativeLinksInsideTransferTree() {
        assumeFalse(isWindows)
        val source = temporaryFolder.newFolder("Source Project")
        val destination = temporaryFolder.newFolder("Remote Project")
        writeFile(source, "shared/material.txt", "internal material")
        val link = File(source, "nested/assets").also { it.parentFile.mkdirs() }.toPath()
        Files.createSymbolicLink(link, File("../shared").toPath())

        assertEquals(0, sync(source, destination).first)
        val remoteLink = File(destination, "nested/assets").toPath()
        assertTrue(Files.isSymbolicLink(remoteLink))
        assertEquals(Files.readSymbolicLink(link), Files.readSymbolicLink(remoteLink))
        assertEquals("internal material", File(destination, "nested/assets/material.txt").readText())
    }

    @Test
    fun sourceSync_shouldCopyExternalLinkTargetsOnUnix() {
        assumeFalse(isWindows)
        val source = temporaryFolder.newFolder("Source Project")
        val destination = temporaryFolder.newFolder("Remote Project")
        val shared = writeFile(temporaryFolder.root, "shared file.txt", "external material")
        val links = mapOf(
            "relative.txt" to File("../shared file.txt").toPath(),
            "absolute.txt" to shared.toPath(),
        )
        links.forEach { (name, target) -> Files.createSymbolicLink(File(source, name).toPath(), target) }

        assertEquals(0, sync(source, destination).first)
        links.forEach { (name, target) ->
            assertFalse(Files.isSymbolicLink(File(destination, name).toPath()))
            assertEquals("external material", File(destination, name).readText())
            assertEquals(target, Files.readSymbolicLink(File(source, name).toPath()))
        }
        assertEquals("external material", shared.readText())
    }

    @Test
    fun sourceSync_shouldJudgeRelativeLinksAgainstTransferRootInsteadOfProjectRoot() {
        assumeFalse(isWindows)
        val source = temporaryFolder.newFolder("local")
        val destination = temporaryFolder.newFolder("remote")
        writeFile(source, ".workspace/others/assets/player/material.txt", "shared material")
        val link = File(source, "android/assets").also { it.parentFile.mkdirs() }.toPath()
        Files.createSymbolicLink(link, File("../.workspace/others/assets").toPath())

        assertEquals(0, sync(source, destination, projectRelativePath = "android").first)
        assertTrue(Files.isSymbolicLink(File(destination, "android/assets").toPath()))
        assertEquals("shared material", File(destination, "android/assets/player/material.txt").readText())
    }

    private fun writeFile(root: File, path: String, content: String): File {
        return File(root, path).also {
            it.parentFile.mkdirs()
            it.writeText(content)
        }
    }

    private fun sync(
        source: File,
        destination: File,
        projectRelativePath: String = "",
        excludes: List<String> = emptyList(),
    ): Pair<Int, String> {
        assumeFalse(isWindows)
        RsyncCompatibleHelper.init(JuggLogger.getGlobalLogger("SyncFileCommandTest"))
        // Local endpoints exercise the production upload command without an SSH server.
        val command = RsyncSyncFileCommand(null, 22, emptyList(),
            "\"${source.absolutePath}/\"", "\"${destination.absolutePath}\"", projectRelativePath, excludes)
        val process = ProcessBuilder("/bin/bash", "-c", command.getCommand(false, false))
            .redirectErrorStream(true).also { it.environment()["LC_ALL"] = "C" }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        val result = output.lineSequence().mapNotNull { command.hasFinishWithResult(it) }.single()
        return result to output
    }

    @Test
    fun rsyncArguments_shouldCopyUnsafeLinksOnlyOnUnix() {
        val arguments = SyncFileCommand.getRsyncArguments("demo", emptyList())

        assertEquals(!isWindows, "--copy-unsafe-links" in arguments)
    }

    @Test
    fun rsyncArguments_shouldKeepFixedBuildAndDotGradleExcludes() {
        val arguments = SyncFileCommand.getRsyncArguments("demo", emptyList())

        assertTrue(arguments.contains("--include='/demo/.gradle/'"))
        assertTrue(arguments.contains("--include='/demo/.gradle/jugg/'"))
        assertTrue(arguments.contains("--include='/demo/.gradle/jugg/**'"))
        assertTrue(arguments.contains("--include='/demo/build/jugg/config/'"))
        assertTrue(arguments.contains("--include='/demo/build/jugg/config/**'"))
        assertTrue(arguments.contains("--exclude='/demo/.gradle/**'"))
        assertTrue(arguments.contains("--exclude='/demo/build/**'"))
        assertFalse(arguments.contains("--include='/demo/.gradle' "))
        assertFalse(arguments.contains("--include='/demo/.gradle/jugg' "))
        assertFalse(arguments.contains("--exclude='.gradle/'"))
        assertTrue(arguments.contains("--exclude='build/'"))
        assertFalse(arguments.contains("--exclude='local.properties'"))
        assertFalse(arguments.contains("--exclude='.idea/'"))
        assertFalse(arguments.contains("--exclude='*.iml'"))
        assertFalse(arguments.contains("--exclude='.git/objects/'"))
        assertFalse(arguments.contains("--exclude='.git/modules/'"))
        assertFalse(arguments.contains("--exclude='.cxx/'"))
    }

    @Test
    fun rsyncArguments_shouldKeepExcludePatternsRaw() {
        val arguments = SyncFileCommand.getRsyncArguments(
            "demo",
            listOf(".git/", "/.git/", "app/src/debug/mock/**"),
        )

        assertTrue(arguments.contains("--exclude='.git/'"))
        assertTrue(arguments.contains("--exclude='/.git/'"))
        assertTrue(arguments.contains("--exclude='app/src/debug/mock/**'"))
        assertFalse(arguments.contains("--exclude='/demo/.git/'"))
        assertFalse(arguments.contains("--exclude='.git/objects/'"))
        assertFalse(arguments.contains("--exclude='.git/modules/'"))
    }

    @Test
    fun rsyncArguments_shouldHandleProjectRootTransferWithoutDoubleSlash() {
        val arguments = SyncFileCommand.getRsyncArguments(
            "",
            listOf("app/src/debug/mock/**"),
        )

        assertTrue(arguments.contains("--exclude='app/src/debug/mock/**'"))
    }

    @Test
    fun rsyncArguments_shouldKeepOriginalFixedFilterOrderBeforeConfigurableExcludes() {
        val arguments = SyncFileCommand.getRsyncArguments(
            "demo",
            listOf("local.properties", ".git/objects/"),
        )

        assertTrue(arguments.indexOf("--include='/demo/.gradle/jugg/**'") < arguments.indexOf("--exclude='/demo/.gradle/**'"))
        assertTrue(arguments.indexOf("--include='/demo/build/jugg/config/**'") < arguments.indexOf("--exclude='/demo/build/**'"))
        assertTrue(arguments.indexOf("--exclude='/demo/build/**'") < arguments.indexOf("--exclude='build/'"))
        assertTrue(arguments.indexOf("--exclude='build/'") < arguments.indexOf("--exclude='local.properties'"))
    }

    @Test
    fun rsyncArguments_defaultPatternsShouldKeepLegacyFilterSequence() {
        val arguments = SyncFileCommand.getRsyncArguments(
            "demo",
            listOf("local.properties", ".idea/", "*.iml", ".git/objects/", ".git/modules/", ".cxx/"),
        )

        val filters = Regex("--(?:include|exclude)='[^']*'")
            .findAll(arguments)
            .map { it.value }
            .toList()
        assertEquals(listOf(
            "--include='/demo/.gradle/'",
            "--include='/demo/.gradle/jugg/'",
            "--include='/demo/.gradle/jugg/**'",
            "--exclude='/demo/.gradle/**'",
            "--include='/demo/build/'",
            "--include='/demo/build/jugg/'",
            "--include='/demo/build/jugg/config/'",
            "--include='/demo/build/jugg/config/**'",
            "--include='/demo/build/jugg/database/'",
            "--include='/demo/build/jugg/database/project_infos.db/'",
            "--include='/demo/build/jugg/database/project_infos.db/project_infos.json'",
            "--exclude='/demo/build/**'",
            "--exclude='build/'",
            "--exclude='local.properties'",
            "--exclude='.idea/'",
            "--exclude='*.iml'",
            "--exclude='.git/objects/'",
            "--exclude='.git/modules/'",
            "--exclude='.cxx/'",
        ), filters)
    }
}
