package com.sickworm.intellij.jugg.compiler.compose

import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComposeResourceScannerTest {

    @Test
    fun `scans legacy Compose XML resources`() {
        val root = newDirectory("compose-legacy-scanner")
        File(root, "values/strings.xml").apply {
            parentFile.mkdirs()
            writeText("<resources><string name=\"legacy_title\">Legacy title</string></resources>")
        }
        File(root, "drawable/icon.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val resources = scanner.scanLegacy(root)

        assertEquals("values/strings.xml", resources.getValue(ComposeResourceType.STRING)
            .getValue("legacy_title").single().path.toString())
        assertTrue(resources.getValue(ComposeResourceType.DRAWABLE).containsKey("icon"))
    }

    @Test
    fun `treats absent configured resource root as empty`() {
        val root = Files.createTempDirectory("compose-missing-root").toFile()
        val missing = File(root, "missing-compose-resources")
        val prepared = File(root, "prepared").apply { mkdirs() }

        assertTrue(scanner.scan(missing, prepared, assetRelativePath).isEmpty())
    }

    private val scanner = ComposeResourceScanner()
    private val preparedRoot = File(
        System.getProperty("user.dir"),
        "src/test/assets/compose/1.7.3/prepared/commonMain/composeResources",
    )
    private val assetRelativePath = "composeResources/com.sickworm.jugg.demo.kmp.generated.resources"

    @Test
    fun `scans value offsets and sizes from golden CVR`() {
        val resources = scanner.scan(preparedRoot, preparedRoot, assetRelativePath)
        val items = resources.getValue(ComposeResourceType.STRING).getValue("baseline_title")

        assertEquals(
            ComposeResourceItem(
                ComposeResourceType.STRING,
                emptyList(),
                "baseline_title",
                Paths.get("$assetRelativePath/values/strings.commonMain.cvr"),
                10,
                42,
                "QmFzZWxpbmUgdGl0bGU=".hashCode(),
            ),
            items.single { it.qualifiers.isEmpty() },
        )
        assertEquals(listOf("zh", "rCN"), items.single { it.qualifiers.isNotEmpty() }.qualifiers)
        assertEquals(10, items.single { it.qualifiers.isNotEmpty() }.offset)
        assertEquals(38, items.single { it.qualifiers.isNotEmpty() }.size)
        assertTrue(resources.getValue(ComposeResourceType.STRING_ARRAY).containsKey("baseline_engines"))
        assertTrue(resources.getValue(ComposeResourceType.PLURAL_STRING).containsKey("baseline_turns"))

        val utf8Root = newDirectory("compose-scanner-utf8")
        File(utf8Root, "values/strings.cvr").apply {
            parentFile.mkdirs()
            writeText("version:0\nstring|\u6807\u9898|dmFsdWU=\nstring|after|dmFsdWU=\n")
        }
        val utf8Items = scanner.scan(newDirectory("compose-scanner-files"), utf8Root, assetRelativePath)
        val after = utf8Items.getValue(ComposeResourceType.STRING).getValue("after").single()
        assertEquals("version:0\nstring|\u6807\u9898|dmFsdWU=\n".toByteArray().size.toLong(), after.offset)
    }

    @Test
    fun `scans drawable and font qualifiers in Compose order`() {
        val resources = scanner.scan(preparedRoot, preparedRoot, assetRelativePath)
        val drawables = resources.getValue(ComposeResourceType.DRAWABLE).getValue("baseline_icon")

        assertEquals(
            listOf(emptyList(), listOf("hdpi")),
            drawables.map { it.qualifiers }.sortedBy { it.size },
        )
        assertEquals(
            Paths.get("$assetRelativePath/drawable-hdpi/baseline_icon.png"),
            drawables.single { it.qualifiers == listOf("hdpi") }.path,
        )
        assertTrue(drawables.all { it.offset == -1L && it.size == -1L })

        val font = resources.getValue(ComposeResourceType.FONT).getValue("baseline_font").single()
        assertEquals(emptyList(), font.qualifiers)
        assertEquals(Paths.get("$assetRelativePath/font/baseline_font.ttf"), font.path)
    }

    @Test
    fun `does not create accessor items for files directory`() {
        val resources = scanner.scan(preparedRoot, preparedRoot, assetRelativePath)

        assertTrue(resources.values.flatMap { it.values }.flatten().none { it.name == "baseline_payload" })

        val hiddenRoot = newDirectory("compose-scanner-hidden")
        File(hiddenRoot, "drawable/.secret.png").apply {
            parentFile.mkdirs()
            writeText("secret")
        }
        File(hiddenRoot, ".ignored/value.png").apply {
            parentFile.mkdirs()
            writeText("secret")
        }
        assertTrue(
            scanner.scan(hiddenRoot, newDirectory("compose-scanner-values"), assetRelativePath).isEmpty(),
        )
    }

    @Test
    fun `rejects unknown directory and malformed CVR version`() {
        val unknownRoot = newDirectory("compose-scanner-unknown")
        val unknownFile = File(unknownRoot, "raw/payload.bin").apply {
            parentFile.mkdirs()
            writeText("payload")
        }
        val unknownError = assertFailsWith<IllegalArgumentException> {
            scanner.scan(unknownRoot, newDirectory("compose-scanner-values"), assetRelativePath)
        }
        assertTrue(unknownError.message.orEmpty().contains(unknownFile.parentFile.absolutePath))

        val qualifiedFilesRoot = newDirectory("compose-scanner-qualified-files")
        val qualifiedFiles = File(qualifiedFilesRoot, "files-en/payload.bin").apply {
            parentFile.mkdirs()
            writeText("payload")
        }
        val qualifiedFilesError = assertFailsWith<IllegalArgumentException> {
            scanner.scan(qualifiedFilesRoot, newDirectory("compose-scanner-values"), assetRelativePath)
        }
        assertTrue(qualifiedFilesError.message.orEmpty().contains(qualifiedFiles.parentFile.absolutePath))

        val malformedRoot = newDirectory("compose-scanner-malformed")
        val malformedFile = File(malformedRoot, "values/strings.cvr").apply {
            parentFile.mkdirs()
            writeText("version:1\nstring|title|dmFsdWU=\n")
        }
        val malformedError = assertFailsWith<IllegalArgumentException> {
            scanner.scan(newDirectory("compose-scanner-resources"), malformedRoot, assetRelativePath)
        }
        assertTrue(malformedError.message.orEmpty().contains(malformedFile.absolutePath))

        malformedFile.writeText("version:0\nstring|missing-content\n")
        val malformedRecordError = assertFailsWith<IllegalArgumentException> {
            scanner.scan(newDirectory("compose-scanner-resources"), malformedRoot, assetRelativePath)
        }
        assertTrue(malformedRecordError.message.orEmpty().contains(malformedFile.absolutePath))
    }

    private fun newDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile()
}
