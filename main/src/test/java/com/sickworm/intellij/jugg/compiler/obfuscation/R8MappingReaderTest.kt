package com.sickworm.intellij.jugg.compiler.obfuscation

import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Unit tests for R8MappingReader.
 *
 * Validates parsing capability for different scale mapping files:
 * - mapping_8.1.txt (~9MB)
 * - mapping_3.2.txt (~78MB)
 */
class R8MappingReaderTest {

    companion object {
        private lateinit var testResourcesDir: File

        @JvmStatic
        @BeforeClass
        fun setup() {
            // Initialize TestGlobal to set up PlatformApi
            TestGlobal
            testResourcesDir = File("src/test/resources").absoluteFile
        }
    }

    private fun getMappingFile(name: String): File {
        return File(testResourcesDir, name).also {
            assertTrue("Mapping file not found: ${it.absolutePath}", it.exists())
        }
    }

    // ==================== mapping_8.1.txt tests (~9MB) ====================

    @Test
    fun testParseMapping81TxtBasicLoading() {
        val file = getMappingFile("mapping_8.1.txt")
        val reader = R8MappingReader.fromFile(file)

        val classCount = reader.getClassCount()
        println("mapping_8.1.txt: $classCount classes")
        assertTrue("Should have classes", classCount > 0)
    }

    @Test
    fun testParseMapping81TxtClassLookup() {
        val file = getMappingFile("mapping_8.1.txt")
        val reader = R8MappingReader.fromFile(file)

        // Verify based on mapping_8.1.txt content
        // androidx.activity.BackEventCompat -> a.b:
        val originalName = reader.getOriginalClassName("a.b")
        assertEquals("androidx.activity.BackEventCompat", originalName)

        // Reverse lookup
        val obfuscatedName = reader.getObfuscatedClassName("androidx.activity.BackEventCompat")
        assertEquals("a.b", obfuscatedName)
    }

    @Test
    fun testParseMapping81TxtClassMappingDetails() {
        val file = getMappingFile("mapping_8.1.txt")
        val reader = R8MappingReader.fromFile(file)

        // androidx.activity.BackEventCompat -> a.b:
        val mapping = reader.getClassMapping("a.b")
        assertNotNull("Class mapping should exist", mapping)
        mapping!!

        assertEquals("androidx.activity.BackEventCompat", mapping.originalName)
        assertEquals("a.b", mapping.obfuscatedName)

        // Verify field mappings
        // float touchX -> a
        val touchXField = mapping.fields.find { it.originalName == "touchX" }
        assertNotNull("touchX field should exist", touchXField)
        assertEquals("a", touchXField!!.obfuscatedName)
        assertEquals("float", touchXField.type)

        // float progress -> c
        val progressField = mapping.fields.find { it.originalName == "progress" }
        assertNotNull("progress field should exist", progressField)
        assertEquals("c", progressField!!.obfuscatedName)
    }

    @Test
    fun testParseMapping81TxtMethodMapping() {
        val file = getMappingFile("mapping_8.1.txt")
        val reader = R8MappingReader.fromFile(file)

        // androidx.activity.ComponentActivity -> a.p:
        val mapping = reader.getClassMapping("a.p")
        assertNotNull("ComponentActivity mapping should exist", mapping)
        mapping!!

        assertEquals("androidx.activity.ComponentActivity", mapping.originalName)

        // Verify method mappings exist
        assertTrue("Should have methods", mapping.methods.isNotEmpty())
        println("ComponentActivity methods count: ${mapping.methods.size}")
    }

    @Test
    fun testParseMapping81TxtFindByPrefix() {
        val file = getMappingFile("mapping_8.1.txt")
        val reader = R8MappingReader.fromFile(file)

        // Find classes in androidx.activity package
        val activityClasses = reader.findClassesByOriginalPrefix("androidx.activity.")
        assertTrue("Should find activity classes", activityClasses.isNotEmpty())
        println("Found ${activityClasses.size} classes in androidx.activity package")
    }

