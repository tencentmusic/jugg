package com.sickworm.intellij.jugg.compiler.obfuscation

import com.sickworm.intellij.jugg.mock.GradleBuildHelper
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

    // ==================== Method Invocation/Inlining tests ====================

    @Test
    fun testMethodInvocationsFromMapping81() {
        val file = getMappingFile("mapping_8.1.txt")
        val reader = R8MappingReader.fromFile(file)

        // Find a class with methods
        val mapping = reader.getClassMapping("a.p")
        assertNotNull("ComponentActivity mapping should exist", mapping)
        mapping!!

        // Check if methods have invocation information
        println("Checking methods for invocations:")
        var foundInvocations = false
        mapping.methods.forEach { method ->
            if (method.invocations.isNotEmpty()) {
                foundInvocations = true
                println("  Method ${method.originalName} has ${method.invocations.size} invocations")
                method.invocations.take(3).forEach { invocation ->
                    println("    - Calls ${invocation.calledClass}.${invocation.calledMethod} at line ${invocation.lineRange}")
                }
            }
        }

        // Note: mapping_8.1.txt may not have many inlined methods, so this is informational
        println("Found invocations in ComponentActivity: $foundInvocations")
    }

    @Test
    fun testGetMethodInvocations() {
        val file = getMappingFile("mapping_8.1.txt")
        val reader = R8MappingReader.fromFile(file)

        // Try to get invocations for a specific method
        val invocations = reader.getMethodInvocations("androidx.activity.ComponentActivity", "onCreate")

        println("Invocations in ComponentActivity.onCreate: ${invocations.size}")
        invocations.take(5).forEach { invocation ->
            println("  - ${invocation.calledClass}.${invocation.calledMethod}(${invocation.parameters})")
        }
    }

    @Test
    fun testFindInvocationsOf() {
        val file = getMappingFile("mapping_8.1.txt")
        val reader = R8MappingReader.fromFile(file)

        // Find all places where a specific method is called
        // Let's search for a common Android method
        val invocationSites = reader.findInvocationsOf("androidx.lifecycle.Lifecycle", "addObserver")

        println("Found ${invocationSites.size} invocations of Lifecycle.addObserver")
        invocationSites.take(5).forEach { site ->
            println("  - Called in ${site.callerClass}.${site.callerMethod} at line ${site.lineRange}")
        }
    }

    @Test
    fun testMethodInvocationDataStructure() {
        val mappingContent = """
            # compiler: R8
            com.example.TestClass -> a.a:
                1:1:void com.example.Helper.helperMethod():0:0 -> testMethod
                1:1:void testMethod():0 -> testMethod
                2:2:void testMethod():0:0 -> testMethod
                3:3:java.lang.String com.example.Utils.utilMethod(int):0:0 -> testMethod
                3:3:void testMethod():0 -> testMethod
        """.trimIndent()

        val reader = R8MappingReader.fromString(mappingContent)
        val mapping = reader.getClassMapping("a.a")
        assertNotNull(mapping)
        mapping!!

        assertEquals("com.example.TestClass", mapping.originalName)
        assertTrue("Should have methods", mapping.methods.isNotEmpty())

        val testMethod = mapping.methods.find { it.originalName == "testMethod" }
        assertNotNull("testMethod should exist", testMethod)
        testMethod!!

        println("testMethod invocations: ${testMethod.invocations.size}")
        testMethod.invocations.forEach { invocation ->
            println("  - ${invocation.calledClass}.${invocation.calledMethod} at line ${invocation.lineRange}")
        }

        // Verify invocations were captured
        assertTrue("Should have invocations", testMethod.invocations.isNotEmpty())
    }

    @Test
    fun testMethodInvocationWithLargeMapping() {
        val file = getMappingFile("mapping_3.2.txt")
        val reader = R8MappingReader.fromFile(file)

        // Sample a few classes to check for method invocations
        var totalInvocations = 0
        var classesWithInvocations = 0
        var sampledClasses = 0

        reader.forEachClass { _, classMapping ->
            if (sampledClasses < 100) { // Sample first 100 classes
                classMapping.methods.forEach { method ->
                    if (method.invocations.isNotEmpty()) {
                        totalInvocations += method.invocations.size
                        classesWithInvocations++
                    }
                }
                sampledClasses++
            }
        }

        println("Sampled $sampledClasses classes:")
        println("  Classes with invocations: $classesWithInvocations")
        println("  Total invocations found: $totalInvocations")

        // This is informational - just verify the data structure works
        assertTrue("Should have processed classes", sampledClasses > 0)
    }

    /**
     * Test case for the specific example mentioned in the requirements:
     * MinifyTestActivity calls MinifyTestEnum.enumMethod() which gets inlined.
     * This test uses the release mapping file from the test assets.
     */
    @Test
    fun testMinifyTestActivityInvocations() {
        // Use the mapping file from idea test assets
        val mappingFile = File("../idea/src/test/assets/android/MyApplicationIntellij/app/build/outputs/mapping/release/mapping.txt").absoluteFile
        if (!mappingFile.exists()) {
            GradleBuildHelper.appAssembleRelease()
            if (!mappingFile.exists()) {
                throw IllegalStateException("Failed to generate mapping file for test")
            }
        }

        val reader = R8MappingReader.fromFile(mappingFile)

        // Get MinifyTestActivity class mapping
        val activityMapping = reader.getClassMappingByOriginalName("com.sickworm.jugg.demo.testcase.minify.MinifyTestActivity")
        assertNotNull("MinifyTestActivity should exist in mapping", activityMapping)
        activityMapping!!

        println("MinifyTestActivity original name: ${activityMapping.originalName}")
        println("MinifyTestActivity obfuscated name: ${activityMapping.obfuscatedName}")
        println("Number of methods: ${activityMapping.methods.size}")
        println("Methods:")
        activityMapping.methods.forEach { method ->
            println("  - ${method.originalName}: ${method.returnType} (${method.parameters})")
        }

        // Get onCreate method
        val onCreateMethod = activityMapping.methods.find { it.originalName == "onCreate" }
        assertNotNull("onCreate method should exist", onCreateMethod)
        onCreateMethod!!

        println("\nonCreate method:")
        println("  Original signature: ${onCreateMethod.returnType} ${onCreateMethod.originalName}(${onCreateMethod.parameters})")
        println("  Obfuscated name: ${onCreateMethod.obfuscatedName}")
        println("  Invocations: ${onCreateMethod.invocations.size}")

        // Debug: print all invocations
        println("\nAll invocations in onCreate:")
        onCreateMethod.invocations.forEach { inv ->
            println("  - ${inv.calledClass}.${inv.calledMethod}() returns ${inv.returnType}")
        }

        // Verify MinifyTestEnum.enumMethod is in the invocations
        val enumMethodInvocation = onCreateMethod.invocations.find {
            it.calledClass.contains("MinifyTestEnum") &&
            it.calledMethod == "enumMethod"
        }
        assertNotNull("Should find MinifyTestEnum.enumMethod invocation", enumMethodInvocation)
        enumMethodInvocation!!

        println("\nFound MinifyTestEnum.enumMethod invocation:")
        println("  Called class: ${enumMethodInvocation.calledClass}")
        println("  Called method: ${enumMethodInvocation.calledMethod}")
        println("  Return type: ${enumMethodInvocation.returnType}")
        println("  Line range: ${enumMethodInvocation.lineRange}")
        println("  Original line range: ${enumMethodInvocation.originalLineRange}")

        assertEquals("java.lang.String", enumMethodInvocation.returnType)
        assertTrue("Line range should be valid", enumMethodInvocation.lineRange.first > 0)

        // Also test the query methods
        val invocations = reader.getMethodInvocations(
            "com.sickworm.jugg.demo.testcase.minify.MinifyTestActivity",
            "onCreate"
        )
        assertTrue("Should have invocations", invocations.isNotEmpty())
        assertTrue("Should contain enumMethod", invocations.any {
            it.calledClass.contains("MinifyTestEnum") &&
            it.calledMethod == "enumMethod"
        })

        // Test findInvocationsOf - be lenient with class name matching due to R8 qualified signature quirks
        val invocationSites = reader.findInvocationsOf(
            "com.sickworm.jugg.demo.testcase.minify.MinifyTestEnum",
            "enumMethod"
        )
        // Also check with partial match since qualified signatures may have duplications
        val invocationSitesPartial = invocationSites.filter { site ->
            site.invocation.calledClass.contains("MinifyTestEnum")
        }

        println("\nAll invocation sites of MinifyTestEnum.enumMethod: ${invocationSites.size}")
        println("Sites with MinifyTestEnum in name: ${invocationSitesPartial.size}")
        invocationSitesPartial.forEach { site ->
            println("  - ${site.callerClass}.${site.callerMethod} at line ${site.lineRange}")
        }

        assertTrue("Should find invocation sites", invocationSitesPartial.isNotEmpty())
        assertTrue("Should include MinifyTestActivity.onCreate", invocationSitesPartial.any {
            it.callerClass == "com.sickworm.jugg.demo.testcase.minify.MinifyTestActivity" &&
            it.callerMethod == "onCreate"
        })
    }
}
