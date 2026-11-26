package com.sickworm.intellij.jugg.compiler.obfuscation

import com.googlecode.d2j.DexConstants
import com.googlecode.d2j.Method
import com.googlecode.d2j.Proto
import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.Op
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.DefaultLogger
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.info.Variant
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that the minify stage follows the selected variant `minifyEnabled` config instead of the
 * presence of `outputs/mapping/<variant>/mapping.txt`.
 *
 * A mapping file left behind by an earlier minified build must not turn a non-minified variant into
 * a minified one: the incremental output would keep the obfuscated naming of a previous APK and
 * crash at runtime. The opposite direction is a correctness contract too: when the variant really
 * enables minify, a missing mapping file must fail the compile instead of silently deploying
 * un-obfuscated classes.
 */
class DexMinifyCompilerVariantMinifyTest {

    private val originalClassName = "Lcom/example/MyClass;"
    private val obfuscatedClassName = "La/b;"
    private val mappingContent = """
        com.example.MyClass -> a.b:
            void doSomething() -> c
    """.trimIndent()

    private lateinit var projectRootDir: File
    private lateinit var outputDir: File
    private lateinit var inputBaseDir: File
    private lateinit var logger: RecordingLogger

    private val parentDisposable = object : Disposable {
        override fun dispose() = Unit
    }

    @Before
    fun setUp() {
        projectRootDir = File(
            System.getProperty("java.io.tmpdir"),
            "jugg_variant_minify_test_${System.currentTimeMillis()}",
        )
        projectRootDir.mkdirs()
        outputDir = File(projectRootDir, "output").apply { mkdirs() }
        inputBaseDir = File(projectRootDir, "input").apply { mkdirs() }
        logger = RecordingLogger()
    }

    @After
    fun tearDown() {
        projectRootDir.deleteRecursively()
    }

    @Test
    fun `variant without minify keeps un-obfuscated output even when a stale mapping file exists`() {
        val module = appModule(buildVariant = "debug", minifyEnabled = false)
        // The stale mapping left behind by an earlier minified build of the same variant.
        writeMappingFile(module)

        val result = compileDex(module, createDexFile("MyClass.dex"))

        assertTrue(result.isAllSuccess, "Skipping minify must not fail the compile")
        val classNames = result.dexOutputClassNames()
        assertTrue(
            classNames.contains(originalClassName),
            "Output must keep the original class name $originalClassName, but found: $classNames",
        )
        assertFalse(
            classNames.contains(obfuscatedClassName),
            "Output must not apply the stale mapping, but found: $classNames",
        )
    }

    @Test
    fun `variant with minify fails the dex compile when the mapping file is missing`() {
        val module = appModule(buildVariant = "release", minifyEnabled = true)

        val result = compileDex(module, createDexFile("MyClass.dex"))

        assertFalse(result.isAllSuccess, "Missing mapping must not be reported as success")
        assertTrue(result.failedFiles.isNotEmpty(), "Missing mapping must fail the compile task files")
        assertTrue(logger.warnings.isNotEmpty(), "Missing mapping must produce a user visible warning")
    }

    @Test
    fun `variant with minify fails the class compile when the mapping file is missing`() {
        val module = appModule(buildVariant = "release", minifyEnabled = true)
        val classFile = File(inputBaseDir, "com/example/MyClass.class").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        }
        val task = CompileTask(
            files = listOf(CompileFile(CompileFile.Type.Class, classFile, inputBaseDir, module)),
            outputDir = outputDir,
            CompileStatusHolder.DEFAULT,
        )

        val result = ClassMinifyCompiler(contextOf(module), parentDisposable).compile(task)

