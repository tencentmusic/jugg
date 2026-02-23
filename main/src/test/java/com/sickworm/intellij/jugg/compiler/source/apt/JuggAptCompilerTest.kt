package com.sickworm.intellij.jugg.compiler.source.apt

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JuggAptCompilerTest validates custom generated-source rewrite behaviors:
 * fail-open execution, Kuikly page aggregation append, idempotence, and module isolation.
 */
class JuggAptCompilerTest {

    private lateinit var testRoot: File

    @Before
    fun setUp() {
        testRoot = File(TestGlobal.buildDir, "jugg_apt_compiler_test")
        testRoot.deleteRecursively()
        testRoot.mkdirs()
    }

    @Test
    fun kuiklyPage_shouldAppendRegistrationForMissingPage() {
        val module = createModule("moduleA")
        val context = createContext(module)
        val compiler = JuggAptCompiler(context, TestGlobal.mockParentDisposable)

        val pageFile = createKotlinPageSource(module, className = "PageA", route = "page_a")
        val entryFile = createKotlinEntry(module)

        val task = createTask(pageFile)
        val result = compiler.compile(task)

        val entryContent = entryFile.readText()
        assertTrue(result.isAllSuccess)
        assertTrue(entryContent.contains("""BridgeManager.registerPageRouter("page_a")"""))
        assertTrue(entryContent.contains("com.test.moduleA.PageA()"))
        assertTrue(result.outputs.any { it.file.absolutePath == entryFile.absolutePath && it.type == CompileOutput.Type.Kotlin })
    }

    @Test
    fun kuiklyPage_shouldBeIdempotentAcrossRepeatedCompile() {
        val module = createModule("moduleA")
        val context = createContext(module)
        val compiler = JuggAptCompiler(context, TestGlobal.mockParentDisposable)

        val pageFile = createKotlinPageSource(module, className = "PageA", route = "page_a")
        val entryFile = createKotlinEntry(module)

        compiler.compile(createTask(pageFile))
        val secondResult = compiler.compile(createTask(pageFile))
        val entryContent = entryFile.readText()

        assertEquals(1, entryContent.countOccurrences("""BridgeManager.registerPageRouter("page_a")"""))
        assertTrue(secondResult.outputs.isEmpty(), "Second compile should not emit rewrite outputs for idempotent content.")
    }

    @Test
    fun kuiklyPage_shouldSkipWhenNoPageAnnotation() {
        val module = createModule("moduleA")
        val context = createContext(module)
        val compiler = JuggAptCompiler(context, TestGlobal.mockParentDisposable)

        val normalFile = createPlainKotlinSource(module, className = "NormalClass")
        val entryFile = createKotlinEntry(module)
        val before = entryFile.readText()

        val result = compiler.compile(createTask(normalFile))

        assertTrue(result.outputs.isEmpty())
        assertEquals(before, entryFile.readText())
    }

    @Test
    fun juggAptCompiler_shouldFailOpenWhenProcessorThrows() {
        val module = createModule("moduleA")
        val context = createContext(module)
        val entryFile = createKotlinEntry(module)
        val compileFile = createPlainKotlinSource(module, className = "NormalClass")
        val compiler = JuggAptCompiler(
            context = context,
            parent = TestGlobal.mockParentDisposable,
            processors = listOf(
                ThrowingProcessor(),
                ReturnFirstGeneratedProcessor(),
            ),
        )

        val result = compiler.compile(createTask(compileFile))

        assertTrue(result.isAllSuccess)
        assertTrue(result.outputs.any { it.file.absolutePath == entryFile.absolutePath })
    }

    @Test
    fun juggAptCompiler_shouldNotRewriteOtherModuleGeneratedFiles() {
        val moduleA = createModule("moduleA")
        val moduleB = createModule("moduleB")
        val context = createContext(moduleA, moduleB)
        val compiler = JuggAptCompiler(context, TestGlobal.mockParentDisposable)

        val pageFileA = createKotlinPageSource(moduleA, className = "PageA", route = "page_a")
        val entryA = createKotlinEntry(moduleA)
        val entryB = createKotlinEntry(moduleB)

        compiler.compile(createTask(pageFileA))

        assertTrue(entryA.readText().contains("""BridgeManager.registerPageRouter("page_a")"""))
        assertFalse(entryB.readText().contains("""BridgeManager.registerPageRouter("page_a")"""))
    }

