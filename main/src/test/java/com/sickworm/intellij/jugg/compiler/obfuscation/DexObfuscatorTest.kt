package com.sickworm.intellij.jugg.compiler.obfuscation

import com.googlecode.d2j.DexConstants
import com.googlecode.d2j.Field
import com.googlecode.d2j.Method
import com.googlecode.d2j.Proto
import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.DexFileReader
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Unit tests for DexObfuscator.
 *
 * Tests the dex-reader/dex-writer based DEX obfuscation functionality.
 */
class DexObfuscatorTest {

    companion object {
        private lateinit var tempDir: File
        @JvmStatic
        @BeforeClass
        fun setup() {
            tempDir = File(System.getProperty("java.io.tmpdir"), "DexObfuscatorTest")
        }
    }

    @Before
    fun setupTest() {
        tempDir.deleteRecursively()
        tempDir.mkdirs()
    }

    // ==================== Basic obfuscation tests ====================

    @Test
    fun testObfuscateDexFromMappingString() {
        val mappingContent = """
            com.example.OriginalClass -> a.b:
                java.lang.String originalField -> a
                int count -> b
                1:10:void originalMethod():0:0 -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

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
    fun testObfuscateDexBytes() {
        val mappingContent = """
            com.example.TestClass -> a.b.c:
                java.lang.String name -> a
                void testMethod() -> b
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a simple DEX file
        val originalDex = createTestDex(
            className = "Lcom/example/TestClass;",
            fields = listOf("name" to "Ljava/lang/String;"),
            methods = listOf("testMethod" to Proto(emptyArray(), "V"))
        )

        // Obfuscate
        val obfuscatedBytes = obfuscator.obfuscate(originalDex)
        assertNotNull("Should have obfuscated the DEX", obfuscatedBytes)

        // Verify the obfuscated DEX
        val dexNode = readDex(obfuscatedBytes!!)
        assertEquals(1, dexNode.clzs.size)
        
        val classNode = dexNode.clzs[0]
        assertEquals("La/b/c;", classNode.className)

        // Verify field was renamed
        val field = classNode.fields.find { it.field.name == "a" }
        assertNotNull("Field should be renamed to 'a'", field)

        // Verify method was renamed
        val method = classNode.methods.find { it.method.name == "b" }
        assertNotNull("Method should be renamed to 'b'", method)
    }

    @Test
    fun testObfuscateDexWithNoMapping() {
        val mappingContent = """
            com.example.OtherClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX that's NOT in the mapping
        val originalDex = createTestDex(
            className = "Lcom/example/NotInMapping;",
            fields = listOf("field1" to "I"),
            methods = listOf("method1" to Proto(emptyArray(), "V"))
        )

        // Obfuscate - should return null since class is not in mapping
        val obfuscatedBytes = obfuscator.obfuscate(originalDex)

        // Since class is not in mapping, no remapping should occur
        assertNull("Should return null when no remapping applied", obfuscatedBytes)
    }

    @Test
    fun testObfuscateDexPath() {
        val mappingContent = """
            com.example.deep.nested.MyClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val inputFile = File(tempDir, "classes.dex")
        inputFile.writeBytes(ByteArray(0)) // Create empty file for testing

        val obfuscatedPath = obfuscator.getObfuscatedDexPath(inputFile, tempDir)
        // For DEX files, we typically keep the same path
        assertEquals("classes.dex", obfuscatedPath)
    }

    // ==================== Method descriptor tests ====================

    @Test
    fun testObfuscateMethodWithParameters() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void methodWithParams(java.lang.String,int) -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val originalDex = createTestDex(
            className = "Lcom/example/TestClass;",
            fields = emptyList(),
            methods = listOf("methodWithParams" to Proto(arrayOf("Ljava/lang/String;", "I"), "V"))
        )

