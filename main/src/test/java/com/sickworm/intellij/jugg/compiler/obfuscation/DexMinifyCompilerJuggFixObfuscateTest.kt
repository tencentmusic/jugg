package com.sickworm.intellij.jugg.compiler.obfuscation

import com.googlecode.d2j.DexConstants
import com.googlecode.d2j.Field
import com.googlecode.d2j.Method
import com.googlecode.d2j.Proto
import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.Op
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexCodeVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.googlecode.d2j.visitors.DexMethodVisitor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Verifies that _jugg_fix DEX files have their internal class references
 * (e.g., anonymous inner classes) properly obfuscated.
 *
 * Bug scenario:
 *   LogUtil has an anonymous inner class LogUtil$1.
 *   R8 renames LogUtil$1 -> a/b.
 *   DexMinifyCompiler generates LogUtil_jugg_fix from original .class bytecode.
 *   The generated _jugg_fix DEX still references "Lcom/example/LogUtil$1;"
 *   but the APK only contains "La/b;", causing NoClassDefFoundError at runtime.
 *
 * Fix: Apply DexObfuscator.obfuscate() to _jugg_fix DEX before writing to output.
 */
class DexMinifyCompilerJuggFixObfuscateTest {

    companion object {
        private lateinit var tempDir: File

        // Mapping: inner class is obfuscated, outer class is also obfuscated
        // but _jugg_fix class itself has NO mapping entry (it's a generated name)
        private const val OUTER_CLASS_ORIGINAL = "Lcom/example/LogUtil;"
        private const val INNER_CLASS_ORIGINAL = "Lcom/example/LogUtil\$1;"
        private const val OUTER_CLASS_OBFUSCATED = "La/b/c;"
        private const val INNER_CLASS_OBFUSCATED = "La/b/d;"

        // The _jugg_fix class name — NOT in mapping
        private const val JUGG_FIX_CLASS = "Lcom/example/LogUtil_jugg_fix;"
    }

    @Before
    fun setUp() {
        tempDir = File(
            System.getProperty("java.io.tmpdir"),
            "jugg_fix_obfuscate_test_${System.currentTimeMillis()}"
        )
        tempDir.mkdirs()
    }

    // ==================== Helper methods ====================

    /**
     * Creates a minimal DEX file simulating a _jugg_fix class that references
     * an anonymous inner class via a static field.
     *
     * This mimics the real scenario:
     *   LogUtil_jugg_fix has a static field of type LogUtil$1
     */
    private fun createJuggFixDexWithInnerClassRef(): ByteArray {
        val dexWriter = DexFileWriter()

        // Create LogUtil_jugg_fix class with a static field referencing LogUtil$1
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            JUGG_FIX_CLASS,
            "Ljava/lang/Object;",
            null
        )

        // Add a static field of type LogUtil$1 (the anonymous inner class)
        classVisitor.visitField(
            DexConstants.ACC_PRIVATE or DexConstants.ACC_STATIC,
            Field(JUGG_FIX_CLASS, "INSTANCE", INNER_CLASS_ORIGINAL),
            null
        ).visitEnd()

        // Add a method that references the inner class in its body
        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_STATIC,
            Method(JUGG_FIX_CLASS, "<clinit>", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitRegister(1)
        // new-instance v0, LogUtil$1
        codeVisitor.visitTypeStmt(Op.NEW_INSTANCE, 0, 0, INNER_CLASS_ORIGINAL)
        // sput-object v0, LogUtil_jugg_fix.INSTANCE
        codeVisitor.visitFieldStmt(
            Op.SPUT_OBJECT, 0, 0,
            Field(JUGG_FIX_CLASS, "INSTANCE", INNER_CLASS_ORIGINAL)
        )
        codeVisitor.visitStmt0R(Op.RETURN_VOID)
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()

        classVisitor.visitEnd()
        dexWriter.visitEnd()

        return dexWriter.toByteArray()
    }

