package com.sickworm.intellij.jugg.diagnostics

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class InvestigationSnapshotBuilderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val originalLocale = Locale.getDefault()

    @Before
    fun useChineseLocale() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun build(
        sources: List<InvestigationSnapshotBuilder.Source>,
        description: String = "App crashes after changing Foo",
    ): InvestigationSnapshotBuilder.Snapshot {
        val project = temporaryFolder.newFolder()
        return InvestigationSnapshotBuilder(temporaryFolder.newFolder(), project,
            Logger.getInstance("InvestigationSnapshotBuilderTest")).build(
            problemType = "Runtime crash", description = description,
            environment = mapOf("pluginVersion" to "3.6"), sources = sources,
            evidenceFailures = mapOf("device:offline" to "Device is offline"), taskOutput = "FATAL EXCEPTION",
        )
    }

    @Test
    fun `snapshot keeps independent evidence after source changes and deletion`() {
        val source = temporaryFolder.newFile("compile.log").apply { writeText("original failure") }
        val snapshot = build(listOf(InvestigationSnapshotBuilder.Source(source, "logs")))
        val manifest = JsonParser.parseString(File(snapshot.directory, "manifest.json").readText()).asJsonObject
        val entry = manifest.getAsJsonArray("files").single().asJsonObject
        val saved = File(snapshot.directory, entry["path"].asString)

        source.writeText("next run")
        source.delete()

        assertEquals("original failure", saved.readText())
        assertEquals(64, entry["sha256"].asString.length)
        assertTrue(snapshot.prompt.contains(snapshot.directory.absolutePath))
        assertEquals(snapshot.prompt, File(snapshot.directory, "prompt.txt").readText())
    }

    @Test
    fun `same deployed path in different APKs keeps both artifacts and ownership`() {
        val first = temporaryFolder.newFolder("first").resolve("classes.dex").apply { writeText("first dex") }
        val second = temporaryFolder.newFolder("second").resolve("classes.dex").apply { writeText("second dex") }
        val snapshot = build(listOf(
            InvestigationSnapshotBuilder.Source(first, "deployed", listOf("app.apk"), "classes.dex"),
            InvestigationSnapshotBuilder.Source(second, "deployed", listOf("test.apk"), "classes.dex"),
        ))
        val entries = JsonParser.parseString(File(snapshot.directory, "manifest.json").readText())
            .asJsonObject.getAsJsonArray("files").map { it.asJsonObject }

        assertEquals(setOf("first dex", "second dex"), entries.map { File(snapshot.directory, it["path"].asString).readText() }.toSet())
        assertEquals(setOf("app.apk", "test.apk"), entries.flatMap { it.getAsJsonArray("targetApkPaths").map { path -> path.asString } }.toSet())
    }

    @Test
    fun `missing evidence is recorded while usable evidence survives`() {
        val source = temporaryFolder.newFile("logcat.log").apply { writeText("crash stack") }
        val missing = File(temporaryFolder.root, "missing.apk")
        val snapshot = build(listOf(
            InvestigationSnapshotBuilder.Source(source, "logs"),
            InvestigationSnapshotBuilder.Source(missing, "apks"),
        ))
        val manifest = JsonParser.parseString(File(snapshot.directory, "manifest.json").readText()).asJsonObject

        assertEquals(1, manifest.getAsJsonArray("files").size())
        assertEquals(2, manifest.getAsJsonObject("failures").size())
        assertEquals(2, snapshot.missingCount)
        assertTrue(manifest.getAsJsonObject("failures").keySet().any { it.endsWith("missing.apk") })
        assertEquals("Device is offline", manifest.getAsJsonObject("failures")["device:offline"].asString)
    }

    @Test
    fun `compact handoff keeps environment and collection failures accessible through the manifest`() {
        val snapshot = build(emptyList())
        val manifestFile = File(snapshot.directory, "manifest.json")
        val manifest = JsonParser.parseString(manifestFile.readText()).asJsonObject

        assertTrue(snapshot.prompt.contains(manifestFile.absolutePath))
        assertEquals("3.6", manifest.getAsJsonObject("environment")["pluginVersion"].asString)
        assertEquals("Device is offline", manifest.getAsJsonObject("failures")["device:offline"].asString)
        assertFalse(snapshot.prompt.contains("device:offline"))
        assertFalse(snapshot.prompt.contains("```json"))
    }

    @Test
    fun `prompt hands off incident context to the shared investigation guide`() {
        val snapshot = build(emptyList())
        val manifest = JsonParser.parseString(File(snapshot.directory, "manifest.json").readText()).asJsonObject

        assertTrue(snapshot.prompt.contains("https://raw.githubusercontent.com/tencentmusic/jugg/main/" +
                ".agents/skills/issue-handler/references/local-agent-investigation-guide.md"))
        assertTrue(snapshot.prompt.contains(snapshot.directory.absolutePath))
        assertTrue(snapshot.prompt.contains(manifest["projectDir"].asString))
        assertTrue(snapshot.prompt.contains(manifest["createdAt"].asString))
        assertTrue(snapshot.prompt.contains(manifest["problemType"].asString))
        assertTrue(snapshot.prompt.contains(manifest["description"].asString))
    }

    @Test
    fun `all Chinese system regions produce a Chinese investigation handoff`() {
        for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.forLanguageTag("zh-HK"))) {
            Locale.setDefault(locale)
            val snapshot = build(emptyList())

            assertTrue(snapshot.prompt.startsWith("# Jugg 问题调查\n"))
            assertTrue(snapshot.prompt.contains("请使用中文"))
            assertTrue(snapshot.prompt.contains(File(snapshot.directory, "manifest.json").absolutePath))
            assertEquals(snapshot.prompt, File(snapshot.directory, "prompt.txt").readText())
        }
    }

    @Test
    fun `other system languages produce an English investigation handoff`() {
        for (locale in listOf(Locale.US, Locale.FRANCE, Locale.JAPAN, Locale.ROOT)) {
            Locale.setDefault(locale)
            val snapshot = build(emptyList())
            val prompt = snapshot.prompt

            assertTrue(prompt.startsWith("# Jugg investigation\n"))
            assertTrue(prompt.contains("Respond in English"))
            assertTrue(prompt.contains("local-agent-investigation-guide.md"))
            assertTrue(prompt.contains(File(snapshot.directory, "manifest.json").absolutePath))
            assertEquals(prompt, File(snapshot.directory, "prompt.txt").readText())
        }
    }

    @Test
    fun `markdown header stays aligned and multiline user text is inserted only once`() {
        val description = "用户描述\n{{projectDir}} and {{environment}} stay literal"
        val prompt = build(emptyList(), description).prompt

        assertTrue(prompt.startsWith("# Jugg 问题调查\n"))
        assertTrue(prompt.contains("\n请在当前 Android 工程中调查"))
        assertTrue(prompt.contains("\n- 问题类型：Runtime crash"))
        assertTrue(prompt.contains("\n## 问题描述\n\n$description\n"))
        assertEquals(1, Regex(Regex.escape(description)).findAll(prompt).count())
    }
}