    @Test
    fun kuiklyPage_shouldSupportJavaEntryRewrite() {
        val module = createModule("moduleA")
        val context = createContext(module)
        val compiler = JuggAptCompiler(context, TestGlobal.mockParentDisposable)

        val pageFile = createKotlinPageSource(
            module = module,
            className = "PageJava",
            route = "page_java",
            annotation = """@Page(route = "page_java")""",
        )
        val javaEntryFile = createJavaEntry(module)

        val result = compiler.compile(createTask(pageFile))
        val content = javaEntryFile.readText()

        assertTrue(content.contains("""BridgeManager.registerPageRouter("page_java""""))
        assertTrue(content.contains("new com.test.moduleA.PageJava();"))
        assertTrue(result.outputs.any { it.file.absolutePath == javaEntryFile.absolutePath && it.type == CompileOutput.Type.Java })
    }

    private fun createContext(vararg modules: ModuleInfo): SimpleCompileContext {
        val base = TestGlobal.context
        val moduleMap = linkedMapOf<String, ModuleInfo>()
        modules.forEach { moduleMap[it.name] = it }
        return base.copy(
            tempCompileDir = File(testRoot, "compiled"),
            tempModuleDir = File(testRoot, "temp_module"),
            projectDir = testRoot,
            incrementalDataDir = File(testRoot, "incremental"),
            modules = moduleMap,
        )
    }

    private fun createModule(name: String): ModuleInfo {
        val moduleRoot = File(testRoot, name)
        val sourceDir = File(moduleRoot, "src/main/java").apply { mkdirs() }
        return TestGlobal.mockModule.copy(
            name = name,
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = moduleRoot,
            projectRootDir = testRoot,
            sourceDirs = listOf(sourceDir),
            resourceDirs = emptyList(),
            assetsDirs = emptyList(),
            manifestFile = null,
            buildPathInfo = ModuleBuildPathInfo(
                projectRootDir = testRoot,
                moduleRootDir = moduleRoot,
                buildVariant = ModuleInfo.DEFAULT_BUILD_VARIANT,
            ),
            moduleDependencies = emptyList(),
            libraryDependencies = emptyList(),
            runtimeLibraryDependencies = emptyList(),
            annotationProcessorDependencies = emptyList(),
            kaptDependencies = emptyList(),
            kspDependencies = emptyList(),
            kotlinPlugins = emptyList(),
            kotlinExtensions = emptyList(),
        )
    }

    private fun createTask(vararg files: CompileFile): CompileTask {
        return CompileTask(files.toList(), File(testRoot, "staging"), CompileStatusHolder.DEFAULT)
    }

    private fun createKotlinPageSource(
        module: ModuleInfo,
        className: String,
        route: String,
        annotation: String = """@Page("$route")""",
    ): CompileFile {
        val sourceBaseDir = module.sourceDirs.first()
        val pageFile = File(sourceBaseDir, "com/test/${module.name}/$className.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.test.${module.name}

                $annotation
                class $className
                """.trimIndent()
            )
        }
        return CompileFile(CompileFile.Type.Kotlin, pageFile, sourceBaseDir, module)
    }

    private fun createPlainKotlinSource(module: ModuleInfo, className: String): CompileFile {
        val sourceBaseDir = module.sourceDirs.first()
        val sourceFile = File(sourceBaseDir, "com/test/${module.name}/$className.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.test.${module.name}

                class $className
                """.trimIndent()
            )
        }
        return CompileFile(CompileFile.Type.Kotlin, sourceFile, sourceBaseDir, module)
    }

    private fun createKotlinEntry(module: ModuleInfo): File {
        return File(module.buildPathInfo.generatedSourcePath, "ksp/debug/kotlin/KuiklyCoreEntry.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.test.${module.name}.generated

                object KuiklyCoreEntry {
                    fun triggerRegisterPages() {
                    }
                }
                """.trimIndent()
            )
        }
    }

    private fun createJavaEntry(module: ModuleInfo): File {
        return File(module.buildPathInfo.generatedSourcePath, "ksp/debug/java/KuiklyCoreEntry.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.test.${module.name}.generated;

                public class KuiklyCoreEntry {
                    public static void triggerRegisterPages() {
                    }
                }
                """.trimIndent()
            )
        }
    }

    private fun String.countOccurrences(token: String): Int {
        if (token.isEmpty()) {
            return 0
        }
        var count = 0
        var currentIndex = 0
        while (true) {
            val nextIndex = indexOf(token, currentIndex)
            if (nextIndex < 0) {
                return count
            }
            count++
            currentIndex = nextIndex + token.length
        }
    }

    private class ThrowingProcessor : IJuggAptProcessor {
        override val id: String = "throwing-processor"

        override fun process(
            context: com.sickworm.intellij.jugg.compiler.ICompileContext,
            module: ModuleInfo,
            allCompileFiles: List<CompileFile>,
            generatedAptFiles: List<CompileFile>,
        ): List<CompileFile> {
            error("intentional fail for fail-open test")
        }
    }

    private class ReturnFirstGeneratedProcessor : IJuggAptProcessor {
        override val id: String = "return-first-processor"

        override fun process(
            context: com.sickworm.intellij.jugg.compiler.ICompileContext,
            module: ModuleInfo,
            allCompileFiles: List<CompileFile>,
            generatedAptFiles: List<CompileFile>,
        ): List<CompileFile> {
            return generatedAptFiles.take(1)
        }
    }
}

