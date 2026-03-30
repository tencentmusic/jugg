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
import org.junit.Test

/**
 * Tests for Plan A: obfuscate-then-rename approach for _jugg_fix DEX files.
 *
 * Core design principle:
 *   _jugg_fix is a bridge class whose internal method calls should point to
 *   the ORIGINAL class (obfuscated as a.b.c in APK), NOT to itself. This
 *   ensures that when ClassA receives incremental updates, callers through
 *   _jugg_fix still reach the new implementation.
 *
 * Plan A flow:
 *   original .class -> D8 -> obfuscate() -> renameDexClassDeclaration()
 *
 * renameDexClassDeclaration only renames the class declaration, NOT method
 * call owners or field reference owners inside the class body. This keeps
 * internal self-references pointing to the original obfuscated class.
 *
 * Tests cover:
 *   1. renameDexClassDeclaration: only renames class declaration
 *   2. Self-references preserved after rename (point to original class)
 *   3. redirectClassMap uses obfuscated name + suffix
 *   4. End-to-end: incremental DEX calls match _jugg_fix method signatures
 */
class DexObfuscatorJuggFixFullObfuscationTest {

    companion object {
        // Mapping: LogUtil -> a.b.c, LogUtil$1 -> a.b.d
        // LogUtil has methods: d(String,String)->a, e()->b
        // LogUtil has field: tag->c
        private const val MAPPING_CONTENT = """com.example.LogUtil -> a.b.c:
    java.lang.String tag -> c
    1:10:void d(java.lang.String,java.lang.String):0:0 -> a
    1:5:void e():0:0 -> b
com.example.LogUtil${'$'}1 -> a.b.d:
    1:3:void run():0:0 -> run"""
    }

    // ==================== renameDexClassDeclaration tests ====================

    /**
     * Core test: renameDexClassDeclaration should ONLY rename the class declaration,
     * NOT any internal references (method calls, field accesses, type refs).
     *
     * Scenario:
     *   Input DEX: class a.b.c with method calling this.b() (owner = a.b.c)
     *   After rename: class declaration = a.b.c_jugg_fix
     *                  method call owner STILL = a.b.c (NOT a.b.c_jugg_fix)
     */
    @Test
    fun `renameDexClassDeclaration should only rename class declaration not self-references`() {
        val obfuscator = DexObfuscator.fromMappingString(MAPPING_CONTENT)

        // Create a DEX simulating an already-obfuscated class (a.b.c)
        // with a method that calls another method on itself (self-reference)
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "La/b/c;",
            "Ljava/lang/Object;",
            null
        )

        // Method "a" calls self method "b" via invoke-virtual
        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("La/b/c;", "a", Proto(arrayOf("Ljava/lang/String;", "Ljava/lang/String;"), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitRegister(3)
        // invoke-virtual this.b()
        codeVisitor.visitMethodStmt(
            Op.INVOKE_VIRTUAL,
            intArrayOf(0),
            Method("La/b/c;", "b", Proto(emptyArray(), "V"))
        )
        codeVisitor.visitStmt0R(Op.RETURN_VOID)
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()

        // Method "b" — simple method
        val methodVisitor2 = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("La/b/c;", "b", Proto(emptyArray(), "V"))
        )
        methodVisitor2.visitCode().apply {
            visitRegister(1)
            visitStmt0R(Op.RETURN_VOID)
            visitEnd()
        }
        methodVisitor2.visitEnd()

        classVisitor.visitEnd()
        val dexBytes = dexWriter.toByteArray()

        // Apply renameDexClassDeclaration
        val renamedBytes = obfuscator.renameDexClassDeclaration(
            dexBytes,
            "La/b/c;",
            "La/b/c_jugg_fix;"
        )

        val dexNode = readDex(renamedBytes)
        assertEquals("Should have one class", 1, dexNode.clzs.size)
        val classNode = dexNode.clzs[0]

        // Class declaration should be renamed
        assertEquals(
            "Class declaration should be renamed to _jugg_fix",
            "La/b/c_jugg_fix;",
            classNode.className
        )

        // Find method "a" and check its internal call
        val methodA = classNode.methods.find { it.method.name == "a" }
        assertNotNull("Method 'a' should exist", methodA)

        // Method "a" owner should be renamed (it's declared in the renamed class)
        assertEquals(
            "Method declaration owner should be the renamed class",
            "La/b/c_jugg_fix;",
            methodA!!.method.owner
        )

        // The internal call to self.b() should STILL reference a.b.c, NOT a.b.c_jugg_fix
        val methodStmt = methodA.codeNode.stmts
            .filterIsInstance<com.googlecode.d2j.node.insn.MethodStmtNode>()
            .firstOrNull()
        assertNotNull("Should have a method invocation", methodStmt)
        assertEquals(
            "Self-reference in method call should still point to original class a.b.c",
            "La/b/c;",
            methodStmt!!.method.owner
        )
    }

