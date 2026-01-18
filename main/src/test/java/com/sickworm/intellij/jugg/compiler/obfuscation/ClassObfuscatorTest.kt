package com.sickworm.intellij.jugg.compiler.obfuscation

import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.ClassNode
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.FieldNode
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.MethodNode
import java.io.File

/**
 * Unit tests for ClassObfuscator.
 *
 * Tests the ASM-based class obfuscation functionality.
 */
class ClassObfuscatorTest {

    companion object {
        private lateinit var testResourcesDir: File
        private lateinit var tempDir: File

        @JvmStatic
        @BeforeClass
        fun setup() {
            TestGlobal
            testResourcesDir = File("src/test/resources").absoluteFile
            tempDir = File(System.getProperty("java.io.tmpdir"), "ClassObfuscatorTest")
        }
    }

    @Before
    fun setupTest() {
        tempDir.deleteRecursively()
        tempDir.mkdirs()
    }

    // ==================== Basic obfuscation tests ====================

    @Test
    fun testObfuscateClassFromMappingString() {
        val mappingContent = """
            com.example.OriginalClass -> a.b:
                java.lang.String originalField -> a
                int count -> b
                1:10:void originalMethod():0:0 -> c
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        // Verify mapping stats
        val stats = obfuscator.getMappingStats()
        assertEquals(1, stats.classCount)
        assertEquals(2, stats.fieldCount)
        assertEquals(1, stats.methodCount)

        // Verify class name lookup
        assertEquals("a.b", obfuscator.getObfuscatedClassName("com.example.OriginalClass"))
        assertNull(obfuscator.getObfuscatedClassName("com.example.NonExistent"))
    }

    @Test
    fun testObfuscateClassBytes() {
        val mappingContent = """
            com.example.TestClass -> a.b.c:
                java.lang.String name -> a
                void testMethod() -> b
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        // Create a simple class using ASM
        val originalClass = createTestClass(
            name = "com/example/TestClass",
            fields = listOf("name" to "Ljava/lang/String;"),
            methods = listOf("testMethod" to "()V")
        )

        // Obfuscate
        val obfuscatedBytes = obfuscator.obfuscate(originalClass)
        assertNotNull("Should have obfuscated the class", obfuscatedBytes)

        // Verify the obfuscated class
        val classNode = readClass(obfuscatedBytes!!)
        assertEquals("a/b/c", classNode.name)

        // Verify field was renamed
        val field = classNode.fields.find { it.name == "a" }
        assertNotNull("Field should be renamed to 'a'", field)

        // Verify method was renamed
        val method = classNode.methods.find { it.name == "b" }
        assertNotNull("Method should be renamed to 'b'", method)
    }

    @Test
    fun testObfuscateClassWithNoMapping() {
        val mappingContent = """
            com.example.OtherClass -> a.b:
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        // Create a class that's NOT in the mapping
        val originalClass = createTestClass(
            name = "com/example/NotInMapping",
            fields = listOf("field1" to "I"),
            methods = listOf("method1" to "()V")
        )

        // Obfuscate - should return null since class is not in mapping
        val obfuscatedBytes = obfuscator.obfuscate(originalClass)

        // Since class is not in mapping, no remapping should occur
        // The method should return null when no changes were made
        assertNull("Should return null when no remapping applied", obfuscatedBytes)
    }

    @Test
    fun testObfuscateClassPath() {
        val mappingContent = """
            com.example.deep.nested.MyClass -> a.b:
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        val inputFile = File(tempDir, "com/example/deep/nested/MyClass.class")
        inputFile.parentFile.mkdirs()
        inputFile.writeBytes(ByteArray(0)) // Create empty file for testing

        val obfuscatedPath = obfuscator.getObfuscatedClassPath(inputFile, tempDir)
        assertEquals("a${File.separatorChar}b.class", obfuscatedPath)
    }

