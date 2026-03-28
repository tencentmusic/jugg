package com.sickworm.intellij.jugg.compiler.obfuscation

import com.googlecode.d2j.DexConstants
import com.googlecode.d2j.DexLabel
import com.googlecode.d2j.DexType
import com.googlecode.d2j.Field
import com.googlecode.d2j.Method
import com.googlecode.d2j.MethodHandle
import com.googlecode.d2j.Proto
import com.googlecode.d2j.Visibility
import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.node.insn.ConstStmtNode
import com.googlecode.d2j.node.insn.FilledNewArrayStmtNode
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.Op
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

    // ==================== Annotation remapping tests ====================

    @Test
    fun testObfuscateMethodAnnotationType() {
        val mappingContent = """
            org.greenrobot.eventbus.Subscribe -> xxx.gkp:
            com.example.TestClass -> a.b:
                void onEvent() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX with a method that has an annotation
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "onEvent", Proto(emptyArray(), "V"))
        )

        // Add annotation to the method
        val annotationVisitor = methodVisitor.visitAnnotation(
            "Lorg/greenrobot/eventbus/Subscribe;",
            Visibility.RUNTIME
        )
        annotationVisitor.visitEnd()

        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Verify class is renamed
        assertEquals("La/b;", classNode.className)

        // Verify method annotation type is remapped
        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Method should be renamed to 'c'", method)

        val methodAnns = method!!.anns
        assertNotNull("Method should have annotations", methodAnns)
        assertEquals(1, methodAnns.size)
        assertEquals(
            "Method annotation type should be remapped to obfuscated name",
            "Lxxx/gkp;",
            methodAnns[0].type
        )
    }

    @Test
    fun testObfuscateClassAnnotationType() {
        val mappingContent = """
            com.example.MyAnnotation -> x.ann:
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX with a class-level annotation
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        // Add class-level annotation
        val annotationVisitor = classVisitor.visitAnnotation(
            "Lcom/example/MyAnnotation;",
            Visibility.RUNTIME
        )
        annotationVisitor.visitEnd()

        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Verify class is renamed
        assertEquals("La/b;", classNode.className)

        // Verify class annotation type is remapped
        val classAnns = classNode.anns
        assertNotNull("Class should have annotations", classAnns)
        assertEquals(1, classAnns.size)
        assertEquals(
            "Class annotation type should be remapped to obfuscated name",
            "Lx/ann;",
            classAnns[0].type
        )
    }

    @Test
    fun testObfuscateMethodAnnotationValue() {
        val mappingContent = """
            com.example.Referenced -> x.ref:
            com.example.MyAnnotation -> x.ann:
            com.example.TestClass -> a.b:
                void testMethod() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX with a method annotation that has a DexType value
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "testMethod", Proto(emptyArray(), "V"))
        )

        // Add annotation with a DexType value
        val annotationVisitor = methodVisitor.visitAnnotation(
            "Lcom/example/MyAnnotation;",
            Visibility.RUNTIME
        )
        annotationVisitor.visit("targetClass", DexType("Lcom/example/Referenced;"))
        annotationVisitor.visitEnd()

        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Verify method annotation type is remapped
        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Method should be renamed to 'c'", method)

        val methodAnns = method!!.anns
        assertNotNull("Method should have annotations", methodAnns)
        assertEquals(1, methodAnns.size)
        assertEquals("Lx/ann;", methodAnns[0].type)

        // Verify annotation value DexType is remapped
        val items = methodAnns[0].items
        assertNotNull("Annotation should have items", items)
        val targetClassItem = items.find { it.name == "targetClass" }
        assertNotNull("Should have 'targetClass' item", targetClassItem)
        assertTrue("Value should be DexType", targetClassItem!!.value is DexType)
        assertEquals(
            "Annotation DexType value should be remapped",
            "Lx/ref;",
            (targetClassItem.value as DexType).desc
        )
    }

    @Test
    fun testObfuscateFieldAnnotationType() {
        val mappingContent = """
            com.example.MyAnnotation -> x.ann:
            com.example.TestClass -> a.b:
                int myField -> f
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX with a field that has an annotation
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        // Add field with annotation
        val fieldVisitor = classVisitor.visitField(
            DexConstants.ACC_PRIVATE,
            Field("Lcom/example/TestClass;", "myField", "I"),
            null
        )
        val annotationVisitor = fieldVisitor.visitAnnotation(
            "Lcom/example/MyAnnotation;",
            Visibility.RUNTIME
        )
        annotationVisitor.visitEnd()
        fieldVisitor.visitEnd()

        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Verify class is renamed
        assertEquals("La/b;", classNode.className)

        // Verify field is renamed
        val field = classNode.fields.find { it.field.name == "f" }
        assertNotNull("Field should be renamed to 'f'", field)

        // Verify field annotation type is remapped
        val fieldAnns = field!!.anns
        assertNotNull("Field should have annotations", fieldAnns)
        assertEquals(1, fieldAnns.size)
        assertEquals(
            "Field annotation type should be remapped to obfuscated name",
            "Lx/ann;",
            fieldAnns[0].type
        )
    }

    // ==================== const-class / filled-new-array / try-catch remapping tests ====================

    /**
     * P0: visitConstStmt with DexType value (const-class instruction).
     * When code does `MainTabViewModel.class`, dex emits const-class with a DexType.
     * DexObfuscator must map the DexType through mapType().
     */
    @Test
    fun testObfuscateConstClassType() {
        val mappingContent = """
            com.example.ViewModel -> x.vm:
            com.example.TestClass -> a.b:
                void doOnCreate() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX with a const-class instruction referencing ViewModel
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "doOnCreate", Proto(emptyArray(), "V"))
        )

        val codeVisitor = methodVisitor.visitCode()
        // const-class v0, Lcom/example/ViewModel;
        codeVisitor.visitConstStmt(
            Op.CONST_CLASS,
            0,
            DexType("Lcom/example/ViewModel;")
        )
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        // Read the obfuscated DEX and verify the const-class type is remapped
        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        assertEquals("La/b;", classNode.className)

        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Method should be renamed to 'c'", method)

        // Find the const-class statement and verify DexType is remapped
        val codeNode = method!!.codeNode
        assertNotNull("Method should have code", codeNode)

        val constStmt = codeNode.stmts.filterIsInstance<ConstStmtNode>()
            .firstOrNull { it.value is DexType }
        assertNotNull("Should have a const-class statement with DexType", constStmt)

        val dexType = constStmt!!.value as DexType
        assertEquals(
            "const-class DexType should be remapped to obfuscated name",
            "Lx/vm;",
            dexType.desc
        )
    }

    /**
     * P0: visitConstStmt with non-DexType value should pass through unchanged.
     * e.g., const-string or const integer values.
     */
    @Test
    fun testObfuscateConstStmtNonDexTypePassThrough() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void test() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "test", Proto(emptyArray(), "V"))
        )

        val codeVisitor = methodVisitor.visitCode()
        // const-string v0, "hello"
        codeVisitor.visitConstStmt(Op.CONST_STRING, 0, "hello")
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull(method)

        val constStmt = method!!.codeNode.stmts.filterIsInstance<ConstStmtNode>()
            .firstOrNull { it.value is String }
        assertNotNull("Should have a const-string statement", constStmt)
        assertEquals("hello", constStmt!!.value)
    }

    /**
     * P1: visitFilledNewArrayStmt type descriptor must be remapped.
     * e.g., filled-new-array {v0, v1}, [Lcom/example/ViewModel;
     */
    @Test
    fun testObfuscateFilledNewArrayType() {
        val mappingContent = """
            com.example.Item -> x.it:
            com.example.TestClass -> a.b:
                void test() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "test", Proto(emptyArray(), "V"))
        )

        val codeVisitor = methodVisitor.visitCode()
        // filled-new-array {v0, v1}, [Lcom/example/Item;
        codeVisitor.visitFilledNewArrayStmt(
            Op.FILLED_NEW_ARRAY,
            intArrayOf(0, 1),
            "[Lcom/example/Item;"
        )
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        assertEquals("La/b;", classNode.className)

        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull(method)

        val filledNewArrayStmt = method!!.codeNode.stmts.filterIsInstance<FilledNewArrayStmtNode>()
            .firstOrNull()
        assertNotNull("Should have a filled-new-array statement", filledNewArrayStmt)
        assertEquals(
            "filled-new-array type should be remapped",
            "[Lx/it;",
            filledNewArrayStmt!!.type
        )
    }

    /**
     * P1: visitTryCatch exception type descriptors must be remapped.
     */
    @Test
    fun testObfuscateTryCatchExceptionType() {
        val mappingContent = """
            com.example.MyException -> x.ex:
            com.example.TestClass -> a.b:
                void test() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "test", Proto(emptyArray(), "V"))
        )

        val codeVisitor = methodVisitor.visitCode()
        val tryStart = DexLabel()
        val tryEnd = DexLabel()
        val handler = DexLabel()

        codeVisitor.visitLabel(tryStart)
        codeVisitor.visitStmt0R(Op.NOP)
        codeVisitor.visitLabel(tryEnd)
        codeVisitor.visitLabel(handler)
        codeVisitor.visitStmt0R(Op.NOP)

        // Register try-catch with exception type
        codeVisitor.visitTryCatch(
            tryStart,
            tryEnd,
            arrayOf(handler),
            arrayOf("Lcom/example/MyException;")
        )
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        // Read back and check the exception type in the try-catch node
        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        assertEquals("La/b;", classNode.className)

        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull(method)

        val tryCatchNodes = method!!.codeNode.tryStmts
        assertNotNull("Should have try-catch statements", tryCatchNodes)
        assertTrue("Should have at least one try-catch", tryCatchNodes.isNotEmpty())

        val types = tryCatchNodes[0].type
        assertNotNull("Try-catch should have exception types", types)
        assertTrue("Should have at least one exception type", types.isNotEmpty())

        // Verify exception type is remapped
        assertEquals(
            "Exception type in try-catch should be remapped",
            "Lx/ex;",
            types[0]
        )
    }

    /**
     * P0: visitTryCatch with catch-all handler (null type element) must not crash.
     * In DEX format, a catch-all handler has null as its exception type in the types array.
     * mapType() must handle null elements gracefully instead of throwing NPE.
     */
    @Test
    fun testObfuscateTryCatchWithCatchAllNullType() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void test() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "test", Proto(emptyArray(), "V"))
        )

        val codeVisitor = methodVisitor.visitCode()
        val tryStart = DexLabel()
        val tryEnd = DexLabel()
        val handler = DexLabel()

        codeVisitor.visitLabel(tryStart)
        codeVisitor.visitStmt0R(Op.NOP)
        codeVisitor.visitLabel(tryEnd)
        codeVisitor.visitLabel(handler)
        codeVisitor.visitStmt0R(Op.NOP)

        // Register try-catch with catch-all handler (null type element).
        // This is valid DEX: catch-all uses null as the exception type.
        val catchAllTypes: Array<String?> = arrayOfNulls(1)
        codeVisitor.visitTryCatch(
            tryStart,
            tryEnd,
            arrayOf(handler),
            catchAllTypes
        )
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()

        // This should NOT throw NullPointerException
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        // Read back and verify class was renamed
        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        assertEquals("La/b;", classNode.className)

        // Verify the try-catch is still present
        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull(method)

        val tryCatchNodes = method!!.codeNode.tryStmts
        assertNotNull("Should have try-catch statements", tryCatchNodes)
        assertTrue("Should have at least one try-catch", tryCatchNodes.isNotEmpty())

        // catch-all handler should preserve null type
        val types = tryCatchNodes[0].type
        assertNotNull("Should have types array", types)
        assertNull("catch-all type should remain null", types[0])
    }

    // ==================== Access flag widening tests (方案 E) ====================

    /**
     * P0: Private method should be widened to public after obfuscation.
     * R8 with -allowaccessmodification widens all private methods to public.
     * DexObfuscator must replicate this behavior to avoid IllegalAccessError
     * when ExternalSyntheticLambda classes call the widened method.
     */
    @Test
    fun testPrivateMethodWidenedToPublic() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void lambda${'$'}onResume${'$'}0() -> a
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX with a private lambda method
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PRIVATE,  // javac generates private lambda methods
            Method("Lcom/example/TestClass;", "lambda\$onResume\$0", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "a" }
        assertNotNull("Method should be renamed to 'a'", method)

        // Verify access flag: private should be widened to public
        val access = method!!.access
        assertTrue(
            "Method should have ACC_PUBLIC after widening",
            access and DexConstants.ACC_PUBLIC != 0
        )
        assertFalse(
            "Method should NOT have ACC_PRIVATE after widening",
            access and DexConstants.ACC_PRIVATE != 0
        )
    }

    /**
     * P0: Private field should be widened to public after obfuscation.
     * R8 with -allowaccessmodification widens all private fields to public.
     */
    @Test
    fun testPrivateFieldWidenedToPublic() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                int secretField -> x
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        classVisitor.visitField(
            DexConstants.ACC_PRIVATE,
            Field("Lcom/example/TestClass;", "secretField", "I"),
            null
        ).visitEnd()

        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val field = classNode.fields.find { it.field.name == "x" }
        assertNotNull("Field should be renamed to 'x'", field)

        // Verify access flag: private should be widened to public
        val access = field!!.access
        assertTrue(
            "Field should have ACC_PUBLIC after widening",
            access and DexConstants.ACC_PUBLIC != 0
        )
        assertFalse(
            "Field should NOT have ACC_PRIVATE after widening",
            access and DexConstants.ACC_PRIVATE != 0
        )
    }

    /**
     * P0: Protected method should be widened to public after obfuscation.
     * R8 with -allowaccessmodification widens protected to public.
     */
    @Test
    fun testProtectedMethodWidenedToPublic() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void protectedMethod() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PROTECTED,
            Method("Lcom/example/TestClass;", "protectedMethod", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Method should be renamed to 'c'", method)

        val access = method!!.access
        assertTrue(
            "Protected method should have ACC_PUBLIC after widening",
            access and DexConstants.ACC_PUBLIC != 0
        )
        assertFalse(
            "Protected method should NOT have ACC_PROTECTED after widening",
            access and DexConstants.ACC_PROTECTED != 0
        )
    }

    /**
     * P0: Package-private method should be widened to public after obfuscation.
     * R8 with -allowaccessmodification widens package-private to public.
     */
    @Test
    fun testPackagePrivateMethodWidenedToPublic() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void packageMethod() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        // package-private = no access modifier flags
        val methodVisitor = classVisitor.visitMethod(
            0,  // package-private
            Method("Lcom/example/TestClass;", "packageMethod", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Method should be renamed to 'c'", method)

        val access = method!!.access
        assertTrue(
            "Package-private method should have ACC_PUBLIC after widening",
            access and DexConstants.ACC_PUBLIC != 0
        )
    }

    /**
     * P1: Public method should remain public (no unnecessary modification).
     */
    @Test
    fun testPublicMethodRemainsPublic() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void publicMethod() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "publicMethod", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Method should be renamed to 'c'", method)

        val access = method!!.access
        assertTrue(
            "Public method should remain ACC_PUBLIC",
            access and DexConstants.ACC_PUBLIC != 0
        )
    }

    /**
     * P1: Private static method should be widened to public static.
     * The static flag must be preserved during widening.
     */
    @Test
    fun testPrivateStaticMethodWidenedPreservesOtherFlags() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void staticLambda() -> s
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PRIVATE or DexConstants.ACC_STATIC,
            Method("Lcom/example/TestClass;", "staticLambda", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "s" }
        assertNotNull("Method should be renamed to 's'", method)

        val access = method!!.access
        assertTrue(
            "Private static method should become public",
            access and DexConstants.ACC_PUBLIC != 0
        )
        assertFalse(
            "Private flag should be removed",
            access and DexConstants.ACC_PRIVATE != 0
        )
        assertTrue(
            "Static flag should be preserved",
            access and DexConstants.ACC_STATIC != 0
        )
    }

    /**
     * P1: Private class (inner class) access flag should be widened to public.
     */
    @Test
    fun testPrivateClassWidenedToPublic() {
        val mappingContent = """
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PRIVATE,  // inner class can be private
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        assertEquals("La/b;", classNode.className)

        val access = classNode.access
        assertTrue(
            "Private class should have ACC_PUBLIC after widening",
            access and DexConstants.ACC_PUBLIC != 0
        )
        assertFalse(
            "Private class should NOT have ACC_PRIVATE after widening",
            access and DexConstants.ACC_PRIVATE != 0
        )
    }

    /**
     * P1: Method not in mapping should still have access flags widened.
     * Even if method name stays the same (no mapping entry), the class IS
     * in the mapping, so access flags should still be widened.
     */
    @Test
    fun testUnmappedMethodInMappedClassStillWidened() {
        val mappingContent = """
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PRIVATE,
            Method("Lcom/example/TestClass;", "unmappedMethod", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        // Method name stays the same since it's not in the mapping
        val method = classNode.methods.find { it.method.name == "unmappedMethod" }
        assertNotNull("Unmapped method should still exist", method)

        val access = method!!.access
        assertTrue(
            "Unmapped method in mapped class should still be widened to public",
            access and DexConstants.ACC_PUBLIC != 0
        )
        assertFalse(
            "Unmapped method should NOT remain private",
            access and DexConstants.ACC_PRIVATE != 0
        )
    }

    // ==================== invoke-direct → invoke-virtual tests (方案 E') ====================

    /**
     * P0: When a private non-static method is widened to public, invoke-direct
     * calls within the same class must be changed to invoke-virtual.
     * Otherwise ART sees "expected direct but found virtual" → IncompatibleClassChangeError.
     */
    @Test
    fun testInvokeDirectChangedToInvokeVirtualForWidenedMethod() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void privateHelper() -> h
                void caller() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX where caller() uses invoke-direct on privateHelper()
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        // Private non-static method
        val helperVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PRIVATE,
            Method("Lcom/example/TestClass;", "privateHelper", Proto(emptyArray(), "V"))
        )
        helperVisitor.visitCode().visitEnd()
        helperVisitor.visitEnd()

        // Caller method that uses invoke-direct on privateHelper
        val callerVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "caller", Proto(emptyArray(), "V"))
        )
        val codeVisitor = callerVisitor.visitCode()
        codeVisitor.visitMethodStmt(
            Op.INVOKE_DIRECT,
            intArrayOf(0),
            Method("Lcom/example/TestClass;", "privateHelper", Proto(emptyArray(), "V"))
        )
        codeVisitor.visitEnd()
        callerVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val callerMethod = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Caller method should be renamed to 'c'", callerMethod)

        // Find the method invocation statement
        val methodStmt = callerMethod!!.codeNode.stmts
            .filterIsInstance<com.googlecode.d2j.node.insn.MethodStmtNode>()
            .firstOrNull()
        assertNotNull("Should have a method invocation statement", methodStmt)

        // Verify: invoke-direct should have been changed to invoke-virtual
        assertEquals(
            "invoke-direct should be changed to invoke-virtual for widened method",
            Op.INVOKE_VIRTUAL,
            methodStmt!!.op
        )
    }

    /**
     * P0: invoke-direct on <init> must NOT be changed to invoke-virtual.
     * Constructor calls always use invoke-direct regardless of visibility.
     */
    @Test
    fun testInvokeDirectOnInitNotChanged() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void caller() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        // Caller method that uses invoke-direct on <init>
        val callerVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "caller", Proto(emptyArray(), "V"))
        )
        val codeVisitor = callerVisitor.visitCode()
        codeVisitor.visitMethodStmt(
            Op.INVOKE_DIRECT,
            intArrayOf(0),
            Method("Lcom/example/TestClass;", "<init>", Proto(emptyArray(), "V"))
        )
        codeVisitor.visitEnd()
        callerVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val callerMethod = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Caller method should be renamed to 'c'", callerMethod)

        val methodStmt = callerMethod!!.codeNode.stmts
            .filterIsInstance<com.googlecode.d2j.node.insn.MethodStmtNode>()
            .firstOrNull()
        assertNotNull("Should have a method invocation statement", methodStmt)

        // Verify: invoke-direct on <init> must remain invoke-direct
        assertEquals(
            "invoke-direct on <init> must NOT be changed",
            Op.INVOKE_DIRECT,
            methodStmt!!.op
        )
    }

    /**
     * P0: invoke-direct on a method of a DIFFERENT class must NOT be changed.
     * We only modify invoke-direct for methods owned by the current class.
     */
    @Test
    fun testInvokeDirectOnOtherClassNotChanged() {
        val mappingContent = """
            com.example.OtherClass -> x.y:
                void otherMethod() -> m
            com.example.TestClass -> a.b:
                void caller() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        // Caller method that uses invoke-direct on OtherClass.otherMethod
        val callerVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "caller", Proto(emptyArray(), "V"))
        )
        val codeVisitor = callerVisitor.visitCode()
        codeVisitor.visitMethodStmt(
            Op.INVOKE_DIRECT,
            intArrayOf(0),
            Method("Lcom/example/OtherClass;", "otherMethod", Proto(emptyArray(), "V"))
        )
        codeVisitor.visitEnd()
        callerVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val callerMethod = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Caller method should be renamed to 'c'", callerMethod)

        val methodStmt = callerMethod!!.codeNode.stmts
            .filterIsInstance<com.googlecode.d2j.node.insn.MethodStmtNode>()
            .firstOrNull()
        assertNotNull("Should have a method invocation statement", methodStmt)

        // Verify: invoke-direct on other class method must remain unchanged
        assertEquals(
            "invoke-direct on other class must NOT be changed",
            Op.INVOKE_DIRECT,
            methodStmt!!.op
        )
    }

    /**
     * P0: invoke-static must NOT be changed even after widening.
     * private static → public static still uses invoke-static in direct section.
     */
    @Test
    fun testInvokeStaticNotChanged() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void staticHelper() -> s
                void caller() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        // Private static method
        val staticVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PRIVATE or DexConstants.ACC_STATIC,
            Method("Lcom/example/TestClass;", "staticHelper", Proto(emptyArray(), "V"))
        )
        staticVisitor.visitCode().visitEnd()
        staticVisitor.visitEnd()

        // Caller uses invoke-static
        val callerVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "caller", Proto(emptyArray(), "V"))
        )
        val codeVisitor = callerVisitor.visitCode()
        codeVisitor.visitMethodStmt(
            Op.INVOKE_STATIC,
            intArrayOf(),
            Method("Lcom/example/TestClass;", "staticHelper", Proto(emptyArray(), "V"))
        )
        codeVisitor.visitEnd()
        callerVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val callerMethod = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Caller method should be renamed to 'c'", callerMethod)

        val methodStmt = callerMethod!!.codeNode.stmts
            .filterIsInstance<com.googlecode.d2j.node.insn.MethodStmtNode>()
            .firstOrNull()
        assertNotNull("Should have a method invocation statement", methodStmt)

        // Verify: invoke-static must remain invoke-static
        assertEquals(
            "invoke-static must NOT be changed",
            Op.INVOKE_STATIC,
            methodStmt!!.op
        )
    }

    /**
     * P1: invoke-virtual must NOT be changed.
     * Already virtual calls should pass through unchanged.
     */
    @Test
    fun testInvokeVirtualNotChanged() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void publicMethod() -> p
                void caller() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val callerVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/TestClass;", "caller", Proto(emptyArray(), "V"))
        )
        val codeVisitor = callerVisitor.visitCode()
        codeVisitor.visitMethodStmt(
            Op.INVOKE_VIRTUAL,
            intArrayOf(0),
            Method("Lcom/example/TestClass;", "publicMethod", Proto(emptyArray(), "V"))
        )
        codeVisitor.visitEnd()
        callerVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val callerMethod = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Caller method should be renamed to 'c'", callerMethod)

        val methodStmt = callerMethod!!.codeNode.stmts
            .filterIsInstance<com.googlecode.d2j.node.insn.MethodStmtNode>()
            .firstOrNull()
        assertNotNull("Should have a method invocation statement", methodStmt)

        // Verify: invoke-virtual must remain invoke-virtual
        assertEquals(
            "invoke-virtual must remain unchanged",
            Op.INVOKE_VIRTUAL,
            methodStmt!!.op
        )
    }

    // ==================== Plan L: interface/superclass-first method name mapping tests ====================

    /**
     * Plan L core scenario: class NOT in mapping, but implements an interface that IS in mapping.
     * The method name should be derived from the interface mapping.
     *
     * Scenario: NewImpl implements IFoo (IFoo.run -> a in mapping),
     * but NewImpl itself has no mapping entry.
     * Expected: NewImpl.run should be obfuscated to "a" (from IFoo).
     */
    @Test
    fun testMethodNameFromInterface_classNotInMapping() {
        val mappingContent = """
            com.example.IFoo -> com.example.IFoo:
                void run() -> a
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a DEX for NewImpl which implements IFoo but is NOT in the mapping
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/NewImpl;",
            "Ljava/lang/Object;",
            arrayOf("Lcom/example/IFoo;")
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/NewImpl;", "run", Proto(emptyArray(), "V"))
        )
        methodVisitor.visitCode().visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscate(dexWriter.toByteArray())
        assertNotNull("Should obfuscate (method name changed)", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Class name stays the same (not in mapping)
        assertEquals("Lcom/example/NewImpl;", classNode.className)

        // Method name should be derived from interface: run -> a
        val method = classNode.methods.find { it.method.name == "a" }
        assertNotNull("Method 'run' should be renamed to 'a' from interface IFoo mapping", method)
    }

    /**
     * Plan L: class IS in mapping AND implements an interface in mapping.
     * Interface-first should produce the same result as class-own lookup (R8 guarantees consistency).
     */
    @Test
    fun testMethodNameFromInterface_classInMapping() {
        val mappingContent = """
            com.example.IFoo -> x.IFoo:
                void run() -> a
            com.example.MyImpl -> x.MyImpl:
                void run() -> a
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/MyImpl;",
            "Ljava/lang/Object;",
            arrayOf("Lcom/example/IFoo;")
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/MyImpl;", "run", Proto(emptyArray(), "V"))
        )
        methodVisitor.visitCode().visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscate(dexWriter.toByteArray())
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        assertEquals("Lx/MyImpl;", classNode.className)

        // Method should be renamed to 'a' (consistent from both interface and class mappings)
        val method = classNode.methods.find { it.method.name == "a" }
        assertNotNull("Method 'run' should be renamed to 'a'", method)
    }

    /**
     * Plan L: class NOT in mapping, but extends a superclass that IS in mapping.
     * The method name should be derived from the superclass mapping.
     */
    @Test
    fun testMethodNameFromSuperClass_classNotInMapping() {
        val mappingContent = """
            com.example.BaseClass -> x.Base:
                void doWork() -> b
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/SubClass;",
            "Lcom/example/BaseClass;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/SubClass;", "doWork", Proto(emptyArray(), "V"))
        )
        methodVisitor.visitCode().visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscate(dexWriter.toByteArray())
        assertNotNull("Should obfuscate (method name changed via superclass)", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Class name stays the same (not in mapping)
        assertEquals("Lcom/example/SubClass;", classNode.className)
        // Superclass should be mapped
        assertEquals("Lx/Base;", classNode.superClass)

        // Method should be derived from superclass: doWork -> b
        val method = classNode.methods.find { it.method.name == "b" }
        assertNotNull("Method 'doWork' should be renamed to 'b' from superclass mapping", method)
    }

    /**
     * Plan L: interface NOT in mapping, but class IS in mapping.
     * Should fall back to class-own mapping.
     */
    @Test
    fun testMethodNameFromHierarchy_interfaceNotInMapping_fallbackToClass() {
        val mappingContent = """
            com.example.MyClass -> x.MC:
                void execute() -> e
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // MyClass implements UnmappedInterface (not in mapping)
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/MyClass;",
            "Ljava/lang/Object;",
            arrayOf("Lcom/example/UnmappedInterface;")
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/MyClass;", "execute", Proto(emptyArray(), "V"))
        )
        methodVisitor.visitCode().visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscate(dexWriter.toByteArray())
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        assertEquals("Lx/MC;", classNode.className)

        // Interface not in mapping -> fall back to class mapping: execute -> e
        val method = classNode.methods.find { it.method.name == "e" }
        assertNotNull("Method 'execute' should be renamed to 'e' from class mapping", method)
    }

    /**
     * Plan L: neither interface, superclass, nor class is in mapping.
     * Method name should be kept as original.
     */
    @Test
    fun testMethodNameFromHierarchy_neitherInMapping_keepOriginal() {
        val mappingContent = """
            com.example.Unrelated -> x.y:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/Unknown;",
            "Lcom/example/UnknownBase;",
            arrayOf("Lcom/example/UnknownIface;")
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/Unknown;", "myMethod", Proto(emptyArray(), "V"))
        )
        methodVisitor.visitCode().visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscate(dexWriter.toByteArray())

        // Nothing was in mapping for this class -> no remapping -> null
        assertNull("Should return null when no remapping applied", obfuscatedBytes)
    }

    /**
     * Plan L: class implements multiple interfaces, method is found in second interface.
     */
    @Test
    fun testMethodNameFromHierarchy_multipleInterfaces() {
        val mappingContent = """
            com.example.IFirst -> x.IF:
                void firstMethod() -> f
            com.example.ISecond -> x.IS:
                void secondMethod() -> s
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/MultiImpl;",
            "Ljava/lang/Object;",
            arrayOf("Lcom/example/IFirst;", "Lcom/example/ISecond;")
        )

        // Method from IFirst
        val mv1 = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/MultiImpl;", "firstMethod", Proto(emptyArray(), "V"))
        )
        mv1.visitCode().visitEnd()
        mv1.visitEnd()

        // Method from ISecond
        val mv2 = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/MultiImpl;", "secondMethod", Proto(emptyArray(), "V"))
        )
        mv2.visitCode().visitEnd()
        mv2.visitEnd()

        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscate(dexWriter.toByteArray())
        assertNotNull("Should obfuscate (method names changed via interfaces)", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Both methods should be renamed from their respective interfaces
        val m1 = classNode.methods.find { it.method.name == "f" }
        assertNotNull("firstMethod should be renamed to 'f' from IFirst", m1)

        val m2 = classNode.methods.find { it.method.name == "s" }
        assertNotNull("secondMethod should be renamed to 's' from ISecond", m2)
    }

    /**
     * Plan L: generic interface with erased signature.
     * mapping.txt records erased signatures, DEX proto is also erased.
     * They should match precisely.
     */
    @Test
    fun testGenericInterface_erasedSignatureMatch() {
        // Generic interface Function<T,R> has erased method: Object apply(Object)
        val mappingContent = """
            com.example.Function -> x.Fn:
                java.lang.Object apply(java.lang.Object) -> a
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Lambda class implements Function, method proto is erased: (Object)Object
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/Lambda0;",
            "Ljava/lang/Object;",
            arrayOf("Lcom/example/Function;")
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method(
                "Lcom/example/Lambda0;",
                "apply",
                Proto(arrayOf("Ljava/lang/Object;"), "Ljava/lang/Object;")
            )
        )
        methodVisitor.visitCode().visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscate(dexWriter.toByteArray())
        assertNotNull("Should obfuscate (method name derived from generic interface)", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Method should be renamed from interface: apply -> a
        val method = classNode.methods.find { it.method.name == "a" }
        assertNotNull("apply(Object) should be renamed to 'a' from generic interface mapping", method)
    }

    /**
     * Plan L: a method that is NOT an override (only exists in the class itself)
     * should still use the class's own mapping.
     */
    @Test
    fun testNonOverrideMethod_usesClassOwnMapping() {
        val mappingContent = """
            com.example.IFoo -> x.IFoo:
                void interfaceMethod() -> a
            com.example.MyClass -> x.MC:
                void interfaceMethod() -> a
                void ownMethod() -> b
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/MyClass;",
            "Ljava/lang/Object;",
            arrayOf("Lcom/example/IFoo;")
        )

        // Interface method
        val mv1 = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/MyClass;", "interfaceMethod", Proto(emptyArray(), "V"))
        )
        mv1.visitCode().visitEnd()
        mv1.visitEnd()

        // Own method (not from interface)
        val mv2 = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/MyClass;", "ownMethod", Proto(emptyArray(), "V"))
        )
        mv2.visitCode().visitEnd()
        mv2.visitEnd()

        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscate(dexWriter.toByteArray())
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // interfaceMethod -> a (from interface or class, both say 'a')
        val m1 = classNode.methods.find { it.method.name == "a" }
        assertNotNull("interfaceMethod should be renamed to 'a'", m1)

        // ownMethod -> b (only from class mapping, interface doesn't have it)
        val m2 = classNode.methods.find { it.method.name == "b" }
        assertNotNull("ownMethod should be renamed to 'b' from class mapping", m2)
    }

    /**
     * Plan L regression: visitMethodStmt (method call instructions) should NOT be affected
     * by hierarchy logic. Only visitMethod (class-declared methods) uses hierarchy-first resolution.
     * Method call instructions should still use the callee's owner to look up the mapping.
     */
    @Test
    fun testMethodStmt_unaffectedByHierarchyLogic() {
        val mappingContent = """
            com.example.IFoo -> x.IFoo:
                void run() -> a
            com.example.Caller -> x.Caller:
                void callIt() -> c
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/Caller;",
            "Ljava/lang/Object;",
            null  // Caller does NOT implement IFoo
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/Caller;", "callIt", Proto(emptyArray(), "V"))
        )

        val codeVisitor = methodVisitor.visitCode()
        // invoke-interface IFoo.run()
        codeVisitor.visitMethodStmt(
            Op.INVOKE_INTERFACE,
            intArrayOf(0),
            Method("Lcom/example/IFoo;", "run", Proto(emptyArray(), "V"))
        )
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscate(dexWriter.toByteArray())
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        val callerMethod = classNode.methods.find { it.method.name == "c" }
        assertNotNull("callIt should be renamed to 'c'", callerMethod)

        // The invoke-interface should have IFoo.run mapped to x.IFoo.a via normal mapMethod
        val methodStmt = callerMethod!!.codeNode.stmts
            .filterIsInstance<com.googlecode.d2j.node.insn.MethodStmtNode>()
            .firstOrNull()
        assertNotNull(methodStmt)
        assertEquals("Lx/IFoo;", methodStmt!!.method.owner)
        assertEquals("a", methodStmt.method.name)
    }

    /**
     * Plan L: ExternalSyntheticLambda scenario (the original motivating case).
     * Lambda class not in mapping, but implements a functional interface that IS in mapping.
     * Method name should be derived from the interface.
     */
    @Test
    fun testExternalSyntheticLambda_methodNameFromInterface() {
        val mappingContent = """
            com.example.MyCallback -> x.CB:
                void onCallback(java.lang.String) -> a
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // ExternalSyntheticLambda class: not in mapping, implements MyCallback
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/Activity\$\$ExternalSyntheticLambda0;",
            "Ljava/lang/Object;",
            arrayOf("Lcom/example/MyCallback;")
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method(
                "Lcom/example/Activity\$\$ExternalSyntheticLambda0;",
                "onCallback",
                Proto(arrayOf("Ljava/lang/String;"), "V")
            )
        )
        methodVisitor.visitCode().visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscate(dexWriter.toByteArray())
        assertNotNull("Should obfuscate lambda method name via interface", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Lambda class name not in mapping -> stays the same
        assertEquals("Lcom/example/Activity\$\$ExternalSyntheticLambda0;", classNode.className)

        // Method name should be derived from interface: onCallback -> a
        val method = classNode.methods.find { it.method.name == "a" }
        assertNotNull("Lambda's onCallback should be renamed to 'a' from interface mapping", method)
    }

    /**
     * Plan L: method with parameters - class not in mapping, interface IS in mapping.
     * Verifies parameter matching works correctly for hierarchy lookup.
     */
    @Test
    fun testMethodNameFromInterface_withParameters() {
        val mappingContent = """
            com.example.IProcessor -> x.IP:
                java.lang.String process(java.lang.String,int) -> p
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/ProcessorImpl;",
            "Ljava/lang/Object;",
            arrayOf("Lcom/example/IProcessor;")
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method(
                "Lcom/example/ProcessorImpl;",
                "process",
                Proto(arrayOf("Ljava/lang/String;", "I"), "Ljava/lang/String;")
            )
        )
        methodVisitor.visitCode().visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscate(dexWriter.toByteArray())
        assertNotNull("Should obfuscate (method name derived via interface)", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        val method = classNode.methods.find { it.method.name == "p" }
        assertNotNull("process(String,int) should be renamed to 'p' from interface", method)
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
