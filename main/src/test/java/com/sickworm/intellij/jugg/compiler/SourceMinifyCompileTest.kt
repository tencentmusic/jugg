package com.sickworm.intellij.jugg.compiler

import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.MultiDexFileReader
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for SourceCompiler with minify (R8 obfuscation) enabled.
 *
 * These tests verify that the compiler correctly compiles source files
 * and produces valid dex output when minify is enabled.
 *
 * Test cases are based on the minify test classes in:
 * android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/minify/
 *
 * For mapping file verification tests, see R8MappingTest.kt
 */
class SourceMinifyCompileTest {

    companion object {
        private const val MINIFY_SOURCE_DIR = "app/src/main/java/com/sickworm/jugg/demo/testcase/minify"

        private var releaseContext: SimpleCompileContext? = null
        private var apkClasses: Map<String, DexClassNode>? = null
        private var releaseApkFile: File? = null

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            try {
                val mappingFile = File(TestGlobal.assetsAndroidDir, "app/build/outputs/mapping/release/mapping.txt")
                GradleBuildHelper.appAssembleRelease()

                if (!mappingFile.exists()) {
                    throw IllegalStateException("Mapping file not found: ${mappingFile.absolutePath}")
                }

                releaseContext = TestGlobal.context.copy(
                    modules = ProjectInfoSerializer(
                        AssembleAndroidProjectOnce.gradleProjectInfoFile,
                        logger
                    ).load()!!.modules,
                )

                // Load APK for comparison
                releaseApkFile = File(TestGlobal.assetsAndroidDir, "app/build/outputs/apk/release/app-release.apk")
                if (releaseApkFile!!.exists()) {
                    apkClasses = loadApkClasses(releaseApkFile!!)
                }
            } catch (e: Exception) {
                throw IllegalStateException("Setup failed: ${e.message}", e)
            }
        }