        assertFalse(result.isAllSuccess, "Missing mapping must not be reported as success")
        assertTrue(logger.warnings.isNotEmpty(), "Missing mapping must produce a user visible warning")
    }

    private fun compileDex(module: ModuleInfo, dexFile: File): CompileResult {
        val task = CompileTask(
            files = listOf(CompileFile(CompileFile.Type.Dex, dexFile, inputBaseDir, module)),
            outputDir = outputDir,
            CompileStatusHolder.DEFAULT,
        )
        return DexMinifyCompiler(contextOf(module), parentDisposable).compile(task)
    }

    private fun contextOf(module: ModuleInfo): SimpleCompileContext {
        return SimpleCompileContext(
            logger = logger,
            tempCompileDir = File(projectRootDir, "compiled"),
            tempModuleDir = File(projectRootDir, "temp_module"),
            androidHome = File(projectRootDir, "sdk"),
            androidJar = File(projectRootDir, "sdk/platforms/android-30/android.jar"),
            modules = mapOf(module.name to module),
            apkInfos = listOf(ApkInfo(File(projectRootDir, "app-debug.apk"), "com.example.app")),
            projectDir = projectRootDir,
            incrementalDataDir = File(projectRootDir, "incremental"),
            deployedFiles = mutableListOf(),
        )
    }

    /**
     * Builds an application module whose selected variant carries [minifyEnabled] and whose other
     * variant carries the opposite value, so a passing test also proves the variant is selected.
     */
    private fun appModule(buildVariant: String, minifyEnabled: Boolean): ModuleInfo {
        val moduleRootDir = File(projectRootDir, "app")
        return ModuleInfo(
            name = "app",
            moduleType = ModuleInfo.Type.Application,
            moduleRootDir = moduleRootDir,
            projectRootDir = projectRootDir,
            sourceDirs = emptyList(),
            resourceDirs = emptyList(),
            assetsDirs = emptyList(),
            manifestFile = null,
            manifestPlaceHolders = null,
            buildVariant = buildVariant,
            compileVersion = null,
            minSdkVersion = "21",
            buildToolsVersion = null,
            kotlinJvmTarget = null,
            kotlinFreeCompilerArgs = emptyList(),
            javaSourceCompatibility = null,
            javaTargetCompatibility = null,
            buildPathInfo = ModuleBuildPathInfo(
                projectRootDir,
                moduleRootDir,
                buildVariant,
                buildDirRelativePath = "app/build",
            ),
            moduleDependencies = emptyList(),
            libraryDependencies = emptyList(),
            runtimeLibraryDependencies = emptyList(),
            annotationProcessorDependencies = emptyList(),
            kaptDependencies = emptyList(),
            kotlinPluginOptions = emptyList(),
            externalBuildInfos = emptyList(),
            variants = listOf("debug", "release").map { name ->
                Variant(
                    name = name,
                    signingConfigName = null,
                    minifyEnabled = if (name == buildVariant) minifyEnabled else !minifyEnabled,
                )
            },
        )
    }

    private fun writeMappingFile(module: ModuleInfo) {
        val mappingFile = module.buildPathInfo.mappingFile
        mappingFile.parentFile.mkdirs()
        mappingFile.writeText(mappingContent)
    }

    /** Creates a minimal DEX file containing a single class with the original class name. */
    private fun createDexFile(name: String): File {
        val dexWriter = DexFileWriter()
        val classVisitor = dexWriter.visit(DexConstants.ACC_PUBLIC, originalClassName, "Ljava/lang/Object;", null)
        val methodVisitor = classVisitor.visitMethod(
            DexConstants.ACC_PUBLIC,
            Method(originalClassName, "doSomething", Proto(emptyArray(), "V")),
        )
        val codeVisitor = methodVisitor.visitCode()
        codeVisitor.visitRegister(1)
        codeVisitor.visitStmt0R(Op.RETURN_VOID)
        codeVisitor.visitEnd()
        methodVisitor.visitEnd()
        classVisitor.visitEnd()
        dexWriter.visitEnd()

        val outputFile = File(inputBaseDir, "com/example/$name")
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(dexWriter.toByteArray())
        return outputFile
    }

    private fun CompileResult.dexOutputClassNames(): Set<String> {
        return outputs.filter { it.type == CompileOutput.Type.Dex }.flatMap { output ->
            val classNames = mutableSetOf<String>()
            DexFileReader(output.file.readBytes()).accept(object : DexFileVisitor() {
                override fun visit(
                    accessFlags: Int,
                    className: String,
                    superClass: String?,
                    interfaceNames: Array<out String>?,
                ): DexClassVisitor? {
                    classNames.add(className)
                    return null
                }
            }, 0)
            classNames
        }.toSet()
    }

    /** Records warn-level messages so a test can assert the user visible signal without binding its text. */
    private class RecordingLogger : DefaultLogger("JuggTest") {

        val warnings = mutableListOf<String>()

        override fun isDebugEnabled(): Boolean = false

        override fun warn(message: String?, t: Throwable?) {
            warnings.add(message.orEmpty())
        }
    }
}
