package com.sickworm.intellij.jugg.compiler.compose

import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComposeValueResourceConverterTest {

    private val converter = ComposeValueResourceConverter()

    @Test
    fun `converts string array and plurals to Compose 1_7_3 golden bytes`() {
        val projectDir = File(System.getProperty("user.dir")).parentFile
        val sourceDir = File(projectDir, "android_demo_project/kmpCompose/src/commonMain/composeResourcesExtended/values")
        val goldenDir = File(
            System.getProperty("user.dir"),
            "src/test/assets/compose/1.7.3/prepared/commonMain/composeResources/values",
        )

        listOf("arrays", "plurals").forEach { name ->
            val outputFile = newOutputFile("$name.cvr")
            converter.convert(File(sourceDir, "$name.xml"), outputFile)

            assertContentEquals(File(goldenDir, "$name.commonMain.cvr").readBytes(), outputFile.readBytes())
        }
    }

    @Test
    fun `sorts records before calculating UTF8 offsets`() {
        val outputFile = convert(
            """
            <resources>
                <string name="z_key">last</string>
                <string name="a_key">first 中文</string>
                <string-array name="middle"><item>value</item></string-array>
            </resources>
            """.trimIndent(),
        )

        assertEquals(
            listOf("string-array|middle", "string|a_key", "string|z_key"),
            outputFile.readLines().drop(1).map { it.substringBeforeLast('|') },
        )
        assertEquals("first 中文", decodeContent(outputFile, "string|a_key"))
    }

    @Test
    fun `preserves escaped quotes apostrophes newlines and unicode`() {
        val outputFile = convert(
            """
            <resources>
                <string name="escaped">He said \"hi\" and it\'s\n\u4F60好</string>
            </resources>
            """.trimIndent(),
        )

        assertEquals("He said \\\"hi\\\" and it\\'s\n你好", decodeContent(outputFile, "string|escaped"))
    }

    @Test
    fun `rejects duplicate keys across one XML file`() {
        val inputFile = newInputFile(
            """
            <resources>
                <string name="duplicate">one</string>
                <string name="duplicate">two</string>
            </resources>
            """.trimIndent(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            converter.convert(inputFile, newOutputFile("duplicate.cvr"))
        }

        assertTrue(error.message.orEmpty().contains("Duplicate resource string|duplicate"))
        assertTrue(error.message.orEmpty().contains(inputFile.absolutePath))
    }

    @Test
    fun `accepts resource names normalized by Compose accessors`() {
        val outputFile = convert(
            """
            <resources>
                <string name="has-dash">dash</string>
                <string name="1invalid">digit</string>
            </resources>
            """.trimIndent(),
        )

        assertEquals("dash", decodeContent(outputFile, "string|has-dash"))
        assertEquals("digit", decodeContent(outputFile, "string|1invalid"))
    }

    @Test
    fun `rejects resource names that break CVR records`() {
        listOf(
            "<resources><string name=\"a|b\">value</string></resources>",
            "<resources><string name=\"has&#10;newline\">value</string></resources>",
            "<resources><string name=\"has&#13;return\">value</string></resources>",
        ).forEach { xml ->
            val inputFile = newInputFile(xml)

            val error = assertFailsWith<IllegalArgumentException> {
                converter.convert(inputFile, newOutputFile("invalid-name.cvr"))
            }

            assertTrue(error.message.orEmpty().contains("Invalid resource name"))
            assertTrue(error.message.orEmpty().contains(inputFile.absolutePath))
        }
    }

    @Test
    fun `rejects invalid and duplicate plural quantities`() {
        val invalidInput = newInputFile(
            "<resources><plurals name=\"count\"><item quantity=\"invalid\">value</item></plurals></resources>",
        )
        val invalidError = assertFailsWith<IllegalArgumentException> {
            converter.convert(invalidInput, newOutputFile("invalid-quantity.cvr"))
        }
        assertTrue(invalidError.message.orEmpty().contains("Invalid plural quantity invalid"))
        assertTrue(invalidError.message.orEmpty().contains(invalidInput.absolutePath))

        val duplicateInput = newInputFile(
            """
            <resources><plurals name="count">
                <item quantity="one">first</item>
                <item quantity="one">second</item>
            </plurals></resources>
            """.trimIndent(),
        )
        val duplicateError = assertFailsWith<IllegalArgumentException> {
            converter.convert(duplicateInput, newOutputFile("duplicate-quantity.cvr"))
        }
        assertTrue(duplicateError.message.orEmpty().contains("Duplicate plural quantity one"))
        assertTrue(duplicateError.message.orEmpty().contains(duplicateInput.absolutePath))
    }

    @Test
    fun `rejects non-whitespace text outside value items`() {
        val commentOutput = convert(
            "<resources><!-- root --><string-array name=\"valid\"><!-- item --><item>value</item></string-array></resources>",
        )
        assertEquals("value", decodeContent(commentOutput, "string-array|valid"))

        listOf(
            "<resources>stray<string name=\"valid\">value</string></resources>",
            "<resources><string-array name=\"valid\">stray<item>value</item></string-array></resources>",
            "<resources><plurals name=\"valid\">stray<item quantity=\"one\">value</item></plurals></resources>",
        ).forEach { xml ->
            val inputFile = newInputFile(xml)
            val error = assertFailsWith<IllegalArgumentException> {
                converter.convert(inputFile, newOutputFile("unexpected-text.cvr"))
            }

            assertTrue(error.message.orEmpty().contains("Unexpected text"))
            assertTrue(error.message.orEmpty().contains(inputFile.absolutePath))
        }
    }

    @Test
    fun `rejects unsupported value element with source path`() {
        val inputFile = newInputFile("<resources><color name=\"accent\">#ff0000</color></resources>")

        val error = assertFailsWith<IllegalArgumentException> {
            converter.convert(inputFile, newOutputFile("unsupported.cvr"))
        }

        assertTrue(error.message.orEmpty().contains("Unsupported value element color"))
        assertTrue(error.message.orEmpty().contains(inputFile.absolutePath))
    }

    @Test
    fun `rejects malformed XML with source path`() {
        listOf(
            "<resources><string name=\"broken\">value</resources>",
            "<!DOCTYPE resources [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><resources><string name=\"x\">&xxe;</string></resources>",
        ).forEach { xml ->
            val inputFile = newInputFile(xml)
            val error = assertFailsWith<IllegalArgumentException> {
                converter.convert(inputFile, newOutputFile("malformed.cvr"))
            }

            assertTrue(error.message.orEmpty().contains("Malformed values XML"))
            assertTrue(error.message.orEmpty().contains(inputFile.absolutePath))
        }
    }

    private fun convert(xml: String): File {
        val outputFile = newOutputFile("output.cvr")
        converter.convert(newInputFile(xml), outputFile)
        return outputFile
    }

    private fun newInputFile(xml: String): File = Files.createTempDirectory("compose-values-input")
        .resolve("values.xml")
        .toFile()
        .apply { writeText(xml) }

    private fun newOutputFile(name: String): File = Files.createTempDirectory("compose-values-output")
        .resolve("nested/$name")
        .toFile()

    private fun decodeContent(outputFile: File, recordPrefix: String): String {
        val encoded = outputFile.readLines().single { it.startsWith("$recordPrefix|") }.substringAfterLast('|')
        return Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
    }
}