    @Test
    fun testObfuscateClassPathNotInMapping() {
        val mappingContent = """
            com.example.OtherClass -> a.b:
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        val inputFile = File(tempDir, "com/example/NotMapped.class")
        inputFile.parentFile.mkdirs()
        inputFile.writeBytes(ByteArray(0))

        val obfuscatedPath = obfuscator.getObfuscatedClassPath(inputFile, tempDir)
        // Should return original path when not in mapping
        assertEquals("com${File.separatorChar}example${File.separatorChar}NotMapped.class", obfuscatedPath)
    }

    // ==================== Method descriptor tests ====================

    @Test
    fun testObfuscateMethodWithParameters() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void methodWithParams(java.lang.String,int) -> c
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        val originalClass = createTestClass(
            name = "com/example/TestClass",
            fields = emptyList(),
            methods = listOf("methodWithParams" to "(Ljava/lang/String;I)V")
        )

        val obfuscatedBytes = obfuscator.obfuscate(originalClass)
        assertNotNull(obfuscatedBytes)

        val classNode = readClass(obfuscatedBytes!!)
        val method = classNode.methods.find { it.name == "c" }
        assertNotNull("Method with params should be renamed to 'c'", method)
    }

    @Test
    fun testObfuscateMethodWithArrayParameters() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void methodWithArray(int[],java.lang.String[]) -> d
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        val originalClass = createTestClass(
            name = "com/example/TestClass",
            fields = emptyList(),
            methods = listOf("methodWithArray" to "([I[Ljava/lang/String;)V")
        )

        val obfuscatedBytes = obfuscator.obfuscate(originalClass)
        assertNotNull(obfuscatedBytes)

        val classNode = readClass(obfuscatedBytes!!)
        val method = classNode.methods.find { it.name == "d" }
        assertNotNull("Method with array params should be renamed to 'd'", method)
    }

    // ==================== Reference remapping tests ====================

    @Test
    fun testObfuscateClassReference() {
        val mappingContent = """
            com.example.Referenced -> x.y:
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        // Create a class that has a field referencing another class
        val classNode = ClassNode()
        classNode.version = Opcodes.V11
        classNode.access = Opcodes.ACC_PUBLIC
        classNode.name = "com/example/TestClass"
        classNode.superName = "java/lang/Object"

        // Add a field of type Referenced
        val fieldNode = FieldNode(
            Opcodes.ACC_PRIVATE,
            "ref",
            "Lcom/example/Referenced;",
            null,
            null
        )
        classNode.fields.add(fieldNode)

        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        val originalBytes = classWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull(obfuscatedBytes)

        val obfuscatedClass = readClass(obfuscatedBytes!!)

        // Class should be renamed
        assertEquals("a/b", obfuscatedClass.name)

        // Field type should also be remapped
        val field = obfuscatedClass.fields[0]
        assertEquals("Lx/y;", field.desc)
    }

    // ==================== File obfuscation tests ====================

    @Test
    fun testObfuscateFile() {
        val mappingContent = """
            com.example.FileTest -> a.b:
                int value -> x
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        // Create input class file
        val inputDir = File(tempDir, "input")
        inputDir.mkdirs()
        val inputFile = File(inputDir, "com/example/FileTest.class")
        inputFile.parentFile.mkdirs()

        val classBytes = createTestClass(
            name = "com/example/FileTest",
            fields = listOf("value" to "I"),
            methods = emptyList()
        )
        inputFile.writeBytes(classBytes)

        // Obfuscate to output
        val outputDir = File(tempDir, "output")
        val outputFile = File(outputDir, "a/b.class")

        val success = obfuscator.obfuscate(inputFile, outputFile)
        assertTrue("Obfuscation should succeed", success)
        assertTrue("Output file should exist", outputFile.exists())

        // Verify content
        val obfuscatedClass = readClass(outputFile.readBytes())
        assertEquals("a/b", obfuscatedClass.name)

        val field = obfuscatedClass.fields.find { it.name == "x" }
        assertNotNull("Field should be renamed to 'x'", field)
    }

    // ==================== Superclass and interface tests ====================

    @Test
    fun testObfuscateSuperclass() {
        val mappingContent = """
            com.example.BaseClass -> x.y.z:
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        // Create a class that extends BaseClass
        val classNode = ClassNode()
        classNode.version = Opcodes.V11
        classNode.access = Opcodes.ACC_PUBLIC
        classNode.name = "com/example/TestClass"
        classNode.superName = "com/example/BaseClass"

        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        val originalBytes = classWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the class", obfuscatedBytes)

        val obfuscatedClass = readClass(obfuscatedBytes!!)

        // Class should be renamed
        assertEquals("a/b", obfuscatedClass.name)

        // Superclass should also be remapped
        assertEquals("x/y/z", obfuscatedClass.superName)
    }

    @Test
    fun testObfuscateInterfaces() {
        val mappingContent = """
            com.example.InterfaceA -> i.a:
            com.example.InterfaceB -> i.b:
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        // Create a class that implements multiple interfaces
        val classNode = ClassNode()
        classNode.version = Opcodes.V11
        classNode.access = Opcodes.ACC_PUBLIC
        classNode.name = "com/example/TestClass"
        classNode.superName = "java/lang/Object"
        classNode.interfaces = mutableListOf("com/example/InterfaceA", "com/example/InterfaceB")

        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        val originalBytes = classWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the class", obfuscatedBytes)

        val obfuscatedClass = readClass(obfuscatedBytes!!)

        // Class should be renamed
        assertEquals("a/b", obfuscatedClass.name)

        // Both interfaces should be remapped
        assertEquals(2, obfuscatedClass.interfaces.size)
        assertTrue("InterfaceA should be remapped", obfuscatedClass.interfaces.contains("i/a"))
        assertTrue("InterfaceB should be remapped", obfuscatedClass.interfaces.contains("i/b"))
    }

    @Test
    fun testObfuscateSuperclassNotInMapping() {
        val mappingContent = """
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        // Create a class that extends a class not in the mapping
        val classNode = ClassNode()
        classNode.version = Opcodes.V11
        classNode.access = Opcodes.ACC_PUBLIC
        classNode.name = "com/example/TestClass"
        classNode.superName = "com/example/NotInMapping"

        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        val originalBytes = classWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the class", obfuscatedBytes)

        val obfuscatedClass = readClass(obfuscatedBytes!!)

        // Class should be renamed
        assertEquals("a/b", obfuscatedClass.name)

        // Superclass should NOT be remapped (not in mapping)
        assertEquals("com/example/NotInMapping", obfuscatedClass.superName)
    }

