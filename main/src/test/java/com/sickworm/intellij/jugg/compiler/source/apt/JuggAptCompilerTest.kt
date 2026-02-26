package com.sickworm.intellij.jugg.compiler.source.apt

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.changeBaseDir
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

        val shadowEntryFile = toShadowGeneratedFile(context, module, entryFile)
        val shadowEntryContent = shadowEntryFile.readText()
        val entryContent = entryFile.readText()
        assertTrue(result.isAllSuccess)
        assertTrue(shadowEntryFile.exists())
        assertFalse(entryContent.contains("""BridgeManager.registerPageRouter("page_a")"""))
        assertTrue(shadowEntryContent.contains("""BridgeManager.registerPageRouter("page_a")"""))
        assertTrue(shadowEntryContent.contains("com.test.moduleA.PageA()"))
        assertTrue(result.outputs.any { it.file.absolutePath == shadowEntryFile.absolutePath && it.type == CompileOutput.Type.Kotlin })
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
        val shadowEntryFile = toShadowGeneratedFile(context, module, entryFile)
        val shadowEntryContent = shadowEntryFile.readText()

        assertEquals(1, shadowEntryContent.countOccurrences("""BridgeManager.registerPageRouter("page_a")"""))
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
        val shadowEntryFile = toShadowGeneratedFile(context, module, entryFile)

        assertTrue(result.isAllSuccess)
        assertTrue(result.outputs.any { it.file.absolutePath == shadowEntryFile.absolutePath })
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

        val shadowEntryA = toShadowGeneratedFile(context, moduleA, entryA)
        val shadowEntryB = toShadowGeneratedFile(context, moduleB, entryB)
        assertFalse(entryA.readText().contains("""BridgeManager.registerPageRouter("page_a")"""))
        assertFalse(entryB.readText().contains("""BridgeManager.registerPageRouter("page_a")"""))
        assertTrue(shadowEntryA.readText().contains("""BridgeManager.registerPageRouter("page_a")"""))
        assertFalse(shadowEntryB.exists())
    }

    @Test
    fun kuiklyPage_shouldSkipJavaEntryRewrite() {
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
        val shadowJavaEntryFile = toShadowGeneratedFile(context, module, javaEntryFile)
        if (shadowJavaEntryFile.exists()) {
            assertFalse(shadowJavaEntryFile.readText().contains("""BridgeManager.registerPageRouter("page_java""""))
        }
        assertFalse(javaEntryFile.readText().contains("""BridgeManager.registerPageRouter("page_java""""))
        assertTrue(result.outputs.isEmpty())
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

    private fun toShadowGeneratedFile(context: SimpleCompileContext, module: ModuleInfo, generatedFile: File): File {
        val shadowGeneratedRoot = context.backupGradleDir(
            module.buildPathInfo.generatedSourcePath,
            dryRun = true,
        )
        return generatedFile.changeBaseDir(module.buildPathInfo.generatedSourcePath, shadowGeneratedRoot)
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
                import com.tencent.kuikly.core.annotations.Page

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
                package com.tencent.kuikly.core.android

                object KuiklyCoreEntry {
                    public override fun triggerRegisterPages(): Unit {
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
                //
                // this file is generating by ksp
                // please do not modified it!!!
                package com.tencent.kuikly.core.android
                
                import com.tencent.kuikly.core.IKuiklyCoreEntry
                import com.tencent.kuikly.core.IKuiklyCoreEntry.Delegate
                import com.tencent.kuikly.core.manager.BridgeManager
                import com.tencent.kuikly.core.nvi.NativeBridge
                import kotlin.Any
                import kotlin.Boolean
                import kotlin.Int
                import kotlin.Unit
                
                public class KuiklyCoreEntry : IKuiklyCoreEntry {
                  private var hadRegisterNativeBridge: Boolean = false
                
                  public override var `delegate`: Delegate? = null
                
                  public override fun callKotlinMethod(
                    methodId: Int,
                    arg0: Any?,
                    arg1: Any?,
                    arg2: Any?,
                    arg3: Any?,
                    arg4: Any?,
                    arg5: Any?
                  ): Unit {
                    if (!hadRegisterNativeBridge) {
                
                    triggerRegisterPages()
                
                              hadRegisterNativeBridge = true
                                  val nativeBridge = NativeBridge()
                                  nativeBridge.delegate = object : NativeBridge.NativeBridgeDelegate {
                                      override fun callNative(
                                            methodId: Int,
                                            arg0: Any?,
                                            arg1: Any?,
                                            arg2: Any?,
                                            arg3: Any?,
                                            arg4: Any?,
                                            arg5: Any?
                                      ): Any? {
                                          return delegate?.callNative(methodId, arg0, arg1, arg2, arg3, arg4,
                                                arg5)
                                      }
                                  }
                                  BridgeManager.registerNativeBridge(arg0 as String, nativeBridge)
                              }
                    BridgeManager.callKotlinMethod(methodId, arg0, arg1, arg2, arg3, arg4, arg5)
                  }
                
                  public override fun triggerRegisterPages(): Unit {
                    BridgeManager.registerPageRouter("vi_router") {
                        com.tencent.kuiklydemo.pages.RouterPage()
                    }
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
