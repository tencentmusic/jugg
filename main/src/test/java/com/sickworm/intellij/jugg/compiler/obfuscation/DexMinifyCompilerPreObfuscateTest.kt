package com.sickworm.intellij.jugg.compiler.obfuscation

import com.googlecode.d2j.DexConstants
import com.googlecode.d2j.Method
import com.googlecode.d2j.Proto
import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.mockModule
import com.sickworm.intellij.jugg.mock.mockParentDisposable
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that DexMinifyCompiler pre-obfuscates dex files before passing them
 * to getMinifyInfo, so that the class names inside the dex bytes match the
 * obfuscated names stored in the deploy database.
 *
 * Without pre-obfuscation, getMinifyInfo receives original class names which
 * cannot be found in the DB, causing false "missing classes" detection.
 */
class DexMinifyCompilerPreObfuscateTest {

    private lateinit var tempDir: File
    private lateinit var outputDir: File
    private lateinit var inputDir: File
    private lateinit var context: ICompileContext

    // Mapping: com/example/MyClass -> a/b
    private val originalClassName = "Lcom/example/MyClass;"
    private val obfuscatedClassName = "La/b;"
    private val mappingContent = """
        com.example.MyClass -> a.b:
            void doSomething() -> c
    """.trimIndent()

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "jugg_pre_obfuscate_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        outputDir = File(tempDir, "output")
        outputDir.mkdirs()
        inputDir = File(tempDir, "input/com/example")
        inputDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * Creates a minimal DEX file containing a single class with the given name.
     */
    private fun createDexFile(className: String, outputFile: File) {
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(
            DexConstants.ACC_PUBLIC,
            className,
            "Ljava/lang/Object;",
            null
        )
        // Add a simple method
        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method(className, "doSomething", Proto(emptyArray(), "V"))
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitRegister(1)
        codeVisitor.visitStmt0R(com.googlecode.d2j.reader.Op.RETURN_VOID)
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()
        dexWriter.visitEnd()

        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(dexWriter.toByteArray())
    }

    /**
     * Extracts all class names from a DEX file.
     */
    private fun extractClassNamesFromDexBytes(dexBytes: ByteArray): Set<String> {
        val classNames = mutableSetOf<String>()
        val reader = DexFileReader(dexBytes)
        reader.accept(object : DexFileVisitor() {
            override fun visit(
                accessFlags: Int,
                className: String,
                superClass: String?,
                interfaceNames: Array<out String>?
            ): DexClassVisitor? {
                classNames.add(className)
                return null
            }
        }, 0)
        return classNames
    }

    @Test
    fun `getMinifyInfo should receive obfuscated dex files not original ones`() {
        // 1. Create a mapping file
        val mappingFile = File(tempDir, "mapping.txt")
        mappingFile.writeText(mappingContent)

        // 2. Create an un-obfuscated DEX file (original class name)
        val dexFile = File(inputDir, "MyClass.dex")
        createDexFile(originalClassName, dexFile)

        // Verify the DEX file contains the original class name
        val originalClasses = extractClassNamesFromDexBytes(dexFile.readBytes())
        assertTrue(
            originalClasses.contains(originalClassName),
            "Input DEX should contain original class name $originalClassName, but found: $originalClasses"
        )

        // 3. Set up mock context that captures getMinifyInfo arguments
        val capturedFiles = mutableListOf<List<CompileFile>>()

        context = mock<ICompileContext> {
            whenever(it.logger).thenReturn(logger)
            whenever(it.isMinified).thenReturn(true)
            whenever(it.isReleaseApk).thenReturn(true)
            whenever(it.mappingFile).thenReturn(mappingFile)
            whenever(it.tempCompileDir).thenReturn(File(tempDir, "compiled"))
            whenever(it.getMinifyInfo(any())).thenAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val files = invocation.arguments[0] as List<CompileFile>
                capturedFiles.add(files)
                null // return null MinifyInfo for simplicity
            }
        }

        // 4. Create DexMinifyCompiler and compile
        val compiler = DexMinifyCompiler(context, mockParentDisposable)
        val baseDir = File(tempDir, "input")
        val compileFile = CompileFile(
            type = CompileFile.Type.Dex,
            file = dexFile,
            baseDir = baseDir,
            module = mockModule,
        )
        val task = CompileTask(
            files = listOf(compileFile),
            outputDir = outputDir,
            CompileStatusHolder.DEFAULT,
        )

        compiler.compile(task)

        // 5. Verify that getMinifyInfo was called with obfuscated files
        assertTrue(
            capturedFiles.isNotEmpty(),
            "getMinifyInfo should have been called at least once"
        )

        val passedFiles = capturedFiles.first()
        assertTrue(
            passedFiles.isNotEmpty(),
            "getMinifyInfo should receive non-empty file list"
        )

        // Read the DEX content of the files passed to getMinifyInfo
        // and verify they contain the OBFUSCATED class name, not the original one
        passedFiles.forEach { cf ->
            val dexBytes = cf.file.readBytes()
            val classNames = extractClassNamesFromDexBytes(dexBytes)

            assertFalse(
                classNames.contains(originalClassName),
                "Files passed to getMinifyInfo should NOT contain original class name " +
                    "$originalClassName. Found classes: $classNames"
            )
            assertTrue(
                classNames.contains(obfuscatedClassName),
                "Files passed to getMinifyInfo should contain obfuscated class name " +
                    "$obfuscatedClassName. Found classes: $classNames"
            )
        }
    }
}