    /**
     * renameDexClassDeclaration should also rename field declarations' owner
     * but NOT field references in code body.
     */
    @Test
    fun `renameDexClassDeclaration should rename field declaration owner but not field refs in code`() {
        val obfuscator = DexObfuscator.fromMappingString(MAPPING_CONTENT)

        // Create DEX: class a.b.c with a field and a method that accesses the field
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "La/b/c;",
            "Ljava/lang/Object;",
            null
        )

        // Field: String c (originally "tag")
        classVisitor.visitField(
            DexConstants.ACC_PRIVATE,
            Field("La/b/c;", "c", "Ljava/lang/String;"),
            null
        ).visitEnd()

        // Method "a" reads field this.c
        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("La/b/c;", "a", Proto(arrayOf("Ljava/lang/String;", "Ljava/lang/String;"), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitRegister(3)
        // iget-object v1, v0, La/b/c;.c:Ljava/lang/String;
        codeVisitor.visitFieldStmt(
            Op.IGET_OBJECT, 1, 0,
            Field("La/b/c;", "c", "Ljava/lang/String;")
        )
        codeVisitor.visitStmt0R(Op.RETURN_VOID)
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()

        classVisitor.visitEnd()
        val dexBytes = dexWriter.toByteArray()

        val renamedBytes = obfuscator.renameDexClassDeclaration(
            dexBytes,
            "La/b/c;",
            "La/b/c_jugg_fix;"
        )

        val dexNode = readDex(renamedBytes)
        val classNode = dexNode.clzs[0]

        // Class declaration renamed
        assertEquals("La/b/c_jugg_fix;", classNode.className)

        // Field declaration owner should be renamed
        val field = classNode.fields.find { it.field.name == "c" }
        assertNotNull("Field 'c' should exist", field)
        assertEquals(
            "Field declaration owner should be renamed",
            "La/b/c_jugg_fix;",
            field!!.field.owner
        )

        // BUT field reference in code should still point to original class
        val fieldStmts = mutableListOf<Field>()
        collectFieldRefs(renamedBytes, fieldStmts)
        val codeFieldRef = fieldStmts.find { it.name == "c" }
        assertNotNull("Should have field ref in code", codeFieldRef)
        assertEquals(
            "Field reference in code body should still point to original class",
            "La/b/c;",
            codeFieldRef!!.owner
        )
    }

    /**
     * renameDexClassDeclaration should NOT rename references to OTHER classes.
     */
    @Test
    fun `renameDexClassDeclaration should not affect references to other classes`() {
        val obfuscator = DexObfuscator.fromMappingString(MAPPING_CONTENT)

        // Create DEX: class a.b.c with method that calls a.b.d.run() (inner class)
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "La/b/c;",
            "Ljava/lang/Object;",
            null
        )

        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("La/b/c;", "a", Proto(arrayOf("Ljava/lang/String;", "Ljava/lang/String;"), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitRegister(2)
        // Call inner class method: a.b.d.run()
        codeVisitor.visitMethodStmt(
            Op.INVOKE_VIRTUAL,
            intArrayOf(1),
            Method("La/b/d;", "run", Proto(emptyArray(), "V"))
        )
        codeVisitor.visitStmt0R(Op.RETURN_VOID)
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val dexBytes = dexWriter.toByteArray()
        val renamedBytes = obfuscator.renameDexClassDeclaration(
            dexBytes, "La/b/c;", "La/b/c_jugg_fix;"
        )

        // Check that a.b.d reference is unchanged
        val allTypes = collectAllTypeReferences(renamedBytes)
        assertTrue("Reference to a.b.d should be preserved", allTypes.contains("La/b/d;"))
        assertFalse("Should NOT have a.b.c (old class declaration)", allTypes.contains("La/b/c;"))
        assertTrue("Should have a.b.c_jugg_fix (new class declaration)", allTypes.contains("La/b/c_jugg_fix;"))
    }

    // ==================== Full pipeline tests ====================

    /**
     * End-to-end test for Plan A pipeline:
     *   1. Create original LogUtil DEX (un-obfuscated)
     *   2. obfuscate() -> all names correctly mapped (LogUtil -> a.b.c, d -> a, etc.)
     *   3. renameDexClassDeclaration -> class becomes a.b.c_jugg_fix
     *   4. Self-references inside _jugg_fix still point to a.b.c (original APK class)
     */
    @Test
    fun `full pipeline should produce jugg_fix with self-refs pointing to original obfuscated class`() {
        val obfuscator = DexObfuscator.fromMappingString(MAPPING_CONTENT)

        // Step 1: Create un-obfuscated LogUtil DEX (simulating after D8 conversion)
        val originalDex = createLogUtilDex()

        // Step 2: obfuscate() — should correctly map everything
        val obfuscatedBytes = obfuscator.obfuscate(originalDex)
        assertNotNull("obfuscate should map LogUtil to a.b.c", obfuscatedBytes)

        // Verify obfuscation is correct before rename
        val obfuscatedNode = readDex(obfuscatedBytes!!)
        assertEquals(1, obfuscatedNode.clzs.size)
        val obfuscatedClass = obfuscatedNode.clzs[0]
        assertEquals("La/b/c;", obfuscatedClass.className)

        // Method d -> a should exist
        val methodA = obfuscatedClass.methods.find { it.method.name == "a" }
        assertNotNull("Method d should be renamed to a", methodA)

        // Step 3: renameDexClassDeclaration — class becomes a.b.c_jugg_fix
        val renamedBytes = obfuscator.renameDexClassDeclaration(
            obfuscatedBytes, "La/b/c;", "La/b/c_jugg_fix;"
        )
        val renamedNode = readDex(renamedBytes)
        val renamedClass = renamedNode.clzs[0]

        // Class declaration is a.b.c_jugg_fix
        assertEquals(
            "Class should be renamed to a.b.c_jugg_fix",
            "La/b/c_jugg_fix;",
            renamedClass.className
        )

        // Method names should still be obfuscated (a, b)
        val renamedMethodA = renamedClass.methods.find { it.method.name == "a" }
        assertNotNull("Obfuscated method 'a' should still exist", renamedMethodA)

        // Self-reference inside method "a" calling "b" should point to a.b.c (NOT a.b.c_jugg_fix)
        val selfCallStmt = renamedMethodA?.codeNode?.stmts
            ?.filterIsInstance<com.googlecode.d2j.node.insn.MethodStmtNode>()
            ?.firstOrNull()
        if (selfCallStmt != null) {
            assertEquals(
                "Self-reference should point to original obfuscated class a.b.c, " +
                    "NOT to a.b.c_jugg_fix. This is the key design requirement: " +
                    "_jugg_fix is a bridge that delegates to the original class.",
                "La/b/c;",
                selfCallStmt.method.owner
            )
        }
    }

    // ==================== redirectClassMap tests ====================

    /**
     * When MinifyInfo is provided, redirectClassMap should map original names to
     * obfuscated name + _jugg_fix suffix.
     *
     * Scenario:
     *   Mapping: LogUtil -> a.b.c
     *   MinifyInfo says LogUtil needs _jugg_fix
     *   Incremental DEX calls LogUtil.d(String,String)
     *
     * Expected after obfuscateWithInlineRedirect:
     *   Call target: a.b.c_jugg_fix.a(String,String)
     *     - owner: LogUtil -> redirect to a.b.c_jugg_fix (obfuscated + suffix)
     *     - method name: d -> a (from mapping, using original owner for lookup)
     */
    @Test
    fun `obfuscateWithInlineRedirect should redirect to obfuscated class name plus suffix`() {
        val obfuscator = DexObfuscator.fromMappingString(MAPPING_CONTENT)

        // MinifyInfo says LogUtil needs jugg_fix class
        val minifyInfo = MinifyInfo(
            inlineEffectedClasses = listOf(
                InlineEffectedClass(
                    className = "Lcom/example/LogUtil;",
                    effectedByClasses = listOf("Lcom/example/Caller;")
                )
            ),
            classFiles = mapOf("com.example.LogUtil" to java.io.File("/fake/LogUtil.class"))
        )

        // Create incremental DEX that calls LogUtil.d(String,String)
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/Caller;",
            "Ljava/lang/Object;",
            null
        )
        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/Caller;", "test", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitRegister(3)
        codeVisitor.visitMethodStmt(
            Op.INVOKE_STATIC,
            intArrayOf(1, 2),
            Method(
                "Lcom/example/LogUtil;", "d",
                Proto(arrayOf("Ljava/lang/String;", "Ljava/lang/String;"), "V")
            )
        )
        codeVisitor.visitStmt0R(Op.RETURN_VOID)
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val incrementalDex = dexWriter.toByteArray()

        // Apply obfuscateWithInlineRedirect
        val obfuscatedBytes = obfuscator.obfuscateWithInlineRedirect(incrementalDex, minifyInfo)
        assertNotNull("Should obfuscate the incremental DEX", obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val classNode = dexNode.clzs[0]

        val testMethod = classNode.methods.find { it.method.name == "test" }
        assertNotNull("Method 'test' should exist (no mapping for Caller.test)", testMethod)

        val callStmt = testMethod!!.codeNode.stmts
            .filterIsInstance<com.googlecode.d2j.node.insn.MethodStmtNode>()
            .firstOrNull()
        assertNotNull("Should have a method call statement", callStmt)

        // Key assertion 1: owner should be redirected to obfuscated name + suffix
        assertEquals(
            "Call owner should be redirected to obfuscated class name + _jugg_fix suffix. " +
                "LogUtil -> a.b.c (from mapping) + _jugg_fix = a/b/c_jugg_fix",
            "La/b/c_jugg_fix;",
            callStmt!!.method.owner
        )

        // Key assertion 2: method name should still be correctly obfuscated
        assertEquals(
            "Method name should be obfuscated using original owner for lookup: d -> a",
            "a",
            callStmt.method.name
        )
    }

    /**
     * When a class has NO obfuscation mapping (class name stays the same),
     * redirectClassMap should use original name + suffix (no obfuscation step).
     */
    @Test
    fun `obfuscateWithInlineRedirect should handle unmapped class by using original name plus suffix`() {
        // Mapping where the class name is not changed (keep rule)
        val mappingContent = """com.example.KeepClass -> com.example.KeepClass:
    void doWork() -> a"""

        val obfuscator = DexObfuscator.fromMappingString(mappingContent)

        val minifyInfo = MinifyInfo(
            inlineEffectedClasses = listOf(
                InlineEffectedClass(
                    className = "Lcom/example/KeepClass;",
                    effectedByClasses = listOf("Lcom/example/Caller;")
                )
            ),
            classFiles = mapOf("com.example.KeepClass" to java.io.File("/fake/KeepClass.class"))
        )

        // Incremental DEX calls KeepClass.doWork()
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/Caller;",
            "Ljava/lang/Object;",
            null
        )
        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/Caller;", "test", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitRegister(1)
        codeVisitor.visitMethodStmt(
            Op.INVOKE_VIRTUAL,
            intArrayOf(0),
            Method("Lcom/example/KeepClass;", "doWork", Proto(emptyArray(), "V"))
        )
        codeVisitor.visitStmt0R(Op.RETURN_VOID)
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val obfuscatedBytes = obfuscator.obfuscateWithInlineRedirect(
            dexWriter.toByteArray(), minifyInfo
        )
        assertNotNull(obfuscatedBytes)

        val dexNode = readDex(obfuscatedBytes!!)
        val callStmt = dexNode.clzs[0].methods[0].codeNode.stmts
            .filterIsInstance<com.googlecode.d2j.node.insn.MethodStmtNode>()
            .firstOrNull()
        assertNotNull(callStmt)

        // When class name is unchanged by R8, redirect target is original + suffix
        assertEquals(
            "Redirect for unmapped class should use original name + suffix",
            "Lcom/example/KeepClass_jugg_fix;",
            callStmt!!.method.owner
        )
        assertEquals(
            "Method name should still be obfuscated: doWork -> a",
            "a",
            callStmt.method.name
        )
    }

    // ==================== Consistency check: incremental DEX ↔ _jugg_fix DEX ====================

    /**
     * The most critical test: verify that the method signatures called from
     * incremental DEX (via redirect) MATCH the method signatures declared
     * in the _jugg_fix DEX (via obfuscate+rename).
     *
     * Incremental side (obfuscateWithInlineRedirect):
     *   LogUtil.d(String,String) -> a.b.c_jugg_fix.a(String,String)
     *
     * _jugg_fix side (obfuscate + renameDexClassDeclaration):
     *   LogUtil.class -> obfuscate() -> class a.b.c { method a(String,String) }
     *                 -> rename -> class a.b.c_jugg_fix { method a(String,String) }
     *
     * Both sides must agree on: owner = a.b.c_jugg_fix, method name = a
     */
    @Test
    fun `incremental DEX redirect target should match jugg_fix declared method signatures`() {
        val obfuscator = DexObfuscator.fromMappingString(MAPPING_CONTENT)

        // === _jugg_fix side: obfuscate + rename ===
        val originalDex = createLogUtilDex()
        val obfuscatedBytes = obfuscator.obfuscate(originalDex)
        assertNotNull(obfuscatedBytes)

        val juggFixBytes = obfuscator.renameDexClassDeclaration(
            obfuscatedBytes!!, "La/b/c;", "La/b/c_jugg_fix;"
        )
        val juggFixNode = readDex(juggFixBytes)
        val juggFixClass = juggFixNode.clzs[0]

        // Collect declared method signatures in _jugg_fix
        val declaredMethods = juggFixClass.methods.map {
            "${it.method.owner}.${it.method.name}(${it.method.proto.parameterTypes.joinToString(",")})"
        }.toSet()

        // === Incremental side: obfuscateWithInlineRedirect ===
        val minifyInfo = MinifyInfo(
            inlineEffectedClasses = listOf(
                InlineEffectedClass(
                    className = "Lcom/example/LogUtil;",
                    effectedByClasses = listOf("Lcom/example/Caller;")
                )
            ),
            classFiles = mapOf("com.example.LogUtil" to java.io.File("/fake/LogUtil.class"))
        )

        // Incremental DEX calls LogUtil.d(String,String) and LogUtil.e()
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/Caller;",
            "Ljava/lang/Object;",
            null
        )
        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method("Lcom/example/Caller;", "test", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitRegister(3)
        // Call LogUtil.d(String,String)
        codeVisitor.visitMethodStmt(
            Op.INVOKE_STATIC, intArrayOf(1, 2),
            Method("Lcom/example/LogUtil;", "d",
                Proto(arrayOf("Ljava/lang/String;", "Ljava/lang/String;"), "V"))
        )
        // Call LogUtil.e()
        codeVisitor.visitMethodStmt(
            Op.INVOKE_STATIC, intArrayOf(),
            Method("Lcom/example/LogUtil;", "e", Proto(emptyArray(), "V"))
        )
        codeVisitor.visitStmt0R(Op.RETURN_VOID)
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()

        val redirectedBytes = obfuscator.obfuscateWithInlineRedirect(
            dexWriter.toByteArray(), minifyInfo
        )
        assertNotNull(redirectedBytes)

        // Collect call targets from incremental DEX
        val dexNode = readDex(redirectedBytes!!)
        val testMethod = dexNode.clzs[0].methods.find { it.method.name == "test" }
        assertNotNull(testMethod)

        val callTargets = testMethod!!.codeNode.stmts
            .filterIsInstance<com.googlecode.d2j.node.insn.MethodStmtNode>()
            .map { "${it.method.owner}.${it.method.name}(${it.method.proto.parameterTypes.joinToString(",")})" }
            .toSet()

        // Every call target in incremental DEX must have a matching declaration in _jugg_fix
        for (target in callTargets) {
            assertTrue(
                "Incremental DEX call target '$target' must match a declared method in _jugg_fix.\n" +
                    "Declared methods: $declaredMethods\n" +
                    "Call targets: $callTargets",
                declaredMethods.contains(target)
            )
        }
    }

    // ==================== Helper methods ====================

    /**
     * Creates a LogUtil DEX with original (un-obfuscated) names.
     * Class: com.example.LogUtil
     * Methods: d(String,String)V calls self.e()V
     *          e()V is a simple method
     * Field: tag:String
     */
    private fun createLogUtilDex(): ByteArray {
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            "Lcom/example/LogUtil;",
            "Ljava/lang/Object;",
            null
        )

        // Field: tag
        classVisitor.visitField(
            DexConstants.ACC_PRIVATE,
            Field("Lcom/example/LogUtil;", "tag", "Ljava/lang/String;"),
            null
        ).visitEnd()

        // Method d(String,String)V — calls this.e()
        val methodD = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC or DexConstants.ACC_STATIC,
            Method("Lcom/example/LogUtil;", "d",
                Proto(arrayOf("Ljava/lang/String;", "Ljava/lang/String;"), "V"))
        )
        val codeDVisitor = methodD.visitCode()
        codeDVisitor.visitRegister(3)
        // invoke-static LogUtil.e()
        codeDVisitor.visitMethodStmt(
            Op.INVOKE_STATIC,
            intArrayOf(),
            Method("Lcom/example/LogUtil;", "e", Proto(emptyArray(), "V"))
        )
        codeDVisitor.visitStmt0R(Op.RETURN_VOID)
        codeDVisitor.visitEnd()
        methodD.visitEnd()