        val obfuscatedBytes = obfuscator.obfuscate(originalDex)
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Method with params should be renamed to 'c'", method)
    }

    @Test
    fun testObfuscateMethodWithArrayParameters() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void methodWithArray(int[],java.lang.String[]) -> d
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val originalDex = createTestDex(
            className = "Lcom/example/TestClass;",
            fields = emptyList(),
            methods = listOf("methodWithArray" to Proto(arrayOf("[I", "[Ljava/lang/String;"), "V"))
        )

        val obfuscatedBytes = obfuscator.obfuscate(originalDex)
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "d" }
        assertNotNull("Method with array params should be renamed to 'd'", method)
    }

    // ==================== Reference remapping tests ====================

    @Test
    fun testObfuscateClassReference() {
        val mappingContent = """
            com.example.Referenced -> x.y:
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX with a field referencing another class
        val originalDex = createTestDex(
            className = "Lcom/example/TestClass;",
            fields = listOf("ref" to "Lcom/example/Referenced;"),
            methods = emptyList()
        )

        val obfuscatedBytes = obfuscator.obfuscate(originalDex)
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Class should be renamed
        assertEquals("La/b;", classNode.className)

        // Field type should also be remapped
        val field = classNode.fields[0]
        assertEquals("Lx/y;", field.field.type)
    }

    // ==================== File obfuscation tests ====================

    @Test
    fun testObfuscateFile() {
        val mappingContent = """
            com.example.FileTest -> a.b:
                int value -> x
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create input DEX file
        val inputDir = File(tempDir, "input")
        inputDir.mkdirs()
        val inputFile = File(inputDir, "classes.dex")

        val dexBytes = createTestDex(
            className = "Lcom/example/FileTest;",
            fields = listOf("value" to "I"),
            methods = emptyList()
        )
        inputFile.writeBytes(dexBytes)

        // Obfuscate to output
        val outputDir = File(tempDir, "output")
        val outputFile = File(outputDir, "classes.dex")

        val success = obfuscator.obfuscate(inputFile, outputFile)
        assertTrue("Obfuscation should succeed", success)
        assertTrue("Output file should exist", outputFile.exists())

        // Verify content
        val dexNode = readDex(outputFile.readBytes())
        val classNode = dexNode.clzs[0]
        assertEquals("La/b;", classNode.className)

        val field = classNode.fields.find { it.field.name == "x" }
        assertNotNull("Field should be renamed to 'x'", field)
    }

    // ==================== Superclass and interface tests ====================

    @Test
    fun testObfuscateSuperclass() {
        val mappingContent = """
            com.example.BaseClass -> x.y.z:
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX that extends BaseClass
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Lcom/example/BaseClass;",
            null
        )
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Class should be renamed
        assertEquals("La/b;", classNode.className)

        // Superclass should also be remapped
        assertEquals("Lx/y/z;", classNode.superClass)
    }

    @Test
    fun testObfuscateInterfaces() {
        val mappingContent = """
            com.example.InterfaceA -> i.a:
            com.example.InterfaceB -> i.b:
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX that implements multiple interfaces
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            arrayOf("Lcom/example/InterfaceA;", "Lcom/example/InterfaceB;")
        )
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Class should be renamed
        assertEquals("La/b;", classNode.className)

        // Both interfaces should be remapped
        assertEquals(2, classNode.interfaceNames.size)
        assertTrue("InterfaceA should be remapped", classNode.interfaceNames.contains("Li/a;"))
        assertTrue("InterfaceB should be remapped", classNode.interfaceNames.contains("Li/b;"))
    }

    @Test
    fun testObfuscateSuperclassNotInMapping() {
        val mappingContent = """
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX that extends a class not in the mapping
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Lcom/example/NotInMapping;",
            null
        )
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Class should be renamed
        assertEquals("La/b;", classNode.className)

        // Superclass should NOT be remapped (not in mapping)
        assertEquals("Lcom/example/NotInMapping;", classNode.superClass)
    }

    @Test
    fun testObfuscateMixedInterfaces() {
        val mappingContent = """
            com.example.InterfaceA -> i.a:
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX with one interface in mapping and one not
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            arrayOf("Lcom/example/InterfaceA;", "Lcom/example/NotInMapping;")
        )
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Class should be renamed
        assertEquals("La/b;", classNode.className)

        // InterfaceA should be remapped, NotInMapping should stay the same
        assertEquals(2, classNode.interfaceNames.size)
        assertTrue("InterfaceA should be remapped", classNode.interfaceNames.contains("Li/a;"))
        assertTrue("NotInMapping should stay the same", classNode.interfaceNames.contains("Lcom/example/NotInMapping;"))
    }

    // ==================== Special methods tests ====================

    @Test
    fun testConstructorNotRenamed() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void normalMethod() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val originalDex = createTestDex(
            className = "Lcom/example/TestClass;",
            fields = emptyList(),
            methods = listOf(
                "<init>" to Proto(emptyArray(), "V"),
                "normalMethod" to Proto(emptyArray(), "V")
            )
        )

        val obfuscatedBytes = obfuscator.obfuscate(originalDex)
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Constructor should NOT be renamed
        val constructor = classNode.methods.find { it.method.name == "<init>" }
        assertNotNull("Constructor should keep original name", constructor)

        // Normal method should be renamed
        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Normal method should be renamed", method)
    }

    @Test
    fun testStaticInitializerNotRenamed() {
        val mappingContent = """
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val originalDex = createTestDex(
            className = "Lcom/example/TestClass;",
            fields = emptyList(),
            methods = listOf("<clinit>" to Proto(emptyArray(), "V"))
        )

        val obfuscatedBytes = obfuscator.obfuscate(originalDex)
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val staticInit = classNode.methods.find { it.method.name == "<clinit>" }
        assertNotNull("Static initializer should keep original name", staticInit)
    }

    // ==================== Method invocation tests ====================

    @Test
    fun testObfuscateMethodInvocation() {
        val mappingContent = """
            com.example.CalledClass -> x.y:
                void calledMethod() -> a
            com.example.CallerClass -> a.b:
                void callerMethod() -> b
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX with method invocation
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/CallerClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/CallerClass;", "callerMethod", Proto(emptyArray(), "V"))
        )

        val codeVisitor = methodVisitor.visitCode()
        // Simulate a method call
        codeVisitor.visitMethodStmt(
            com.googlecode.d2j.reader.Op.INVOKE_VIRTUAL,
            intArrayOf(0),
            Method("Lcom/example/CalledClass;", "calledMethod", Proto(emptyArray(), "V"))
        )
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Verify class is renamed
        assertEquals("La/b;", classNode.className)

        // Verify method is renamed
        val method = classNode.methods.find { it.method.name == "b" }
        assertNotNull("Caller method should be renamed", method)
    }

    // ==================== Field access tests ====================

    @Test
    fun testObfuscateFieldAccess() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                int myField -> x
                void accessField() -> m
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX with field access
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        // Add field
        classVisitor.visitField(
            DexConstants.ACC_PRIVATE,
            Field("Lcom/example/TestClass;", "myField", "I"),
            null
        ).visitEnd()

        // Add method that accesses the field
        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "accessField", Proto(emptyArray(), "V"))
        )

        val codeVisitor = methodVisitor.visitCode()
        // Simulate field access
        codeVisitor.visitFieldStmt(
            com.googlecode.d2j.reader.Op.IGET,
            0,
            1,
            Field("Lcom/example/TestClass;", "myField", "I")
        )
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Verify class is renamed
        assertEquals("La/b;", classNode.className)

        // Verify field is renamed
        val field = classNode.fields.find { it.field.name == "x" }
        assertNotNull("Field should be renamed to 'x'", field)

        // Verify method is renamed
        val method = classNode.methods.find { it.method.name == "m" }
        assertNotNull("Method should be renamed to 'm'", method)
    }

    // ==================== Cache tests ====================

    @Test
    fun testObfuscatorCaching() {
        val mappingFile = File(tempDir, "mapping.txt")
        mappingFile.writeText("""
            com.example.TestClass -> a.b:
        """.trimIndent())

        val obfuscator1 = DexObfuscator.fromMappingFile(mappingFile)
        val obfuscator2 = DexObfuscator.fromMappingFile(mappingFile)

        // Should return the same cached instance
        assertSame("Should return cached obfuscator", obfuscator1, obfuscator2)

        // Modify the file
        Thread.sleep(10) // Ensure different timestamp
        mappingFile.writeText("""
            com.example.TestClass -> x.y:
        """.trimIndent())

        val obfuscator3 = DexObfuscator.fromMappingFile(mappingFile)

        // Should return a new instance
        assertNotSame("Should return new obfuscator after file change", obfuscator1, obfuscator3)
    }

    // ==================== Helper methods ====================

    /**
     * Create a simple test DEX file.
     */
    private fun createTestDex(
        className: String,
        fields: List<Pair<String, String>>,
        methods: List<Pair<String, Proto>>
    ): ByteArray {
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            className,
            "Ljava/lang/Object;",
            null
        )

        // Add fields
        fields.forEach { (fieldName, fieldType) ->
            classVisitor.visitField(
                DexConstants.ACC_PRIVATE,
                Field(className, fieldName, fieldType),
                null
            ).visitEnd()
        }

        // Add methods
        methods.forEach { (methodName, proto) ->
            val methodVisitor = classVisitor.visitMethod(
                DexConstants.ACC_PUBLIC,
                Method(className, methodName, proto)
            )
            val codeVisitor = methodVisitor.visitCode()
            // Add minimal code
            codeVisitor.visitEnd()
            methodVisitor.visitEnd()
        }

        classVisitor.visitEnd()
        return dexWriter.toByteArray()
    }

    /**
     * Read DEX bytes into DexFileNode for inspection.
     */
    private fun readDex(bytes: ByteArray): DexFileNode {
        val dexReader = DexFileReader(bytes)
        val dexNode = DexFileNode()
        dexReader.accept(dexNode)
        return dexNode
    }
}