    @Test
    fun testObfuscateMixedInterfaces() {
        val mappingContent = """
            com.example.InterfaceA -> i.a:
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        // Create a class with one interface in mapping and one not
        val classNode = ClassNode()
        classNode.version = Opcodes.V11
        classNode.access = Opcodes.ACC_PUBLIC
        classNode.name = "com/example/TestClass"
        classNode.superName = "java/lang/Object"
        classNode.interfaces = mutableListOf("com/example/InterfaceA", "com/example/NotInMapping")

        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        val originalBytes = classWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the class", obfuscatedBytes)

        val obfuscatedClass = readClass(obfuscatedBytes!!)

        // Class should be renamed
        assertEquals("a/b", obfuscatedClass.name)

        // InterfaceA should be remapped, NotInMapping should stay the same
        assertEquals(2, obfuscatedClass.interfaces.size)
        assertTrue("InterfaceA should be remapped", obfuscatedClass.interfaces.contains("i/a"))
        assertTrue("NotInMapping should stay the same", obfuscatedClass.interfaces.contains("com/example/NotInMapping"))
    }

    // ==================== Special methods tests ====================

    @Test
    fun testConstructorNotRenamed() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void normalMethod() -> c
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        val originalClass = createTestClass(
            name = "com/example/TestClass",
            fields = emptyList(),
            methods = listOf("<init>" to "()V", "normalMethod" to "()V")
        )

        val obfuscatedBytes = obfuscator.obfuscate(originalClass)
        assertNotNull(obfuscatedBytes)

        val classNode = readClass(obfuscatedBytes!!)

        // Constructor should NOT be renamed
        val constructor = classNode.methods.find { it.name == "<init>" }
        assertNotNull("Constructor should keep original name", constructor)

        // Normal method should be renamed
        val method = classNode.methods.find { it.name == "c" }
        assertNotNull("Normal method should be renamed", method)
    }

    @Test
    fun testStaticInitializerNotRenamed() {
        val mappingContent = """
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = ClassObfuscator.fromMappingString(mappingContent)

        val originalClass = createTestClass(
            name = "com/example/TestClass",
            fields = emptyList(),
            methods = listOf("<clinit>" to "()V")
        )

        val obfuscatedBytes = obfuscator.obfuscate(originalClass)
        assertNotNull(obfuscatedBytes)

        val classNode = readClass(obfuscatedBytes!!)
        val staticInit = classNode.methods.find { it.name == "<clinit>" }
        assertNotNull("Static initializer should keep original name", staticInit)
    }

    // ==================== Helper methods ====================

    /**
     * Create a simple test class using ASM.
     */
    private fun createTestClass(
        name: String,
        fields: List<Pair<String, String>>,
        methods: List<Pair<String, String>>
    ): ByteArray {
        val classNode = ClassNode()
        classNode.version = Opcodes.V11
        classNode.access = Opcodes.ACC_PUBLIC
        classNode.name = name
        classNode.superName = "java/lang/Object"

        fields.forEach { (fieldName, fieldDesc) ->
            val fieldNode = FieldNode(
                Opcodes.ACC_PRIVATE,
                fieldName,
                fieldDesc,
                null,
                null
            )
            classNode.fields.add(fieldNode)
        }

        methods.forEach { (methodName, methodDesc) ->
            val methodNode = MethodNode(
                Opcodes.ACC_PUBLIC,
                methodName,
                methodDesc,
                null,
                null
            )
            // Add minimal bytecode for valid method
            methodNode.visitCode()
            methodNode.visitInsn(Opcodes.RETURN)
            methodNode.visitMaxs(1, 1)
            methodNode.visitEnd()
            classNode.methods.add(methodNode)
        }

        val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }

    /**
     * Read class bytes into ClassNode for inspection.
     */
    private fun readClass(bytes: ByteArray): ClassNode {
        val classNode = ClassNode()
        val classReader = ClassReader(bytes)
        classReader.accept(classNode, 0)
        return classNode
    }
}
