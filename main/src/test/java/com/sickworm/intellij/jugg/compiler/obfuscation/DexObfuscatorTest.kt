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
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode
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

    // ==================== Access flag alignment tests (方案 D: APK DB access flags) ====================

    /**
     * Helper: build an APK ClassNode map keyed by obfuscated DEX class name.
     * Simulates the data from DeployDataDatabase.getClassNodes().
     */
    private fun buildApkClassNodes(vararg entries: ClassNode): Map<String, ClassNode> {
        return entries.associateBy { it.className }
    }

    /**
     * P0: When APK says method is public, DexObfuscator should align private -> public.
     * This is the core IllegalAccessError fix: R8 widened a lambda method to public,
     * so the incremental DEX must also be public.
     */
    @Test
    fun testMethodAccessAlignedToApkPublic() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void lambda${'$'}onResume${'$'}0() -> a
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // APK data: after R8, the method is public in APK
        val apkClassNodes = buildApkClassNodes(
            ClassNode("apk.dex", "La/b;", DexConstants.ACC_PUBLIC,
                methods = listOf(MethodNode("La/b;", DexConstants.ACC_PUBLIC, "a", "()V")),
                fields = emptyList(),
                interfaceNames = emptyList(), superClass = "Ljava/lang/Object;", sourceArg = null)
        )

        // Create a DEX with a private lambda method (javac output)
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
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes, apkClassNodes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "a" }
        assertNotNull("Method should be renamed to 'a'", method)

        // Verify: aligned to APK's public
        val access = method!!.access
        assertTrue(
            "Method should have ACC_PUBLIC (aligned to APK)",
            access and DexConstants.ACC_PUBLIC != 0
        )
        assertFalse(
            "Method should NOT have ACC_PRIVATE (aligned to APK)",
            access and DexConstants.ACC_PRIVATE != 0
        )
    }

    /**
     * P0: When APK says method is private (R8 chose NOT to widen), keep private.
     * This prevents IncompatibleClassChangeError (方案 E's fatal flaw).
     */
    @Test
    fun testMethodAccessAlignedToApkPrivate() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void syntheticMethod() -> s
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // APK data: R8 kept this method private (one of the §6.3 exceptions)
        val apkClassNodes = buildApkClassNodes(
            ClassNode("apk.dex", "La/b;", DexConstants.ACC_PUBLIC,
                methods = listOf(MethodNode("La/b;", DexConstants.ACC_PRIVATE, "s", "()V")),
                fields = emptyList(),
                interfaceNames = emptyList(), superClass = "Ljava/lang/Object;", sourceArg = null)
        )

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PRIVATE,
            Method("Lcom/example/TestClass;", "syntheticMethod", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes, apkClassNodes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "s" }
        assertNotNull("Method should be renamed to 's'", method)

        // Verify: aligned to APK's private (NOT widened, unlike 方案 E)
        val access = method!!.access
        assertTrue(
            "Method should stay ACC_PRIVATE (aligned to APK)",
            access and DexConstants.ACC_PRIVATE != 0
        )
        assertFalse(
            "Method should NOT have ACC_PUBLIC (APK says private)",
            access and DexConstants.ACC_PUBLIC != 0
        )
    }

    /**
     * P0: Field access flag should be aligned to APK data.
     * R8 widens private fields to public; DexObfuscator must match.
     */
    @Test
    fun testFieldAccessAlignedToApk() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                int secretField -> x
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // APK data: R8 widened this field to public
        val apkClassNodes = buildApkClassNodes(
            ClassNode("apk.dex", "La/b;", DexConstants.ACC_PUBLIC,
                methods = emptyList(),
                fields = listOf(FieldNode("La/b;", DexConstants.ACC_PUBLIC, "x", "I")),
                interfaceNames = emptyList(), superClass = "Ljava/lang/Object;", sourceArg = null)
        )

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
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes, apkClassNodes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val field = classNode.fields.find { it.field.name == "x" }
        assertNotNull("Field should be renamed to 'x'", field)

        // Verify: aligned to APK's public
        val access = field!!.access
        assertTrue(
            "Field should have ACC_PUBLIC (aligned to APK)",
            access and DexConstants.ACC_PUBLIC != 0
        )
        assertFalse(
            "Field should NOT have ACC_PRIVATE (aligned to APK)",
            access and DexConstants.ACC_PRIVATE != 0
        )
    }

    /**
     * P0: Class access flag should be aligned to APK data.
     * R8 may widen private inner classes to public.
     */
    @Test
    fun testClassAccessAlignedToApk() {
        val mappingContent = """
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // APK data: R8 widened this inner class to public
        val apkClassNodes = buildApkClassNodes(
            ClassNode("apk.dex", "La/b;", DexConstants.ACC_PUBLIC,
                methods = emptyList(), fields = emptyList(),
                interfaceNames = emptyList(), superClass = "Ljava/lang/Object;", sourceArg = null)
        )

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PRIVATE,  // inner class was private
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes, apkClassNodes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        assertEquals("La/b;", classNode.className)

        // Verify: aligned to APK's public
        val access = classNode.access
        assertTrue(
            "Class should have ACC_PUBLIC (aligned to APK)",
            access and DexConstants.ACC_PUBLIC != 0
        )
        assertFalse(
            "Class should NOT have ACC_PRIVATE (aligned to APK)",
            access and DexConstants.ACC_PRIVATE != 0
        )
    }

    /**
     * P0: New method not in APK should preserve original access flags.
     * When a method is added in incremental compilation that doesn't exist
     * in the APK, we keep the original access flags (no alignment needed).
     */
    @Test
    fun testNewMethodNotInApkPreservesOriginalAccess() {
        val mappingContent = """
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // APK data: class exists but method "newMethod" is NOT in it
        val apkClassNodes = buildApkClassNodes(
            ClassNode("apk.dex", "La/b;", DexConstants.ACC_PUBLIC,
                methods = listOf(MethodNode("La/b;", DexConstants.ACC_PUBLIC, "existingMethod", "()V")),
                fields = emptyList(),
                interfaceNames = emptyList(), superClass = "Ljava/lang/Object;", sourceArg = null)
        )

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PRIVATE,
            Method("Lcom/example/TestClass;", "newMethod", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes, apkClassNodes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "newMethod" }
        assertNotNull("New method should exist with original name", method)

        // Verify: original private access preserved (no APK data to align to)
        val access = method!!.access
        assertTrue(
            "New method should keep ACC_PRIVATE (not in APK)",
            access and DexConstants.ACC_PRIVATE != 0
        )
        assertFalse(
            "New method should NOT have ACC_PUBLIC (not in APK)",
            access and DexConstants.ACC_PUBLIC != 0
        )
    }

    /**
     * P0: Class not in APK data should preserve original access flags.
     * When apkClassNodes is provided but does not contain this class,
     * keep the original access flags.
     */
    @Test
    fun testClassNotInApkDataPreservesOriginalAccess() {
        val mappingContent = """
            com.example.TestClass -> a.b:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // APK data is empty (class not found in DB)
        val apkClassNodes = emptyMap<String, ClassNode>()

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PRIVATE,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes, apkClassNodes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Verify: original access preserved since class is not in APK data
        val access = classNode.access
        assertTrue(
            "Class should keep ACC_PRIVATE (not in APK data)",
            access and DexConstants.ACC_PRIVATE != 0
        )
    }

    /**
     * P0: Without APK data (null), access flags should pass through unchanged.
     * This preserves backward compatibility for non-release builds.
     */
    @Test
    fun testNoApkDataPassesThroughAccessFlags() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void privateMethod() -> c
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
            Method("Lcom/example/TestClass;", "privateMethod", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        // No APK data: use the existing obfuscate(dexBytes) overload
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "c" }
        assertNotNull("Method should be renamed to 'c'", method)

        // Verify: original private access preserved (no APK data)
        val access = method!!.access
        assertTrue(
            "Method should keep ACC_PRIVATE when no APK data",
            access and DexConstants.ACC_PRIVATE != 0
        )
    }

    /**
     * P1: APK method with static flag preserved during alignment.
     * R8 widens private static -> public static; verify static flag survives.
     */
    @Test
    fun testStaticFlagPreservedDuringAlignment() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void staticLambda() -> s
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // APK data: R8 widened private static -> public static
        val apkClassNodes = buildApkClassNodes(
            ClassNode("apk.dex", "La/b;", DexConstants.ACC_PUBLIC,
                methods = listOf(MethodNode("La/b;",
                    DexConstants.ACC_PUBLIC or DexConstants.ACC_STATIC, "s", "()V")),
                fields = emptyList(),
                interfaceNames = emptyList(), superClass = "Ljava/lang/Object;", sourceArg = null)
        )

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
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes, apkClassNodes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "s" }
        assertNotNull("Method should be renamed to 's'", method)

        val access = method!!.access
        assertTrue(
            "Method should have ACC_PUBLIC (aligned to APK)",
            access and DexConstants.ACC_PUBLIC != 0
        )
        assertFalse(
            "Method should NOT have ACC_PRIVATE",
            access and DexConstants.ACC_PRIVATE != 0
        )
        assertTrue(
            "Static flag should be preserved from APK",
            access and DexConstants.ACC_STATIC != 0
        )
    }

    /**
     * P1: Method with parameters - access flag lookup must match by name+desc.
     * Ensures the lookup key correctly includes the method descriptor.
     */
    @Test
    fun testMethodWithParamsAccessAligned() {
        val mappingContent = """
            com.example.TestClass -> a.b:
                void process(java.lang.String,int) -> p
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // APK data: method with params, R8 widened to public
        val apkClassNodes = buildApkClassNodes(
            ClassNode("apk.dex", "La/b;", DexConstants.ACC_PUBLIC,
                methods = listOf(MethodNode("La/b;",
                    DexConstants.ACC_PUBLIC, "p", "(Ljava/lang/String;I)V")),
                fields = emptyList(),
                interfaceNames = emptyList(), superClass = "Ljava/lang/Object;", sourceArg = null)
        )

        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/TestClass;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PROTECTED,
            Method("Lcom/example/TestClass;", "process",
                Proto(arrayOf("Ljava/lang/String;", "I"), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val originalBytes = dexWriter.toByteArray()
        val obfuscatedBytes = obfuscator.obfuscate(originalBytes, apkClassNodes)
        assertNotNull("Should obfuscate the DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]
        val method = classNode.methods.find { it.method.name == "p" }
        assertNotNull("Method should be renamed to 'p'", method)

        val access = method!!.access
        assertTrue(
            "Method with params should have ACC_PUBLIC (aligned to APK)",
            access and DexConstants.ACC_PUBLIC != 0
        )
        assertFalse(
            "Method should NOT have ACC_PROTECTED",
            access and DexConstants.ACC_PROTECTED != 0
        )
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