    @Test
    fun testParseMapping81TxtMapVersion() {
        val file = getMappingFile("mapping_8.1.txt")
        val reader = R8MappingReader.fromFile(file)

        val version = reader.getMapVersion()
        println("mapping_8.1.txt version: $version")
        // R8 mapping files typically have version info
        assertNotNull("Should have map version", version)
    }

    // ==================== mapping_3.2.txt tests (~24MB) ====================

    @Test
    fun testParseMapping32BasicLoading() {
        val file = getMappingFile("mapping_3.2.txt")
        val reader = R8MappingReader.fromFile(file)

        val classCount = reader.getClassCount()
        println("mapping_3.2.txt: $classCount classes")
        assertTrue("Should have many classes", classCount > 1000)
    }

    @Test
    fun testParseMapping32IterateSampleClasses() {
        val file = getMappingFile("mapping_3.2.txt")
        val reader = R8MappingReader.fromFile(file)

        var count = 0
        val sampleClasses = mutableListOf<R8MappingReader.ClassMapping>()

        reader.forEachClass { _, classMapping ->
            if (count < 10) {
                sampleClasses.add(classMapping)
            }
            count++
        }

        println("Sample classes from mapping_3.2.txt:")
        sampleClasses.forEach {
            println("  ${it.originalName} -> ${it.obfuscatedName} (${it.fields.size} fields, ${it.methods.size} methods)")
        }

        assertEquals("Should iterate all classes", reader.getClassCount(), count)
    }

    @Test
    fun testParseMapping32FieldAndMethodCounts() {
        val file = getMappingFile("mapping_3.2.txt")
        val reader = R8MappingReader.fromFile(file)

        var totalFields = 0
        var totalMethods = 0

        reader.forEachClass { _, classMapping ->
            totalFields += classMapping.fields.size
            totalMethods += classMapping.methods.size
        }

        println("mapping_3.2.txt: $totalFields fields, $totalMethods methods")
        assertTrue("Should have fields", totalFields > 0)
        assertTrue("Should have methods", totalMethods > 0)
    }

    // ==================== General functionality tests ====================

    @Test
    fun testFromStringParseInlineMapping() {
        val mappingContent = """
            # compiler: R8
            # common_typos_disable
            com.example.MyClass -> a.a:
                java.lang.String name -> a
                int count -> b
                1:10:void doSomething():0:0 -> a
                11:20:java.lang.String getName():0:0 -> b
        """.trimIndent()

        val reader = R8MappingReader.fromString(mappingContent)

        assertEquals(1, reader.getClassCount())

        val mapping = reader.getClassMapping("a.a")
        assertNotNull(mapping)
        mapping!!

        assertEquals("com.example.MyClass", mapping.originalName)
        assertEquals(2, mapping.fields.size)
        assertEquals(2, mapping.methods.size)

        // Verify fields
        val nameField = mapping.fields.find { it.originalName == "name" }
        assertNotNull(nameField)
        assertEquals("java.lang.String", nameField!!.type)
        assertEquals("a", nameField.obfuscatedName)

        // Verify methods
        val doSomethingMethod = mapping.methods.find { it.originalName == "doSomething" }
        assertNotNull(doSomethingMethod)
        assertEquals("void", doSomethingMethod!!.returnType)
        assertEquals("a", doSomethingMethod.obfuscatedName)
    }

    @Test
    fun testGetClassMappingByOriginalName() {
        val file = getMappingFile("mapping_8.1.txt")
        val reader = R8MappingReader.fromFile(file)

        val mapping = reader.getClassMappingByOriginalName("androidx.activity.BackEventCompat")
        assertNotNull("Should find class by original name", mapping)
        mapping!!

        assertEquals("a.b", mapping.obfuscatedName)
    }

    @Test
    fun testNonExistentClassReturnsNull() {
        val file = getMappingFile("mapping_8.1.txt")
        val reader = R8MappingReader.fromFile(file)

        assertNull(reader.getOriginalClassName("non.existent.Class"))
        assertNull(reader.getObfuscatedClassName("non.existent.Class"))
        assertNull(reader.getClassMapping("non.existent.Class"))
        assertNull(reader.getClassMappingByOriginalName("non.existent.Class"))
    }
}
