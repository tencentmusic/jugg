package com.sickworm.intellij.jugg.project.runtime

import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class JuggDirectoryMigrationTest {
    @get:org.junit.Rule val isolatedHome = com.sickworm.intellij.jugg.mock.IsolatedHomeRule()
    @Test fun locatorFallsBackOnMalformedOrRelativeContent() {
        val home = java.io.File(System.getProperty("user.home"))
        try {
            val locator = home.resolve(".juggdir")
            for (content in listOf("broken", "juggdir: relative", "juggdir: ", "other: /tmp", "juggdir: /a\njuggdir: /b")) {
                locator.writeText(content)
                assertEquals(home.resolve(".jugg"), JuggGlobalPathManager.configuredRootDir)
            }
            val target = home.resolve("space directory")
            locator.writeText("juggdir: ${target.absolutePath}\n")
            assertEquals(target.canonicalFile, JuggGlobalPathManager.configuredRootDir.canonicalFile)
        } finally { home.deleteRecursively() }
    }

    @Test fun migrationPublishesOnlyAfterCopyAndCleansOldRootOnRestart() {
        val home = Files.createTempDirectory("jugg-migration").toFile()
        try {
            val actualSource = home.resolve("actual-old").apply { mkdirs() }
            val source = home.resolve("old")
            Files.createSymbolicLink(source.toPath(), actualSource.toPath())
            source.resolve("settings.json").writeText("{\"enabled\":true}")
            val external = home.resolve("external").apply { mkdirs() }
            external.resolve("keep").writeText("untouched")
            Files.createSymbolicLink(source.resolve("external-link").toPath(), external.toPath())
            val target = home.resolve("new \$cache dir")
            source.resolve("standalone/bin").mkdirs()
            source.resolve("standalone/bin/jugg-standalone").writeText("#!/bin/sh\nJAVA_CMD=/bin/echo\nexec \"\$JAVA_CMD\" -cp \"${source.absolutePath}/hot_update/jars/*\" Fake\n")
            source.resolve("bin").mkdirs()
            source.resolve("bin/jugg").writeText("#!/bin/sh\n")
            val cli = home.resolve(".local/bin/jugg")
            cli.parentFile.mkdirs()
            Files.createSymbolicLink(cli.toPath(), source.resolve("bin/jugg").toPath())
            val hooks = home.resolve(".claude/settings.json")
            hooks.parentFile.mkdirs()
            source.resolve("skills/hooks").mkdirs()
            source.resolve("skills/hooks/start.py").writeText("print('hook-ok')")
            val hookCommand = "python3 \"${source.absolutePath}/skills/hooks/start.py\""
            hooks.writeText(com.google.gson.Gson().toJson(mapOf("command" to hookCommand)))
            JuggGlobalDirectoryMigration(source, home).prepare(target)
            assertTrue(source.isDirectory)
            assertEquals(source.resolve("settings.json").readText(), target.resolve("settings.json").readText())
            assertEquals(target.canonicalFile, JuggGlobalPathManager.configuredRootDir.canonicalFile)
            val marker = target.resolve(".migration.properties")
            marker.writeText(marker.readText().replace("pid=${ProcessHandle.current().pid()}", "pid=-1"))
            JuggGlobalDirectoryMigration(target, home).cleanupPreviousRoot()
            assertFalse(source.exists())
            assertFalse(Files.isSymbolicLink(source.toPath()))
            assertFalse(actualSource.exists())
            assertEquals("untouched", external.resolve("keep").readText())
            assertTrue(target.resolve("settings.json").isFile)
            assertEquals(target.resolve("bin/jugg").canonicalFile, cli.canonicalFile)
            val repairedHook = com.google.gson.JsonParser.parseString(hooks.readText()).asJsonObject.get("command").asString
            val hookProcess = ProcessBuilder("sh", "-c", repairedHook).start()
            assertEquals("hook-ok", hookProcess.inputStream.bufferedReader().readText().trim())
            assertEquals(0, hookProcess.waitFor())
            val launch = ProcessBuilder("sh", target.resolve("standalone/bin/jugg-standalone").path).start()
            val arguments = launch.inputStream.bufferedReader().readText()
            assertEquals(0, launch.waitFor())
            assertTrue(arguments.contains("-Djugg.root.dir=${target.canonicalPath}"))
            assertTrue(arguments.contains("${target.canonicalPath}/hot_update/jars/*"))
            val third = home.resolve("third")
            JuggGlobalDirectoryMigration(target, home).prepare(third)
            val secondMarker = third.resolve(".migration.properties")
            secondMarker.writeText(secondMarker.readText().replace("pid=${ProcessHandle.current().pid()}", "pid=-1"))
            JuggGlobalDirectoryMigration(third, home).cleanupPreviousRoot()
            assertFalse(target.exists())
            val nextHook = com.google.gson.JsonParser.parseString(hooks.readText()).asJsonObject.get("command").asString
            val nextHookProcess = ProcessBuilder("sh", "-c", nextHook).start()
            assertEquals("hook-ok", nextHookProcess.inputStream.bufferedReader().readText().trim())
            assertEquals(0, nextHookProcess.waitFor())
            val secondLaunch = ProcessBuilder("sh", third.resolve("standalone/bin/jugg-standalone").path).start()
            val secondArguments = secondLaunch.inputStream.bufferedReader().readText()
            assertEquals(0, secondLaunch.waitFor())
            assertTrue(secondArguments.contains("-Djugg.root.dir=${third.canonicalPath}"))
            assertTrue(secondArguments.contains("${third.canonicalPath}/hot_update/jars/*"))
        } finally { home.deleteRecursively() }
    }

    @Test fun migrationBacksUpAnOpenWalDatabase() {
        val home = Files.createTempDirectory("jugg-migration-db").toFile()
        try {
            Class.forName("org.sqlite.JDBC")
            val source = home.resolve("old").apply { mkdirs() }
            val target = home.resolve("new")
            java.sql.DriverManager.getConnection("jdbc:sqlite:${source.resolve("action.db")}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode=WAL")
                    statement.execute("CREATE TABLE records(value TEXT)")
                    statement.execute("INSERT INTO records VALUES ('retained')")
                }
                JuggGlobalDirectoryMigration(source, home).prepare(target)
                java.sql.DriverManager.getConnection("jdbc:sqlite:${target.resolve("action.db")}").use { copy ->
                    copy.createStatement().use { statement ->
                        statement.executeQuery("SELECT value FROM records").use {
                            assertTrue(it.next())
                            assertEquals("retained", it.getString(1))
                        }
                    }
                }
            }
        } finally { home.deleteRecursively() }
    }

    @Test fun invalidDestinationDoesNotPublishLocatorOrRemoveUserFiles() {
        val home = Files.createTempDirectory("jugg-migration-fail").toFile()
        try {
            val source = home.resolve("old").apply { mkdirs() }
            val target = home.resolve("new").apply { mkdirs() }
            target.resolve("user-file").writeText("keep")
            assertFailsWith<IllegalArgumentException> { JuggGlobalDirectoryMigration(source, home).prepare(target) }
            assertFalse(home.resolve(".juggdir").exists())
            assertEquals("keep", target.resolve("user-file").readText())
            assertFailsWith<IllegalArgumentException> { JuggGlobalDirectoryMigration(source, home).prepare(source.resolve("nested")) }
        } finally { home.deleteRecursively() }
    }
}