        // Method e()V — simple return
        val methodE = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC or DexConstants.ACC_STATIC,
            Method("Lcom/example/LogUtil;", "e", Proto(emptyArray(), "V"))
        )
        methodE.visitCode().apply {
            visitRegister(0)
            visitStmt0R(Op.RETURN_VOID)
            visitEnd()
        }
        methodE.visitEnd()

        classVisitor.visitEnd()
        dexWriter.visitEnd()
        return dexWriter.toByteArray()
    }

    private fun readDex(bytes: ByteArray): DexFileNode {
        val dexReader = DexFileReader(bytes)
        val dexNode = DexFileNode()
        dexReader.accept(dexNode)
        return dexNode
    }

    /**
     * Collect all type references from a DEX byte array.
     */
    private fun collectAllTypeReferences(dexBytes: ByteArray): Set<String> {
        val types = mutableSetOf<String>()
        val reader = DexFileReader(dexBytes)
        reader.accept(object : DexFileVisitor() {
            override fun visit(
                accessFlags: Int, className: String,
                superClass: String?, interfaceNames: Array<out String>?
            ): DexClassVisitor {
                types.add(className)
                superClass?.let { types.add(it) }
                interfaceNames?.forEach { types.add(it) }
                return object : DexClassVisitor() {
                    override fun visitField(
                        accessFlags: Int, field: Field, value: Any?
                    ): com.googlecode.d2j.visitors.DexFieldVisitor? {
                        types.add(field.type)
                        types.add(field.owner)
                        return null
                    }
                    override fun visitMethod(accessFlags: Int, method: Method): DexMethodVisitor {
                        types.add(method.owner)
                        types.add(method.returnType)
                        method.parameterTypes?.forEach { types.add(it) }
                        return object : DexMethodVisitor() {
                            override fun visitCode(): DexCodeVisitor {
                                return object : DexCodeVisitor() {
                                    override fun visitTypeStmt(op: Op?, a: Int, b: Int, type: String?) {
                                        type?.let { types.add(it) }
                                    }
                                    override fun visitFieldStmt(op: Op?, a: Int, b: Int, field: Field?) {
                                        field?.let { types.add(it.owner); types.add(it.type) }
                                    }
                                    override fun visitMethodStmt(op: Op?, args: IntArray?, method: Method?) {
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

    /**
     * Collect field references from code bodies in a DEX.
     */
    private fun collectFieldRefs(dexBytes: ByteArray, fieldRefs: MutableList<Field>) {
        val reader = DexFileReader(dexBytes)
        reader.accept(object : DexFileVisitor() {
            override fun visit(
                accessFlags: Int, className: String,
                superClass: String?, interfaceNames: Array<out String>?
            ): DexClassVisitor {
                return object : DexClassVisitor() {
                    override fun visitMethod(accessFlags: Int, method: Method): DexMethodVisitor {
                        return object : DexMethodVisitor() {
                            override fun visitCode(): DexCodeVisitor {
                                return object : DexCodeVisitor() {
                                    override fun visitFieldStmt(op: Op?, a: Int, b: Int, field: Field?) {
                                        field?.let { fieldRefs.add(it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }, 0)
    }
}