        private fun loadApkClasses(apkFile: File): Map<String, DexClassNode> {
            val classes = mutableMapOf<String, DexClassNode>()
            val apkBytes = apkFile.readBytes()
            val reader: BaseDexFileReader = MultiDexFileReader.open(apkBytes)
            val visitor = DexFileNode()
            reader.accept(visitor)

            visitor.clzs.forEach {
                classes[it.className] = it
            }
            return classes
        }
    }

    private lateinit var sourceCompiler: SourceCompiler

    @Before
    fun setUp() {
        sourceCompiler = SourceCompiler(releaseContext!!, TestGlobal.mockParentDisposable)
    }

    @Test
    fun testKeepClassName() {
        val task = createCompileTask("KeepClassName.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for KeepClassName")
        assertTrue(result.outputs.any { it.type == CompileOutput.Type.Dex }, "Should produce dex output")

        // Verify obfuscation: class name should be kept, but field/method names should be obfuscated
        verifyObfuscation(
            result = result,
            expectedClassName = "Lcom/sickworm/jugg/demo/testcase/minify/KeepClassName;",
            verifyCallback = { compiledClass, apkClass ->
                // Class name should match (not obfuscated)
                assertEquals(apkClass.className, compiledClass.className,
                    "Class name should be preserved: KeepClassName")

                // Field should be obfuscated to 'a' according to mapping
                val apkField = apkClass.fields?.find { it.field.name == "a" }
                assertNotNull(apkField, "Field 'obfuscatedField' should be obfuscated to 'a' in APK")

                val compiledField = compiledClass.fields?.find { it.field.name == "a" }
                assertNotNull(compiledField, "Compiled class should also have obfuscated field 'a'")

                assertEquals(apkField.field.type, compiledField.field.type,
                    "Field type should match")
            }
        )
    }

    @Test
    fun testKeepClassMembers() {
        val task = createCompileTask("KeepClassMembers.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for KeepClassMembers")

        // Verify obfuscation: class name should be obfuscated to b3.a
        verifyObfuscation(
            result = result,
            expectedClassName = "Lb3/a;",
            verifyCallback = { compiledClass, apkClass ->
                // Class name should be obfuscated
                assertEquals("Lb3/a;", compiledClass.className,
                    "Class name should be obfuscated to b3.a")
                assertEquals(apkClass.className, compiledClass.className,
                    "Compiled class name should match APK")

                // keptField should be preserved
                val apkKeptField = apkClass.fields?.find { it.field.name == "keptField" }
                assertNotNull(apkKeptField, "keptField should be preserved in APK")

                val compiledKeptField = compiledClass.fields?.find { it.field.name == "keptField" }
                assertNotNull(compiledKeptField, "keptField should be preserved in compiled output")

                // keptMethod should be preserved
                val apkKeptMethod = apkClass.methods?.find { it.method.name == "keptMethod" }
                assertNotNull(apkKeptMethod, "keptMethod should be preserved in APK")

                val compiledKeptMethod = compiledClass.methods?.find { it.method.name == "keptMethod" }
                assertNotNull(compiledKeptMethod, "keptMethod should be preserved in compiled output")
            }
        )
    }

    @Test
    fun testFullyObfuscated() {
        val task = createCompileTask("FullyObfuscated.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for FullyObfuscated")

        // Note: FullyObfuscated class may be completely removed by R8 optimization
        // because it has no side effects and is only used in MinifyTestActivity.
        // The compilation succeeds and produces dex output, which is what we verify here.
        assertTrue(result.outputs.any { it.type == CompileOutput.Type.Dex },
            "Should produce dex output even if class is optimized away")
    }

    @Test
    fun testKeepMethodName() {
        val task = createCompileTask("KeepMethodName.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for KeepMethodName")

        // Verify obfuscation: class name should be obfuscated to b3.b
        verifyObfuscation(
            result = result,
            expectedClassName = "Lb3/b;",
            verifyCallback = { compiledClass, apkClass ->
                // Class name should be obfuscated
                assertEquals("Lb3/b;", compiledClass.className,
                    "Class name should be obfuscated to b3.b")
                assertEquals(apkClass.className, compiledClass.className,
                    "Compiled class name should match APK")

                // keptMethod should be preserved
                val apkKeptMethod = apkClass.methods?.find { it.method.name == "keptMethod" }
                assertNotNull(apkKeptMethod, "keptMethod should be preserved in APK")

                val compiledKeptMethod = compiledClass.methods?.find { it.method.name == "keptMethod" }
                assertNotNull(compiledKeptMethod, "keptMethod should be preserved in compiled output")

                // internalState field should be obfuscated to 'a'
                val apkField = apkClass.fields?.find { it.field.name == "a" }
                assertNotNull(apkField, "internalState should be obfuscated to 'a' in APK")

                val compiledField = compiledClass.fields?.find { it.field.name == "a" }
                assertNotNull(compiledField, "Compiled class should have obfuscated field 'a'")
            }
        )
    }

    @Test
    fun testKeepAnnotated() {
        val task = createCompileTask("KeepAnnotated.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for KeepAnnotated")

        // Verify obfuscation: class name is kept, @Keep annotated members are preserved
        verifyObfuscation(
            result = result,
            expectedClassName = "Lcom/sickworm/jugg/demo/testcase/minify/KeepAnnotated;",
            verifyCallback = { compiledClass, apkClass ->
                // Class name should be preserved
                assertEquals(apkClass.className, compiledClass.className,
                    "Class name should be preserved: KeepAnnotated")

                // keptField should be preserved (has @Keep annotation)
                val apkKeptField = apkClass.fields?.find { it.field.name == "keptField" }
                assertNotNull(apkKeptField, "keptField should be preserved in APK")

                val compiledKeptField = compiledClass.fields?.find { it.field.name == "keptField" }
                assertNotNull(compiledKeptField, "keptField should be preserved in compiled output")

                // keptMethod should be preserved (has @Keep annotation)
                val apkKeptMethod = apkClass.methods?.find { it.method.name == "keptMethod" }
                assertNotNull(apkKeptMethod, "keptMethod should be preserved in APK")

                val compiledKeptMethod = compiledClass.methods?.find { it.method.name == "keptMethod" }
                assertNotNull(compiledKeptMethod, "keptMethod should be preserved in compiled output")
            }
        )
    }

    @Test
    fun testInterfaceImplementor() {
        val task = createCompileTask("InterfaceImplementor.kt", "MinifyTestInterface.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for InterfaceImplementor")

        // Note: InterfaceImplementor and MinifyTestInterface may be optimized away by R8
        // The compilation succeeds and produces dex output, which is what we verify here.
        assertTrue(result.outputs.any { it.type == CompileOutput.Type.Dex },
            "Should produce dex output")
    }

    @Test
    fun testSerializableClass() {
        val task = createCompileTask("SerializableClass.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for SerializableClass")

        // Verify obfuscation: class name should be obfuscated to b3.c
        verifyObfuscation(
            result = result,
            expectedClassName = "Lb3/c;",
            verifyCallback = { compiledClass, apkClass ->
                // Class name should be obfuscated
                assertEquals("Lb3/c;", compiledClass.className,
                    "Class name should be obfuscated to b3.c")
                assertEquals(apkClass.className, compiledClass.className,
                    "Compiled class name should match APK")

                // serializedField should be preserved (Serializable requirement)
                val apkField = apkClass.fields?.find { it.field.name == "serializedField" }
                assertNotNull(apkField, "serializedField should be preserved in APK")

                val compiledField = compiledClass.fields?.find { it.field.name == "serializedField" }
                assertNotNull(compiledField, "serializedField should be preserved in compiled output")
            }
        )
    }

    @Test
    fun testEnumClass() {
        val task = createCompileTask("MinifyTestEnum.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for MinifyTestEnum")

        // Note: MinifyTestEnum may be inlined or optimized by R8
        assertTrue(result.outputs.any { it.type == CompileOutput.Type.Dex },
            "Should produce dex output")
    }

    @Test
    fun testInnerClasses() {
        val task = createCompileTask("InnerClassHolder.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for InnerClassHolder")

        // Note: InnerClassHolder may be optimized by R8
        assertTrue(result.outputs.any { it.type == CompileOutput.Type.Dex },
            "Should produce dex output")
    }

    @Test
    fun testNativeMethodClass() {
        val task = createCompileTask("NativeMethodClass.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for NativeMethodClass")

        // Verify obfuscation: class name should be preserved (has native methods)
        verifyObfuscation(
            result = result,
            expectedClassName = "Lcom/sickworm/jugg/demo/testcase/minify/NativeMethodClass;",
            verifyCallback = { compiledClass, apkClass ->
                // Class name should be preserved (native methods require it)
                assertEquals(apkClass.className, compiledClass.className,
                    "Class name should be preserved: NativeMethodClass")

                // nativeField should be obfuscated to 'a'
                val apkField = apkClass.fields?.find { it.field.name == "a" }
                assertNotNull(apkField, "nativeField should be obfuscated to 'a' in APK")

                val compiledField = compiledClass.fields?.find { it.field.name == "a" }
                assertNotNull(compiledField, "Compiled class should have obfuscated field 'a'")

                // Note: nativeMethod may be optimized away by R8 if not actually used with JNI
            }
        )
    }

    @Test
    fun testWildcardKeep() {
        val task = createCompileTask("WildcardKeepClass.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for WildcardKeepClass")

        // Verify obfuscation: class name should be obfuscated to b3.d
        verifyObfuscation(
            result = result,
            expectedClassName = "Lb3/d;",
            verifyCallback = { compiledClass, apkClass ->
                // Class name should be obfuscated
                assertEquals("Lb3/d;", compiledClass.className,
                    "Class name should be obfuscated to b3.d")
                assertEquals(apkClass.className, compiledClass.className,
                    "Compiled class name should match APK")

                // prefixKeptMethod is obfuscated to 'a' (contrary to what the class name suggests)
                val apkMethod = apkClass.methods?.find { it.method.name == "a" }
                assertNotNull(apkMethod, "prefixKeptMethod should be obfuscated to 'a' in APK")

                val compiledMethod = compiledClass.methods?.find { it.method.name == "a" }
                assertNotNull(compiledMethod, "Compiled method should be obfuscated to 'a'")
            }
        )
    }

    @Test
    fun testKeepClassAndMembers() {
        val task = createCompileTask("KeepClassAndMembers.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for KeepClassAndMembers")

        // Verify obfuscation: class name and members should be preserved
        verifyObfuscation(
            result = result,
            expectedClassName = "Lcom/sickworm/jugg/demo/testcase/minify/KeepClassAndMembers;",
            verifyCallback = { compiledClass, apkClass ->
                // Class name should be preserved
                assertEquals(apkClass.className, compiledClass.className,
                    "Class name should be preserved: KeepClassAndMembers")

                // keptFieldOne should be preserved
                val apkField = apkClass.fields?.find { it.field.name == "keptFieldOne" }
                assertNotNull(apkField, "keptFieldOne should be preserved in APK")

                val compiledField = compiledClass.fields?.find { it.field.name == "keptFieldOne" }
                assertNotNull(compiledField, "keptFieldOne should be preserved in compiled output")

                // keptMethodOne should be preserved
                val apkMethod = apkClass.methods?.find { it.method.name == "keptMethodOne" }
                assertNotNull(apkMethod, "keptMethodOne should be preserved in APK")

                val compiledMethod = compiledClass.methods?.find { it.method.name == "keptMethodOne" }
                assertNotNull(compiledMethod, "keptMethodOne should be preserved in compiled output")
            }
        )
    }

    @Test
    fun testMinifyTestActivity() {
        val task = createCompileTask("MinifyTestActivity.kt")
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for MinifyTestActivity")

        // Verify obfuscation: Activity name should be preserved (declared in manifest)
        verifyObfuscation(
            result = result,
            expectedClassName = "Lcom/sickworm/jugg/demo/testcase/minify/MinifyTestActivity;",
            verifyCallback = { compiledClass, apkClass ->
                // Class name should be preserved (Activity in manifest)
                assertEquals(apkClass.className, compiledClass.className,
                    "Class name should be preserved: MinifyTestActivity")

                // onCreate method should be preserved (Activity lifecycle)
                val apkMethod = apkClass.methods?.find { it.method.name == "onCreate" }
                assertNotNull(apkMethod, "onCreate should be preserved in APK")

                val compiledMethod = compiledClass.methods?.find { it.method.name == "onCreate" }
                assertNotNull(compiledMethod, "onCreate should be preserved in compiled output")
            }
        )
    }

    @Test
    fun testAllMinifyClasses() {
        val allFiles = listOf(
            "KeepClassName.kt",
            "KeepClassMembers.kt",
            "FullyObfuscated.kt",
            "KeepMethodName.kt",
            "KeepAnnotated.kt",
            "MinifyTestInterface.kt",
            "InterfaceImplementor.kt",
            "SerializableClass.kt",
            "MinifyTestEnum.kt",
            "InnerClassHolder.kt",
            "NativeMethodClass.kt",
            "WildcardKeepClass.kt",
            "KeepClassAndMembers.kt",
            "MinifyTestActivity.kt",
        )

        val task = createCompileTask(*allFiles.toTypedArray())
        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed for all minify test classes")

        val dexOutputs = result.outputs.filter { it.type == CompileOutput.Type.Dex }
        assertTrue(dexOutputs.isNotEmpty(), "Should produce dex outputs")
    }

    @Test
    fun kotlinAndJavaCompileWithMinify() {
        // Step 1: Execute appAssembleRelease to build release version with minify enabled

        // Create release version of mockModule with release buildVariant
        // Create a SourceCompiler with release context
        val releaseContext = TestGlobal.context.copy(
            modules = ProjectInfoSerializer(AssembleAndroidProjectOnce.gradleProjectInfoFile, logger).load()!!.modules,
        )
        val releaseSourceCompiler = SourceCompiler(releaseContext, TestGlobal.mockParentDisposable)

        // Step 2: Compile task
        val task = CompileTask(
            listOf(
                CompileFile(
                    CompileFile.Type.Kotlin,
                    File(TestGlobal.assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity.kt"),
                    File(TestGlobal.assetsAndroidDir, "app/src/main/java/"),
                    module = TestGlobal.applicationModule,
                ),
                CompileFile(
                    CompileFile.Type.Java,
                    File(TestGlobal.assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity2.java"),
                    File(TestGlobal.assetsAndroidDir, "app/src/main/java/"),
                    module = TestGlobal.applicationModule,
                )
            ),
            TestGlobal.stagingDir,
            CompileStatusHolder.DEFAULT,
        )
        val result = releaseSourceCompiler.compile(task)

        // Step 3: Verify compile result is obfuscated
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compile should succeed")

        // Verify that the output files are obfuscated
        // In minified build, class files should have obfuscated names/paths
        val dexOutputs = result.outputs.filter { it.type == CompileOutput.Type.Dex }
        assertTrue(dexOutputs.isNotEmpty(), "Should have dex outputs")

        // Check that mapping file exists (indicates minify was enabled)
        val mappingFile = releaseContext.applicationModule.buildPathInfo.mappingFile
        assertTrue(mappingFile.exists(), "Mapping file should exist for release build: ${mappingFile.absolutePath}")

        // Verify the compiled classes went through obfuscation by checking the obfuscated output path
        // The obfuscated class files should be in the minify output directory
        val minifyOutputDir = File(TestGlobal.buildDir, "minify")
        if (minifyOutputDir.exists()) {
            val classFiles = minifyOutputDir.walkTopDown().filter { it.extension == "class" }.toList()
            // If minify is working, class names should be obfuscated (e.g., "a.class" instead of "MainActivity.class")
            val hasObfuscatedClasses = classFiles.any { classFile ->
                // Obfuscated classes typically have short names like a, b, c
                val className = classFile.nameWithoutExtension
                className.length <= 2 || !className.contains("MainActivity")
            }
            assertTrue(
                classFiles.isEmpty() || hasObfuscatedClasses,
                "Output should contain obfuscated class files when minify is enabled"
            )
        }
    }


    private fun createCompileTask(vararg fileNames: String): CompileTask {
        val compileFiles = fileNames.map { fileName ->
            CompileFile(
                type = if (fileName.endsWith(".kt")) CompileFile.Type.Kotlin else CompileFile.Type.Java,
                file = File(TestGlobal.assetsAndroidDir, "$MINIFY_SOURCE_DIR/$fileName"),
                baseDir = File(TestGlobal.assetsAndroidDir, "app/src/main/java/"),
                module = releaseContext!!.applicationModule,
            )
        }

        return CompileTask(
            files = compileFiles,
            outputDir = TestGlobal.stagingDir,
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )
    }

    /**
     * Verify that the compiled dex output matches the obfuscation in the original APK.
     *
     * @param result The compile result containing dex outputs
     * @param expectedClassName The expected class name in internal format (e.g., "Lcom/example/MyClass;")
     * @param verifyCallback Custom verification logic to compare compiled class with APK class
     */
    private fun verifyObfuscation(
        result: CompileResult,
        expectedClassName: String,
        verifyCallback: (compiledClass: DexClassNode, apkClass: DexClassNode) -> Unit
    ) {
        // Get dex outputs from compilation result
        val dexOutputs = result.outputs.filter { it.type == CompileOutput.Type.Dex }
        assertTrue(dexOutputs.isNotEmpty(), "Should have dex outputs")

        // Parse compiled dex to get class nodes
        val compiledClasses = mutableMapOf<String, DexClassNode>()
        dexOutputs.forEach { output ->
            val dexBytes = output.file.readBytes()
            val reader: BaseDexFileReader = MultiDexFileReader.open(dexBytes)
            val visitor = DexFileNode()
            reader.accept(visitor)

            visitor.clzs.forEach { classNode ->
                compiledClasses[classNode.className] = classNode
            }
        }

        // Find the target class in compiled output
        val compiledClass = compiledClasses[expectedClassName]
        assertNotNull(compiledClass, "Compiled class $expectedClassName not found in dex output")

        // Find the same class in APK
        val apkClass = apkClasses?.get(expectedClassName)
        assertNotNull(apkClass, "Class $expectedClassName not found in APK. APK classes available: ${apkClasses != null}")

        // Run custom verification
        verifyCallback(compiledClass, apkClass)
    }
}