    /**
     * Collects all type references (class names, field types, etc.) from a DEX byte array.
     */
    private fun collectAllTypeReferences(dexBytes: ByteArray): Set<String> {
        val types = mutableSetOf<String>()
        val reader = DexFileReader(dexBytes)
        reader.accept(object : DexFileVisitor() {
            override fun visit(
                accessFlags: Int,
                className: String,
                superClass: String?,
                interfaceNames: Array<out String>?
            ): DexClassVisitor {
                types.add(className)
                superClass?.let { types.add(it) }
                interfaceNames?.forEach { types.add(it) }

                return object : DexClassVisitor() {
                    override fun visitField(
                        accessFlags: Int,
                        field: Field,
                        value: Any?
                    ): com.googlecode.d2j.visitors.DexFieldVisitor? {
                        types.add(field.type)
                        types.add(field.owner)
                        return null
                    }

                    override fun visitMethod(
                        accessFlags: Int,
                        method: Method
                    ): DexMethodVisitor? {
                        types.add(method.owner)
                        types.add(method.returnType)
                        method.parameterTypes?.forEach { types.add(it) }

                        return object : DexMethodVisitor() {
                            override fun visitCode(): DexCodeVisitor {
                                return object : DexCodeVisitor() {
                                    override fun visitTypeStmt(
                                        op: Op?,
                                        a: Int,
                                        b: Int,
                                        type: String?
                                    ) {
                                        type?.let { types.add(it) }
                                    }

                                    override fun visitFieldStmt(
                                        op: Op?,
                                        a: Int,
                                        b: Int,
                                        field: Field?
                                    ) {
                                        field?.let {
                                            types.add(it.type)
                                            types.add(it.owner)
                                        }
                                    }

                                    override fun visitMethodStmt(
                                        op: Op?,
                                        args: IntArray?,
                                        method: Method?
                                    ) {
                                        method?.let {
                                            types.add(it.owner)
                                            types.add(it.returnType)
                                            it.parameterTypes?.forEach { p -> types.add(p) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }, 0)
        return types
    }

    private fun readDex(bytes: ByteArray): DexFileNode {
        val dexReader = DexFileReader(bytes)
        val dexNode = DexFileNode()
        dexReader.accept(dexNode)
        return dexNode
    }

    // ==================== Tests ====================

    /**
     * Verifies the test DEX contains the original inner class reference
     * (precondition check).
     */
    @Test
    fun `jugg_fix dex should initially contain original inner class reference`() {
        val dexBytes = createJuggFixDexWithInnerClassRef()
        val types = collectAllTypeReferences(dexBytes)

        assertTrue(
            "Test DEX should contain reference to original inner class $INNER_CLASS_ORIGINAL",
            types.contains(INNER_CLASS_ORIGINAL)
        )
        assertTrue(
            "Test DEX should contain the _jugg_fix class itself $JUGG_FIX_CLASS",
            types.contains(JUGG_FIX_CLASS)
        )
    }

    /**
     * Core test: After applying DexObfuscator.obfuscate(), the inner class
     * reference inside _jugg_fix DEX should be remapped to its obfuscated name.
     *
     * This test currently FAILS because generateJuggFixClasses() copies DEX
     * directly without obfuscation. After the fix, obfuscate() will be applied.
     */
    @Test
    fun `obfuscate should remap inner class references inside jugg_fix dex`() {
        // Mapping includes both the outer class and its anonymous inner class
        val mappingContent = """
            com.example.LogUtil -> a.b.c:
            com.example.LogUtil${'$'}1 -> a.b.d:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        // Create a _jugg_fix DEX that references the inner class
        val juggFixDex = createJuggFixDexWithInnerClassRef()

        // Verify precondition: original DEX contains un-obfuscated inner class ref
        val originalTypes = collectAllTypeReferences(juggFixDex)
        assertTrue(
            "Original DEX should reference $INNER_CLASS_ORIGINAL",
            originalTypes.contains(INNER_CLASS_ORIGINAL)
        )

        // Apply obfuscation — this is what the fix should do
        val obfuscatedBytes = obfuscator.obfuscate(juggFixDex)

        // After obfuscation, inner class ref should be remapped
        assertNotNull(
            "obfuscate() should return non-null because inner class reference " +
                "needs remapping to its obfuscated name",
            obfuscatedBytes
        )

        val obfuscatedTypes = collectAllTypeReferences(obfuscatedBytes!!)

        // Inner class reference should now be obfuscated
        assertFalse(
            "Obfuscated _jugg_fix DEX should NOT contain original inner class name " +
                "$INNER_CLASS_ORIGINAL. Found types: $obfuscatedTypes",
            obfuscatedTypes.contains(INNER_CLASS_ORIGINAL)
        )
        assertTrue(
            "Obfuscated _jugg_fix DEX should contain obfuscated inner class name " +
                "$INNER_CLASS_OBFUSCATED. Found types: $obfuscatedTypes",
            obfuscatedTypes.contains(INNER_CLASS_OBFUSCATED)
        )

        // _jugg_fix class name itself should NOT be remapped (it has no mapping entry)
        // Note: The outer class mapping (LogUtil -> a.b.c) should NOT affect
        // LogUtil_jugg_fix because "com.example.LogUtil_jugg_fix" != "com.example.LogUtil"
        assertTrue(
            "_jugg_fix class name should remain unchanged: $JUGG_FIX_CLASS. " +
                "Found types: $obfuscatedTypes",
            obfuscatedTypes.contains(JUGG_FIX_CLASS)
        )
    }

    /**
     * Verifies that when obfuscation is applied to a _jugg_fix DEX, the
     * _jugg_fix class name itself is preserved (not remapped), while only
     * its internal references are obfuscated.
     */
    @Test
    fun `obfuscate should preserve jugg_fix class name while remapping references`() {
        val mappingContent = """
            com.example.LogUtil -> a.b.c:
            com.example.LogUtil${'$'}1 -> a.b.d:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)
        val juggFixDex = createJuggFixDexWithInnerClassRef()

        val obfuscatedBytes = obfuscator.obfuscate(juggFixDex)
        assertNotNull("obfuscate() should return non-null", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        assertEquals("Should contain exactly one class", 1, dexNode.clzs.size)

        val classNode = dexNode.clzs[0]
        assertEquals(
            "_jugg_fix class name should be preserved",
            JUGG_FIX_CLASS,
            classNode.className
        )
    }

    /**
     * Verifies that the field type referencing the inner class is also remapped.
     */
    @Test
    fun `obfuscate should remap field type of inner class reference`() {
        val mappingContent = """
            com.example.LogUtil -> a.b.c:
            com.example.LogUtil${'$'}1 -> a.b.d:
        """.trimIndent()

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)
        val juggFixDex = createJuggFixDexWithInnerClassRef()

        val obfuscatedBytes = obfuscator.obfuscate(juggFixDex)
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        // Find the INSTANCE field — its type should now be the obfuscated inner class
        val instanceField = classNode.fields.find { it.field.name == "INSTANCE" }
        assertNotNull("Should have INSTANCE field", instanceField)
        assertEquals(
            "INSTANCE field type should be remapped to obfuscated inner class name",
            INNER_CLASS_OBFUSCATED,
            instanceField!!.field.type
        )
    }
}
