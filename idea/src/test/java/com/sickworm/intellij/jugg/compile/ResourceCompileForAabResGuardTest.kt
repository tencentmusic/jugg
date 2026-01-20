package com.sickworm.intellij.jugg.compile

import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.overlay.AabResGuardHandler
import com.sickworm.intellij.jugg.compiler.overlay.AabResGuardMappingParser
import com.sickworm.intellij.jugg.compiler.overlay.AabResGuardResourceProcessor
import com.sickworm.intellij.jugg.compiler.overlay.ResourceCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceCompileForAabResGuardTest {

    private lateinit var aabResGuardHandler: AabResGuardHandler
    private val testMappingDir = File(buildDir, "aabresguard_test")

    @Before
    fun init() {
        clearBuild()
        aabResGuardHandler = AabResGuardHandler(logger)
        testMappingDir.mkdirs()
    }

    @After
    fun release() {
        testMappingDir.deleteRecursively()
    }

    @Test
    fun testParseMappingFile() {
        val mappingFile = createTestMappingFile()
        val mappings = AabResGuardMappingParser.parse(mappingFile)

        // Verify basic parsing
        assertTrue(mappings.isNotEmpty(), "Mappings should not be empty")
        assertEquals(5, mappings.size, "Should parse 5 resource mappings")

        // Verify color mappings
        val colorPrimary = mappings["color/colorPrimary"]
        assertNotNull(colorPrimary, "colorPrimary mapping should exist")
        assertEquals("colorPrimary", colorPrimary.originalName)
        assertEquals("a", colorPrimary.obfuscatedName)
        assertEquals("color", colorPrimary.resourceType)

        val colorAccent = mappings["color/colorAccent"]
        assertNotNull(colorAccent, "colorAccent mapping should exist")
        assertEquals("colorAccent", colorAccent.originalName)
        assertEquals("b", colorAccent.obfuscatedName)
        assertEquals("color", colorAccent.resourceType)

        // Verify drawable mappings
        val icLauncher = mappings["drawable/ic_launcher"]
        assertNotNull(icLauncher, "ic_launcher mapping should exist")
        assertEquals("ic_launcher", icLauncher.originalName)
        assertEquals("c", icLauncher.obfuscatedName)
        assertEquals("drawable", icLauncher.resourceType)

        // Verify style mappings
        val appTheme = mappings["style/AppTheme"]
        assertNotNull(appTheme, "AppTheme mapping should exist")
        assertEquals("AppTheme", appTheme.originalName)
        assertEquals("d", appTheme.obfuscatedName)
        assertEquals("style", appTheme.resourceType)

        // Verify string mappings
        val appName = mappings["string/app_name"]
        assertNotNull(appName, "app_name mapping should exist")
        assertEquals("app_name", appName.originalName)
        assertEquals("e", appName.obfuscatedName)
        assertEquals("string", appName.resourceType)
    }

    @Test
    fun testParseEmptyMappingFile() {
        val emptyMappingFile = File(testMappingDir, "empty-mapping.txt")
        emptyMappingFile.writeText("""
            res dir mapping:

            res id mapping:

            res entries path mapping:
        """.trimIndent())

        val mappings = AabResGuardMappingParser.parse(emptyMappingFile)
        assertTrue(mappings.isEmpty(), "Empty mapping file should result in empty mappings")
    }

    @Test
    fun testParseMalformedMappingFile() {
        val malformedFile = File(testMappingDir, "malformed-mapping.txt")
        malformedFile.writeText("""
            res id mapping:
                This is not a valid mapping line
                Another invalid line without proper format
        """.trimIndent())

        val mappings = AabResGuardMappingParser.parse(malformedFile)
        assertTrue(mappings.isEmpty(), "Malformed mapping file should result in empty mappings")
    }

    @Test
    fun testResourceReferenceReplacement() {
        val mappingFile = createTestMappingFile()
        val mappings = AabResGuardMappingParser.parse(mappingFile)
        val processor = AabResGuardResourceProcessor(mappings, logger)

        val testXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:background="@color/colorPrimary">

                <ImageView
                    android:src="@drawable/ic_launcher" />

                <TextView
                    android:textColor="@color/colorAccent"
                    android:text="@string/app_name" />
            </LinearLayout>
        """.trimIndent()

        val inputFile = File(testMappingDir, "test_layout.xml")
        inputFile.writeText(testXml)

        val outputDir = File(testMappingDir, "output")
        outputDir.mkdirs()

        val baseDir = File(assetsAndroidDir, "app/src/main/res")
        val compileFile = CompileFile(CompileFile.Type.Resource, inputFile, baseDir, mockModule)

        val processedFiles = processor.processResourceFiles(listOf(inputFile), outputDir, compileFile)

        assertEquals(1, processedFiles.size)
        val processedFile = processedFiles.first()
        assertTrue(processedFile.exists())

        val processedContent = processedFile.readText()

        // Verify references are replaced
        assertTrue(processedContent.contains("@color/a"), "colorPrimary should be replaced with 'a'")
        assertTrue(processedContent.contains("@drawable/c"), "ic_launcher should be replaced with 'c'")
        assertTrue(processedContent.contains("@color/b"), "colorAccent should be replaced with 'b'")
        assertTrue(processedContent.contains("@string/e"), "app_name should be replaced with 'e'")

        // Verify original references are gone
        assertTrue(!processedContent.contains("@color/colorPrimary"))
        assertTrue(!processedContent.contains("@drawable/ic_launcher"))
        assertTrue(!processedContent.contains("@color/colorAccent"))
        assertTrue(!processedContent.contains("@string/app_name"))
    }

    @Test
    fun testPreserveUnobfuscatedResources() {
        val mappingFile = createTestMappingFile()
        val mappings = AabResGuardMappingParser.parse(mappingFile)
        val processor = AabResGuardResourceProcessor(mappings, logger)

        val testXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                android:textColor="@android:color/white"
                android:background="@color/colorPrimary"
                android:id="@+id/my_text_view" />
        """.trimIndent()

        val inputFile = File(testMappingDir, "test_preserve.xml")
        inputFile.writeText(testXml)

        val outputDir = File(testMappingDir, "output_preserve")
        outputDir.mkdirs()

        val baseDir = File(assetsAndroidDir, "app/src/main/res")
        val compileFile = CompileFile(CompileFile.Type.Resource, inputFile, baseDir, mockModule)

        val processedFiles = processor.processResourceFiles(listOf(inputFile), outputDir, compileFile)
        val processedContent = processedFiles.first().readText()

        // System resources should be preserved
        assertTrue(processedContent.contains("@android:color/white"), "System resources should not be replaced")

        // ID definitions should be preserved
        assertTrue(processedContent.contains("@+id/my_text_view"), "ID definitions should not be replaced")

        // App resources should be replaced
        assertTrue(processedContent.contains("@color/a"), "colorPrimary should be replaced")
    }

    @Test
    fun testProcessNonXmlFiles() {
        val mappingFile = createTestMappingFile()
        val mappings = AabResGuardMappingParser.parse(mappingFile)
        val processor = AabResGuardResourceProcessor(mappings, logger)

        // Create a dummy PNG file
        val pngFile = File(testMappingDir, "test_image.png")
        pngFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) // PNG magic number

        val outputDir = File(testMappingDir, "output_png")
        outputDir.mkdirs()

        val baseDir = File(assetsAndroidDir, "app/src/main/res")
        val compileFile = CompileFile(CompileFile.Type.Resource, pngFile, baseDir, mockModule)

        val processedFiles = processor.processResourceFiles(listOf(pngFile), outputDir, compileFile)

        assertEquals(1, processedFiles.size)
        val processedFile = processedFiles.first()
        assertTrue(processedFile.exists())

        // PNG file should be copied as-is
        assertTrue(processedFile.readBytes().contentEquals(pngFile.readBytes()), "Non-XML files should be copied without modification")
    }

    @Test
    fun testProcessMultipleXmlFiles() {
        val mappingFile = createTestMappingFile()
        val mappings = AabResGuardMappingParser.parse(mappingFile)
        val processor = AabResGuardResourceProcessor(mappings, logger)

        val layout1 = File(testMappingDir, "layout1.xml")
        layout1.writeText("""<TextView android:textColor="@color/colorPrimary" />""")

        val layout2 = File(testMappingDir, "layout2.xml")
        layout2.writeText("""<ImageView android:src="@drawable/ic_launcher" />""")

        val outputDir = File(testMappingDir, "output_multiple")
        outputDir.mkdirs()

        val baseDir = File(assetsAndroidDir, "app/src/main/res")
        val compileFile = CompileFile(CompileFile.Type.Resource, testMappingDir, baseDir, mockModule)

        val processedFiles = processor.processResourceFiles(listOf(layout1, layout2), outputDir, compileFile)

        assertEquals(2, processedFiles.size)

        val processed1 = processedFiles.find { it.name == "layout1.xml" }
        assertNotNull(processed1)
        assertTrue(processed1.readText().contains("@color/a"))

        val processed2 = processedFiles.find { it.name == "layout2.xml" }
        assertNotNull(processed2)
        assertTrue(processed2.readText().contains("@drawable/c"))
    }

    @Test
    fun testHandlerWithNoMappingFile() {
        // Create a task with no mapping file
        val baseDir = File(assetsAndroidDir, "app/src/main/res")
        val layoutFile = File(assetsAndroidDir, "app/src/main/res/layout/activity_main.xml")

        val task = CompileTask(
            listOf(CompileFile(CompileFile.Type.Resource, layoutFile, baseDir, mockModule)),
            stagingDir
        )

        val resCompileSet = ResourceCompiler.ResCompileSet(
            task,
            mapOf(task.files.first() to listOf(layoutFile)),
            stagingDir
        )

        // Process should return original ResCompileSet when no mapping file exists
        val result = aabResGuardHandler.process(resCompileSet)
        assertNotNull(result, "Should return original ResCompileSet when no mapping file exists")
        assertEquals(resCompileSet, result, "Should return the same ResCompileSet")
    }

    @Test
    fun testHandlerWithMappingFile() {
        // Create mapping file in the expected location
        val moduleRootDir = mockModule.moduleRootDir
        val mappingFileDir = File(moduleRootDir, "build/outputs/bundle/debug")
        mappingFileDir.mkdirs()

        val mappingFile = File(mappingFileDir, "resources-mapping.txt")
        createTestMappingFileAt(mappingFile)

        try {
            // Create a test XML file
            val testXmlContent = """<TextView android:textColor="@color/colorPrimary" />"""
            val testXmlFile = File(testMappingDir, "test.xml")
            testXmlFile.writeText(testXmlContent)

            val baseDir = File(assetsAndroidDir, "app/src/main/res")
            val task = CompileTask(
                listOf(CompileFile(CompileFile.Type.Resource, testXmlFile, baseDir, mockModule)),
                stagingDir
            )

            val resCompileSet = ResourceCompiler.ResCompileSet(
                task,
                mapOf(task.files.first() to listOf(testXmlFile)),
                stagingDir
            )

            // Process should apply obfuscation
            val result = aabResGuardHandler.process(resCompileSet)
            assertNotNull(result, "Should return processed ResCompileSet")

            // Verify the file was processed
            val processedFiles = result.compileFileMap.values.flatten()
            assertEquals(1, processedFiles.size)

            val processedFile = processedFiles.first()
            assertTrue(processedFile.exists())
            val content = processedFile.readText()
            assertTrue(content.contains("@color/a"), "Should replace colorPrimary with obfuscated name 'a'")
        } finally {
            mappingFile.delete()
            mappingFileDir.deleteRecursively()
        }
    }

    @Test
    fun testHandlerWithEmptyMappings() {
        // Create empty mapping file
        val moduleRootDir = mockModule.moduleRootDir
        val mappingFileDir = File(moduleRootDir, "build/outputs/bundle/debug")
        mappingFileDir.mkdirs()

        val mappingFile = File(mappingFileDir, "resources-mapping.txt")
        mappingFile.writeText("""
            res dir mapping:

            res id mapping:

            res entries path mapping:
        """.trimIndent())

        try {
            val baseDir = File(assetsAndroidDir, "app/src/main/res")
            val layoutFile = File(assetsAndroidDir, "app/src/main/res/layout/activity_main.xml")

            val task = CompileTask(
                listOf(CompileFile(CompileFile.Type.Resource, layoutFile, baseDir, mockModule)),
                stagingDir
            )

            val resCompileSet = ResourceCompiler.ResCompileSet(
                task,
                mapOf(task.files.first() to listOf(layoutFile)),
                stagingDir
            )

            // Should return original ResCompileSet when mappings are empty
            val result = aabResGuardHandler.process(resCompileSet)
            assertNotNull(result, "Should return original ResCompileSet when mappings are empty")
            assertEquals(resCompileSet, result, "Should return the same ResCompileSet")
        } finally {
            mappingFile.delete()
            mappingFileDir.deleteRecursively()
        }
    }

    /**
     * Create a test mapping file with sample resource mappings
     */
    private fun createTestMappingFile(): File {
        val mappingFile = File(testMappingDir, "resources-mapping.txt")
        createTestMappingFileAt(mappingFile)
        return mappingFile
    }

    private fun createTestMappingFileAt(file: File) {
        file.writeText("""
            res dir mapping:
                res/layout -> res/a
                res/drawable -> res/b

            res id mapping:
                0x7f040001 : com.example.myapplication.R.color.colorPrimary -> com.example.myapplication.R.color.a
                0x7f040002 : com.example.myapplication.R.color.colorAccent -> com.example.myapplication.R.color.b
                0x7f050001 : com.example.myapplication.R.drawable.ic_launcher -> com.example.myapplication.R.drawable.c
                0x7f060001 : com.example.myapplication.R.style.AppTheme -> com.example.myapplication.R.style.d
                0x7f070001 : com.example.myapplication.R.string.app_name -> com.example.myapplication.R.string.e

            res entries path mapping:
                0x7f050001 : res/drawable/ic_launcher.png -> res/b/c.png
        """.trimIndent())
    }
}
