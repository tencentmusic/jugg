package com.sickworm.intellij.jugg.compiler.source.apt

import com.intellij.openapi.Disposable
import com.android.tools.build.jetifier.core.config.ConfigParser
import com.android.tools.build.jetifier.processor.FileMapping
import com.android.tools.build.jetifier.processor.Processor
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ICompiler
import com.sickworm.intellij.jugg.compiler.file
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.data.KspInfo
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.ChangedFile
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.Mockito
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the built-in APT pipeline with the project's real ARouter processor artifacts. */
class ARouterJuggAptProcessorTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: ICompileContext
    private lateinit var module: ModuleInfo
    private lateinit var source: CompileFile
    private lateinit var compiler: JuggAptCompiler
    private lateinit var processorJars: List<File>
    private lateinit var libraryJars: List<File>
    private lateinit var supportFragment: File

    @Before
    fun setUp() {
        processorJars = System.getProperty("arouter.test.processors").split(File.pathSeparator).map(::File)
        libraryJars = System.getProperty("arouter.test.libraries").split(File.pathSeparator).map(::File).map { file ->
            if (file.extension != "aar") file else JarFile(file).use { jar ->
                File(temporary.root, "${file.nameWithoutExtension}.jar").apply {
                    writeBytes(jar.getInputStream(jar.getJarEntry("classes.jar")).use { it.readBytes() })
                }
            }
        }
        supportFragment = libraryJars.single { it.name.startsWith("support-fragment") }
        libraryJars = libraryJars - supportFragment
        val sources = temporary.newFolder("src")
        module = ModuleInfo.virtualModule.copy(
            name = "routes", projectRootDir = temporary.root, moduleRootDir = temporary.root,
            sourceDirs = listOf(sources),
            buildPathInfo = ModuleBuildPathInfo(temporary.root, temporary.root, "debug", buildDirRelativePath = "build"),
            annotationProcessorDependencies = processorJars.map { LibraryDependency(it.name, it) },
            javaAnnotationProcessorOptions = mapOf("AROUTER_MODULE_NAME" to "routes"),
        )
        context = mock {
            on { logger } doReturn StdLogger("ARouterTest")
            on { tempCompileDir } doReturn temporary.newFolder("work")
            on { incrementalDataDir } doReturn temporary.newFolder("incremental")
            on { customCompilers } doReturn emptyList()
            on { modulesWithOrder } doReturn listOf(module)
            on { modules } doReturn mapOf(module.name to module)
            on { isSingleApk } doReturn true
            on { tempModule } doReturn ModuleInfo.virtualModule
            on { tempModuleDir } doReturn File(temporary.root, "temp-module")
            on { cmdCompileEnv } doReturn emptyList()
            on { projectDir } doReturn temporary.root
        }
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(emptyList())
        whenever(context.containsApkClass(any())).thenReturn(emptyList())
        val androidJar = File(System.getenv("ANDROID_HOME"), "platforms/android-30/android.jar")
        assertTrue(androidJar.isFile, "Tests require Android SDK platform 30")
        whenever(context.androidHome).thenReturn(File(System.getenv("ANDROID_HOME")))
        whenever(context.androidJar).thenReturn(androidJar)
        whenever(context.getModuleDependencies(any(), any())).thenAnswer {
            listOf(androidJar.path) + libraryJars.map { it.path } + processorJars.map { it.path }
        }
        source = writeSource("@Route(path = \"/demo/first\")")
        compiler = JuggAptCompiler(context, object : Disposable { override fun dispose() = Unit })
    }

    @Test
    fun projectKsp1GeneratesKotlinRoutesWithoutAptArtifacts() = projectKspGeneratesRoutes(false)

    @Test
    fun projectKsp2GeneratesKotlinRoutesWithoutAptArtifacts() = projectKspGeneratesRoutes(true)

    @Test
    fun projectKsp1MixedSourcesAndGeneratedKotlinCompileToDex() = projectKspMixedSourcesCompileToDex(false)

    @Test
    fun projectKsp2MixedSourcesAndGeneratedKotlinCompileToDex() = projectKspMixedSourcesCompileToDex(true)

    private fun projectKspMixedSourcesCompileToDex(ksp2: Boolean) {
        val common = configureProjectKsp(ksp2)
        val android = source.baseDir
        val java = File(android, "demo/JavaScreen.java").apply { parentFile.mkdirs(); writeText("""
            package demo;
            @com.alibaba.android.arouter.facade.annotation.Route(path = "/demo/java")
            public class JavaScreen extends android.app.Activity {
                public Screen kotlinScreen() { return new Screen(); }
            }
        """.trimIndent()) }
        source.file.writeText(source.file.readText().replace("class Screen : android.app.Activity()",
            "class Screen : android.app.Activity() { fun javaScreen() = JavaScreen() }"))
        configureSourceCompilation()
        val inputs = listOf(source,
            CompileFile(CompileFile.Type.Kotlin, File(common, "Other.kt"), common, module),
            CompileFile(CompileFile.Type.Java, java, android, module))
        val compiler = SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
        val result = compiler.compile(CompileTask(inputs, File(temporary.root, "ksp-mixed-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        assertTrue(result.successFiles.map { it.file }.containsAll(inputs))
        assertTrue(result.successFiles.any { it.file.file.name.startsWith("ARouter") })
        assertTrue(result.outputs.any {
            it.type == com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex && it.file.name.contains("ARouter")
        })
        assertTrue(File(module.buildPathInfo.javaClassPath, "demo/JavaScreen.class").isFile)
        assertTrue(File(module.buildPathInfo.kotlinClassPath, "demo/Screen.class").isFile)
        assertEquals(setOf("/demo/first", "/demo/untouched", "/demo/java"),
            resolvedRoutes("com.tencent.yb.routes.ARouter\$\$Group\$\$demo"))
    }

    private fun projectKspGeneratesRoutes(ksp2: Boolean) {
        val common = configureProjectKsp(ksp2)
        val kotlin = source.file
        val fixture = com.sickworm.intellij.jugg.compiler.source.kotlin.ARouterKspFixture
        val generated = generate()
        assertEquals(3, generated.size)
        assertTrue(generated.all { it.extension == "kt" })
        fixture.compile(listOf(kotlin, File(common, "Other.kt")), libraryJars + context.androidJar,
            module.buildPathInfo.kotlinClassPath.apply { mkdirs() })
        val checkpoint = File(context.incrementalDataDir, "arouter/routes/debug")
        val gradle = File(temporary.root, "build/generated/ksp/debug")
        File(checkpoint, "sources").copyRecursively(gradle, overwrite = true)
        checkpoint.deleteRecursively()
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(listOf(gradle))
        module = module.copy(sourceDirs = module.sourceDirs + File(gradle, "kotlin"))
        updateModule()
        kotlin.writeText(kotlin.readText().replace("/demo/first", "/demo/latest"))
        val updated = generate()
        assertTrue(updated.any { it.readText().contains("/demo/latest") && it.readText().contains("/demo/untouched") })
        assertFalse(updated.any { it.readText().contains("/demo/first") })
        val before = updated.associate { it.path to it.readText() }
        val ideBefore = gradle.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() }
        module = module.copy(kspInfo = module.kspInfo!!.copy(arguments = mapOf("MODULE_NAME" to "routes")))
        updateModule()
        assertFailsWith<JuggAptFatalException> { generate() }
        assertEquals(before, updated.associate { it.path to it.readText() })
        assertEquals(ideBefore, gradle.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() })
        module = module.copy(kspInfo = module.kspInfo!!.copy(arguments = mapOf("AROUTER_MODULE_NAME" to "routes")))
        updateModule()
        kotlin.writeText(kotlin.readText().replace("/demo/latest", "/other/latest"))
        File(common, "Other.kt").delete()
        assertFailsWith<JuggAptFatalException> { generate() }
        assertEquals(before, updated.associate { it.path to it.readText() })
        assertEquals(ideBefore, gradle.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() })
    }

    private fun configureProjectKsp(ksp2: Boolean): File {
        val fixture = com.sickworm.intellij.jugg.compiler.source.kotlin.ARouterKspFixture
        processorJars = listOf(fixture.annotation, fixture.processor) + fixture.processorDependencies
        libraryJars = listOf(fixture.annotation) + libraryJars + fixture.compiler
        module = module.copy(annotationProcessorDependencies = emptyList(), kaptDependencies = emptyList(),
            kotlinPlugins = fixture.compiler,
            kspInfo = KspInfo(processorDependencies = processorJars.map { LibraryDependency(it.name, it) },
                useKsp2 = ksp2, runtimeDependencies = fixture.runtime(ksp2).map { LibraryDependency(it.name, it) },
                arguments = mapOf("AROUTER_MODULE_NAME" to "routes", "MODULE_NAME" to "routes")))
        updateModule()
        source.file.delete()
        val common = File(source.baseDir, "commonMain").apply { mkdirs() }
        val android = File(source.baseDir, "androidMain").apply { mkdirs() }
        val kotlin = File(android, "Screen.kt").apply { writeText("""
            package demo
            import com.alibaba.android.arouter.facade.annotation.Route
            @Route(path = "/demo/first", app = "custom")
            class Screen : android.app.Activity()
        """.trimIndent()) }
        File(common, "Other.kt").writeText("""
            package demo
            @com.alibaba.android.arouter.facade.annotation.Route(path = "/demo/untouched", app = "custom")
            class Other
        """.trimIndent())
        module = module.copy(sourceDirs = listOf(common, android), kotlinCommonSourceDirs = listOf(common))
        source = CompileFile(CompileFile.Type.Kotlin, kotlin, android, module)
        updateModule()
        return common
    }

    @Test
    fun defaultPipelineGeneratesRoutesForAndroidXWithoutCustomCompiler() {
        val generated = generate()
        assertEquals(3, generated.size)
        assertTrue(generated.any { it.readText().contains("/demo/first") })
        assertTrue(generated.all { it.isFile })
    }

    @Test
    fun generatedJavaUpdatesIdeSourcesWithoutReplacingOtherProcessorOutputs() {
        module = module.copy(buildPathInfo = module.buildPathInfo.copy(buildDirRelativePath = "custom-build"))
        updateModule()
        val target = File(module.buildPathInfo.generatedSourcePath, "ap_generated_sources/debug/out")
        val unrelated = File(target, "OtherProcessor.java").apply { parentFile.mkdirs(); writeText("class OtherProcessor {}") }
        val otherVariant = File(module.buildPathInfo.generatedSourcePath, "ap_generated_sources/release/out/Untouched.java")
            .apply { parentFile.mkdirs(); writeText("class Untouched {}") }
        val root = File(context.incrementalDataDir, "arouter/routes/debug/sources")
        val first = generate()
        first.forEach { assertEquals(it.readText(), File(target, it.relativeTo(root).path).readText()) }
        source = writeSource("@Route(path = \"/demo/latest\")")
        val latest = generate()
        latest.forEach { assertEquals(it.readText(), File(target, it.relativeTo(root).path).readText()) }
        assertTrue(latest.any { it.readText().contains("/demo/latest") })
        assertEquals("class OtherProcessor {}", unrelated.readText())
        assertEquals("class Untouched {}", otherVariant.readText())
    }

    @Test
    fun kaptProjectMirrorsGeneratedJavaToItsExistingIdeRoot() {
        module = module.copy(kaptDependencies = module.annotationProcessorDependencies, annotationProcessorDependencies = emptyList())
        updateModule()
        val target = File(module.buildPathInfo.generatedSourcePath, "source/kapt/debug")
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(listOf(target))
        val root = File(context.incrementalDataDir, "arouter/routes/debug/sources")
        generate().forEach { assertEquals(it.readText(), File(target, it.relativeTo(root).path).readText()) }
        assertFalse(File(module.buildPathInfo.generatedSourcePath, "ap_generated_sources/debug/out").exists())
    }

    @Test
    fun projectKsp1MirrorsSourcesToTheIdeSourceRoot() = projectKspMirrorsIdeSources(false)

    @Test
    fun projectKsp2MirrorsSourcesToTheIdeSourceRoot() = projectKspMirrorsIdeSources(true)

    private fun projectKspMirrorsIdeSources(ksp2: Boolean) {
        configureProjectKsp(ksp2)
        val target = File(module.buildPathInfo.generatedSourcePath, "ksp/android/androidDebug")
        val kotlinRoot = File(target, "kotlin").apply { mkdirs() }
        module = module.copy(sourceDirs = module.sourceDirs + kotlinRoot)
        updateModule()
        val unrelated = File(kotlinRoot, "OtherProcessor.kt").apply { writeText("class OtherProcessor") }
        val root = File(context.incrementalDataDir, "arouter/routes/debug/sources")
        generate().forEach { assertEquals(it.readText(), File(target, it.relativeTo(root).path).readText()) }
        assertEquals("class OtherProcessor", unrelated.readText())
    }

    @Test
    fun ideSourceSyncFailureWarnsAndStillCompilesRoutesToDex() {
        val target = File(module.buildPathInfo.generatedSourcePath, "ap_generated_sources/debug/out")
        target.parentFile.mkdirs()
        target.writeText("blocked generated source directory")
        val logger = org.mockito.kotlin.spy(context.logger)
        whenever(context.logger).thenReturn(logger)
        configureSourceCompilation()
        val flow = SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
        val result = flow.compile(CompileTask(listOf(source), File(temporary.root, "sync-failure-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        assertTrue(result.outputs.any { it.type == com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex && it.file.name.contains("ARouter") })
        assertEquals(setOf("/demo/first"), resolvedRoutes())
        assertEquals("blocked generated source directory", target.readText())
        org.mockito.kotlin.verify(logger).warn(org.mockito.kotlin.argThat<String> {
            contains("ARouter") && contains("IDE") && contains(module.name) && contains("continues")
        }, any<Throwable>())
    }

    @Test
    fun unchangedDeclarationsReuseSourcesAndRouteChangesRegenerate() {
        val first = generate()
        compileBusinessSources()
        val contents = first.associate { it.name to it.readText() }
        first.forEach { assertTrue(it.setLastModified(1234567890000L)) }
        assertTrue(generate().isEmpty())
        assertEquals(contents, first.associate { it.name to it.readText() })
        assertTrue(first.all { it.lastModified() == 1234567890000L })
        source = writeSource("@Route(path = \"/demo/other\")")
        val changed = generate()
        assertTrue(changed.any { it.readText().contains("/demo/other") })
        assertFalse(changed.any { it.readText().contains("/demo/first") })
    }

    @Test
    fun spacedRouteAnnotationGeneratesAndInvalidatesCache() {
        source = writeSource("@Route (path = \"/demo/first\")")
        assertTrue(generate().any { it.readText().contains("/demo/first") })
        source = writeSource("@Route (path = \"/demo/other\")")
        val generated = generate()
        assertTrue(generated.any { it.readText().contains("/demo/other") })
        assertFalse(generated.any { it.readText().contains("/demo/first") })
    }

    @Test
    fun relatedParentTypeChangesRegenerateTheRouteKind() {
        val parent = File(source.baseDir, "BaseScreen.java")
        parent.writeText("package demo; class BaseScreen extends android.app.Activity {}")
        source.file.writeText(source.file.readText().replace("extends android.app.Activity", "extends BaseScreen"))
        assertTrue(generate().any { it.readText().contains("RouteType.ACTIVITY") })
        parent.writeText("""
            package demo;
            class BaseScreen extends android.app.Service {
                public android.os.IBinder onBind(android.content.Intent intent) { return null; }
            }
        """.trimIndent())
        assertTrue(generate().any { it.readText().contains("RouteType.SERVICE") })
    }

    @Test
    fun javaAutowiredAndInterceptorGenerateTheirOwnArtifacts() {
        source.file.writeText(source.file.readText().replace("extends android.app.Activity {}", """
            extends android.app.Activity {
                @com.alibaba.android.arouter.facade.annotation.Autowired public String user;
            }
        """.trimIndent()))
        File(source.baseDir, "DemoInterceptor.java").writeText("""
            package demo;
            import com.alibaba.android.arouter.facade.annotation.Interceptor;
            import com.alibaba.android.arouter.facade.Postcard;
            import com.alibaba.android.arouter.facade.callback.InterceptorCallback;
            import com.alibaba.android.arouter.facade.template.IInterceptor;
            @Interceptor(priority = 1)
            public class DemoInterceptor implements IInterceptor {
                public void init(android.content.Context context) {}
                public void process(Postcard postcard, InterceptorCallback callback) { callback.onContinue(postcard); }
            }
        """.trimIndent())
        val generated = generate()
        assertTrue(generated.any { it.name.contains("Autowired") && it.readText().contains("substitute.user") })
        assertTrue(generated.any { it.name.contains("Interceptors") && it.readText().contains("DemoInterceptor.class") })
    }

    @Test
    fun whitespaceInsideAnnotationLiteralsIsPartOfTheCacheInput() {
        source = writeSource("@Route(path = \"/demo/with space\")")
        assertTrue(generate().any { it.readText().contains("/demo/with space") })
        source = writeSource("@Route(path = \"/demo/withspace\")")
        assertTrue(generate().any { it.readText().contains("/demo/withspace") })
        assertFalse(generate().any { it.readText().contains("/demo/with space") })
    }

    @Test
    fun methodBodyChangesReuseSourcesButReferencedConstantsRegenerate() {
        val constants = File(source.baseDir, "Paths.java")
        constants.writeText("package demo; class Paths { static final String ROUTE = \"/demo/constant\"; }")
        source = writeSource("@Route(path = Paths.ROUTE)")
        val generated = generate()
        assertTrue(generated.any { it.readText().contains("/demo/constant") })
        compileBusinessSources()
        generated.forEach { assertTrue(it.setLastModified(1234567890000L)) }
        source.file.writeText(source.file.readText().replace("extends android.app.Activity {", "extends android.app.Activity { void work() { int changed = 1; }"))
        assertTrue(generate().all { it.lastModified() == 1234567890000L })
        constants.writeText(constants.readText().replace("/demo/constant", "/demo/modified"))
        assertTrue(generate().any { it.readText().contains("/demo/modified") })
    }

    @Test
    fun moduleOptionsAreLeftToFullGradleBuildsWhenBusinessInputsAreUnchanged() {
        establishGradleBaseline()
        module = module.copy(javaAnnotationProcessorOptions = module.javaAnnotationProcessorOptions.orEmpty() + ("AROUTER_MODULE_NAME" to "renamed"))
        updateModule()
        assertTrue(generate().isEmpty())
    }

    @Test
    fun aRefreshedGradleBaselineAllowsRebuildingAfterAnIndexWasRemoved() {
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/other/first\")")
        assertFailsWith<JuggAptFatalException> { generate() }
        establishGradleBaseline()
        assertTrue(generate().isEmpty())
        source = writeSource("@Route(path = \"/other/next\")")
        assertTrue(generate().any { it.readText().contains("/other/next") })
    }

    @Test
    fun missingFragmentFailsInsteadOfSilentlyKeepingBaselineRoutes() {
        libraryJars = libraryJars.filterNot { it.name.startsWith("fragment-") }
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun missingModuleNameFailsBeforePublishingGeneratedSources() {
        module = module.copy(javaAnnotationProcessorOptions = emptyMap())
        source = source.copy(module = module)
        whenever(context.modulesWithOrder).thenReturn(listOf(module))
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun firstIncrementalRoundCannotRemoveGradleBaselineClasses() {
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/other/first\")")
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun removingLastAnnotationLeavesGeneratedSourcesForFullBuild() {
        establishGradleBaseline()
        val generated = File(temporary.root, "gradle-generated")
        assertTrue(generated.walkTopDown().any { it.isFile && it.readText().contains("/demo/first") })
        source = writeSource("")
        source.file.writeText(source.file.readText().replace("import com.alibaba.android.arouter.facade.annotation.Route;", ""))
        assertTrue(generate().isEmpty())
        assertTrue(generated.walkTopDown().any { it.isFile && it.readText().contains("/demo/first") })
        establishGradleBaseline()
        assertFalse(generated.walkTopDown().any { it.isFile && it.readText().contains("/demo/first") })
    }

    @Test
    fun unusedAnnotationImportDoesNotRecoverRemovedAnnotation() {
        establishGradleBaseline()
        source = writeSource("")
        assertTrue(generate().isEmpty())
    }

    @Test
    fun firstRoundMethodBodyChangeDoesNotRunApt() {
        establishGradleBaseline()
        source.file.writeText(source.file.readText().replace("Activity {}", "Activity { void changed() {} }"))
        assertTrue(generate().isEmpty())
        assertFalse(File(context.incrementalDataDir, "arouter/routes/debug/checkpoint.txt").exists())
    }

    @Test
    fun missingOriginalClassesSkipsDetectionWithoutPublishingACheckpoint() {
        val baseline = temporary.newFolder("baseline")
        File(baseline, "ARouter\$\$Root\$\$routes.java").writeText("// Unknown or incomplete baseline")
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(listOf(baseline))
        assertTrue(generate().isEmpty())
        assertFalse(File(context.incrementalDataDir, "arouter/routes/debug/checkpoint.txt").exists())
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(emptyList())
        assertTrue(generate().any { it.readText().contains("/demo/first") })
    }

    @Test
    fun ordinaryProjectDoesNotRequireARouterOptionsOrProcessor() {
        module = module.copy(annotationProcessorDependencies = emptyList(), javaAnnotationProcessorOptions = emptyMap())
        source = writeSource("")
        updateModule()
        assertTrue(generate().isEmpty())
    }

    @Test
    fun actualSpiCanRegisterOnlyTwoProcessorsAndIgnoreUnrelatedEntries() {
        val original = compilerJar()
        val replacement = rewriteJar(original, "two-processors.jar", spi = """
            # Project processor registration
            com.alibaba.android.arouter.compiler.processor.RouteProcessor # route
            com.alibaba.android.arouter.compiler.processor.InterceptorProcessor
            java.lang.String
        """.trimIndent())
        replaceCompiler(original, replacement)
        module = module.copy(kaptDependencies = module.annotationProcessorDependencies)
        updateModule()
        assertEquals(3, generate().size)
    }

    @Test
    fun distinctCompilerCandidatesFailInsteadOfClasspathOrderChoosingOne() {
        val duplicate = File(temporary.root, "duplicate.jar")
        compilerJar().copyTo(duplicate)
        module = module.copy(annotationProcessorDependencies = module.annotationProcessorDependencies + LibraryDependency("duplicate", duplicate))
        updateModule()
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun missingRegisteredProcessorClassFailsInsteadOfSkippingGeneration() {
        val original = compilerJar()
        replaceCompiler(original, rewriteJar(original, "broken.jar", spi = "com.alibaba.android.arouter.compiler.processor.Missing"))
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun supportOnlyBaselineUsesSupportProcessorWithoutAndroidX() {
        useSupportClasspath()
        whenever(context.containsApkClass(any())).thenReturn(listOf(fragmentNode("android/support/v4/app/Fragment")))
        assertEquals(3, generate().size)
    }

    @Test
    fun ambiguousAndMultiApkBaselinesDefaultToAndroidX() {
        useSupportClasspath()
        val support = fragmentNode("android/support/v4/app/Fragment")
        val androidx = fragmentNode("androidx/fragment/app/Fragment")
        whenever(context.containsApkClass(any())).thenReturn(listOf(support, androidx))
        assertFailsWith<JuggAptFatalException> { generate() }
        whenever(context.containsApkClass(any())).thenReturn(listOf(support))
        whenever(context.isSingleApk).thenReturn(false)
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun alreadyJetifiedProcessorGeneratesRoutesWithoutAnotherNamespaceChange() {
        jetifyCompiler()
        whenever(context.containsApkClass(any())).thenReturn(listOf(fragmentNode("androidx/fragment/app/Fragment")))
        assertEquals(3, generate().size)
    }

    @Test
    fun reverseJetifierProducesWorkingProcessorForSupportBaseline() {
        jetifyCompiler()
        useSupportClasspath()
        whenever(context.containsApkClass(any())).thenReturn(listOf(fragmentNode("android/support/v4/app/Fragment")))
        assertEquals(3, generate().size)
    }

    @Test
    fun processorJarReplacementIsLeftToFullGradleBuildsWhenBusinessInputsAreUnchanged() {
        establishGradleBaseline()
        val original = compilerJar()
        replaceCompiler(original, rewriteJar(original, "local-compiler.jar", comment = "changed"))
        assertTrue(generate().isEmpty())
    }

    @Test
    fun externalARouterConfigurationRunsOnlyTheExternalGenerator() {
        val legacy = Mockito.mock(ICompiler::class.java, Mockito.withSettings().extraInterfaces(IJuggAptProcessor::class.java))
        val apt = legacy as IJuggAptProcessor
        whenever(apt.id).thenReturn("ARouter")
        whenever(apt.failClosed).thenReturn(true)
        val file = File(temporary.root, "LegacyRoute.java").apply { writeText("class LegacyRoute {}") }
        whenever(apt.process(any(), any(), any(), any())).thenReturn(listOf(CompileFile(CompileFile.Type.Java, file, temporary.root, module)))
        whenever(context.customCompilers).thenReturn(listOf(legacy))
        assertEquals(listOf(file), generate())
    }

    @Test
    fun builtinGeneratedSourcesAreOwnedByFailClosedProcessor() {
        val files = generate()
        assertTrue(files.all { compiler.isFailClosedGeneratedFile(CompileFile(CompileFile.Type.Java, it, it.parentFile, module)) })
        assertFalse(compiler.isFailClosedGeneratedFile(source))
    }

    @Test
    fun mixedAggregationCompilesTypealiasesAndUncompiledKotlinDependenciesToDex() {
        val executions = installCountingProcessor()
        val java = source
        val toolchain = System.getProperty("arouter.test.kotlin").split(File.pathSeparator).map(::File)
        module = module.copy(kotlinPlugins = toolchain, kotlinJvmTarget = "1.8")
        libraryJars += toolchain.filter { it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("annotations-") }
        val root = module.sourceDirs.single()
        val dependency = File(root, "demo/NewBase.kt").apply {
            writeText("package demo\nopen class NewBase : android.app.Activity()\nclass NewPayload")
        }
        val alias = File(root, "demo/alias/PayloadAlias.kt").apply {
            parentFile.mkdirs()
            writeText("package demo.alias\ntypealias PayloadAlias = demo.NewPayload")
        }
        val kotlin = File(root, "demo/KotlinScreen.kt").apply {
            writeText("""
                package demo
                import demo.alias.PayloadAlias
                @com.alibaba.android.arouter.facade.annotation.Route(path = "/demo/kotlin")
                class KotlinScreen : NewBase() {
                    fun payload(): PayloadAlias = PayloadAlias()
                }
            """.trimIndent())
        }
        source = CompileFile(CompileFile.Type.Kotlin, kotlin, root, module)
        updateModule()
        configureSourceCompilation()
        assertFalse(File(module.buildPathInfo.kotlinClassPath, "demo/NewBase.class").exists())
        val inputs = listOf(source, java.copy(module = module)) + listOf(dependency, alias).map {
            CompileFile(CompileFile.Type.Kotlin, it, root, module)
        }
        val flow = SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
        val result = flow.compile(CompileTask(inputs, File(temporary.root, "mixed-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        assertTrue(result.outputs.any { it.type == com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex })
        assertEquals(setOf("/demo/first", "/demo/kotlin"), resolvedRoutes())
        assertEquals(1, executions.readLines().size)
        assertTrue(File(module.buildPathInfo.kotlinClassPath, "demo/NewPayload.class").isFile)
    }

    @Test
    fun mixedAggregationIgnoresOtherProcessorsAndPreviousGeneratedSourceRoots() {
        compileBusinessSources()
        val toolchain = System.getProperty("arouter.test.kotlin").split(File.pathSeparator).map(::File)
        val rejected = rejectingProcessor("demo.UnrelatedProcessor")
        processorJars += rejected
        module = module.copy(kotlinPlugins = toolchain, kotlinJvmTarget = "1.8",
            annotationProcessorDependencies = processorJars.map { LibraryDependency(it.name, it) })
        libraryJars += toolchain.filter { it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("annotations-") }
        val root = module.sourceDirs.single()
        File(root, "demo/KotlinScreen.kt").writeText("""
            package demo
            @com.alibaba.android.arouter.facade.annotation.Route(path = "/demo/kotlin")
            class KotlinScreen : android.app.Activity()
        """.trimIndent())
        val previous = temporary.newFolder("previous-generated")
        File(previous, "Previous.java").writeText("class Previous { MissingType dependency; }")
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(listOf(previous))
        updateModule()
        source = writeSource("@Route(path = \"/demo/latest\")")
        val generated = generate()
        assertTrue(generated.any { it.readText().contains("/demo/latest") && it.readText().contains("/demo/kotlin") })
    }

    @Test
    fun mixedProcessorFailurePreservesPublishedSourcesAndFailsClosed() {
        val published = generate().associate { it to it.readBytes().toList() }
        val toolchain = System.getProperty("arouter.test.kotlin").split(File.pathSeparator).map(::File)
        module = module.copy(kotlinPlugins = toolchain, kotlinJvmTarget = "1.8")
        libraryJars += toolchain.filter { it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("annotations-") }
        File(module.sourceDirs.single(), "demo/Ordinary.kt").writeText("package demo\nclass Ordinary")
        replaceCompiler(compilerJar(), rejectingProcessor("com.alibaba.android.arouter.compiler.processor.RejectedProcessor"))
        source = writeSource("@Route(path = \"/demo/latest\")")
        assertFailsWith<JuggAptFatalException> { generate() }
        assertEquals(published, published.keys.associateWith { it.readBytes().toList() })
    }

    @Test
    fun kotlinAliasAndAutowiredGenerateFromKaptStubs() {
        val toolchain = System.getProperty("arouter.test.kotlin").split(File.pathSeparator).map(::File)
        module = module.copy(kotlinPlugins = toolchain, kotlinJvmTarget = "1.8")
        libraryJars = libraryJars + toolchain.filter { it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("annotations-") }
        File(module.sourceDirs.single(), "Page.kt").writeText("package com.tencent.kuikly.core.annotations; annotation class Page(val route: String)")
        val entry = File(module.buildPathInfo.generatedKspSourcePath, "KuiklyCoreEntry.kt").apply {
            parentFile.mkdirs()
            writeText("class KuiklyCoreEntry { private fun triggerRegisterPages() {} }")
        }
        val kotlin = File(module.sourceDirs.single(), "demo/KotlinScreen.kt").apply {
            writeText("""
                package demo
                import com.alibaba.android.arouter.facade.annotation.Route as Destination
                import com.alibaba.android.arouter.facade.annotation.Autowired
                import com.tencent.kuikly.core.annotations.Page
                @Page("kuikly_screen")
                @Destination(path = "/demo/kotlin")
                class KotlinScreen : android.app.Activity() {
                    @field:Autowired @JvmField var title: String? = null
                }
            """.trimIndent())
        }
        source = CompileFile(CompileFile.Type.Kotlin, kotlin, module.sourceDirs.single(), module)
        updateModule()
        val generated = generate()
        assertTrue(generated.any { it.readText().contains("/demo/kotlin") && it.readText().contains("/demo/first") })
        assertTrue(entry.readText().contains("BridgeManager.registerPageRouter(\"kuikly_screen\")"))
        assertTrue(generated.any { it.name.contains("Autowired") && it.readText().contains("title") })
    }

    @Test
    fun generatedJavaErrorRetainsPendingSourcesAndRetryConsumesLatestGeneration() {
        val executions = installCountingProcessor()
        configureSourceCompilation()
        source.file.writeText("""
            package demo;
            import com.alibaba.android.arouter.facade.annotation.Route;
            import com.alibaba.android.arouter.facade.annotation.Autowired;
            @Route(path = "/demo/first")
            public class Screen extends android.app.Activity {
                @Autowired public final String value = "";
            }
        """.trimIndent())
        val trackedFiles = linkedSetOf<File>()
        val trackingContext = object : ICompileContext by context {
            override fun addChangedFile(files: List<ChangedFile>) { trackedFiles += files.map { it.file } }
            override fun removeChangedFile(files: List<File>) { trackedFiles -= files.toSet() }
        }
        val sourceCompiler = SourceCompiler(trackingContext, object : Disposable { override fun dispose() = Unit })
        val result = sourceCompiler.compile(CompileTask(listOf(source), File(temporary.root, "classes"), CompileStatusHolder.DEFAULT))
        assertFalse(result.isAllSuccess)
        assertTrue(trackedFiles.any { it.name.contains("Autowired") }, "A failed ARouter language stage must retain generated changed files")
        val count = executions.readLines().size
        source.file.writeText(source.file.readText().replace("public final String", "public String"))
        val root = File(context.incrementalDataDir, "arouter/routes/debug/sources")
        val pending = trackedFiles.map { CompileFile(CompileFile.Type.Java, it, root, module) }
        val retry = sourceCompiler.compile(CompileTask(listOf(source) + pending, File(temporary.root, "retry-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(retry.isAllSuccess, retry.failedFiles.toString())
        assertTrue(retry.outputs.any { it.file.name.contains("ARouter") })
        assertTrue(executions.readLines().size >= count)
    }

    @Test
    fun changingOnlyConstantImportMustRegenerateRoute() {
        for ((pkg, route) in listOf("first" to "/demo/first", "second" to "/demo/second")) {
            File(source.baseDir, "$pkg/Paths.java").apply {
                parentFile.mkdirs()
                writeText("package $pkg; public class Paths { public static final String PATH = \"$route\"; }")
            }
        }
        source = writeSource("@Route(path = Paths.PATH)")
        source.file.writeText(source.file.readText().replace("package demo;", "package demo;\nimport first.Paths;"))
        assertTrue(generate().any { it.readText().contains("/demo/first") })
        source.file.writeText(source.file.readText().replace("import first.Paths;", "import second.Paths;"))
        val reused = generate().any { it.readText().contains("/demo/second") }
        assertTrue(reused, "Import-only change incorrectly reused the previous route")
    }

    @Test
    fun changingOnlyAutowiredTypeImportMustRegenerateCast() {
        source.file.writeText("""
            package demo;
            import java.util.Date;
            import com.alibaba.android.arouter.facade.annotation.Route;
            import com.alibaba.android.arouter.facade.annotation.Autowired;
            @Route(path = "/demo/first")
            public class Screen extends android.app.Activity {
                @Autowired public Date value;
            }
        """.trimIndent())
        assertTrue(generate().any { it.readText().contains("java.util.Date") })
        source.file.writeText(source.file.readText().replace("import java.util.Date;", "import java.sql.Date;"))
        val reused = generate().any { it.readText().contains("java.sql.Date") }
        assertTrue(reused, "Import-only change incorrectly reused the previous injection cast")
    }

    @Test
    fun oneHundredBodyEditsAndEquivalentAnnotationValuesDoNotExecuteApt() {
        val executions = installCountingProcessor()
        generate()
        compileBusinessSources()
        val before = executions.readLines().size
        val original = source.file.readText()
        val started = System.nanoTime()
        repeat(100) { iteration ->
            source.file.writeText(original.replace("Activity {}", "Activity { void work() { int value = $iteration; } }"))
            assertTrue(generate().isEmpty())
        }
        println("ARouter 100 body-edit comparisons: ${(System.nanoTime() - started) / 1_000_000} ms")
        File(source.baseDir, "demo/Paths.java").writeText("package demo; class Paths { static final String PATH = \"/demo/\" + \"first\"; }")
        source = writeSource("@Route(path = Paths.PATH, group = \"\", priority = -1, extras = Integer.MIN_VALUE, name = \"\")")
        assertTrue(generate().isEmpty())
        assertEquals(before, executions.readLines().size, "Apt init must not run for equivalent semantics")
        source = writeSource("@Route(path = \"/demo/changed\")")
        assertTrue(generate().any { it.readText().contains("/demo/changed") })
        assertEquals(before + 1, executions.readLines().size)
    }

    @Test
    fun ordinaryRoundDoesNotReturnCompletedIndexes() {
        generate()
        val ordinary = File(source.baseDir, "demo/Ordinary.java").apply { writeText("package demo; class Ordinary {}") }
        val input = CompileFile(CompileFile.Type.Java, ordinary, source.baseDir, module)
        val result = compiler.compile(CompileTask(listOf(input), File(temporary.root, "ordinary-out"), CompileStatusHolder.DEFAULT))
        assertTrue(result.outputs.isEmpty())
    }

    @Test
    fun aggregateFailureDoesNotPublishPartialIndexesAndTheNextRoundCanRecover() {
        val second = File(source.baseDir, "demo/Second.java").apply {
            writeText("package demo; import com.alibaba.android.arouter.facade.annotation.Route; @Route(path=\"/demo/second\") public class Second extends android.app.Activity {}")
        }
        generate()
        val folder = File(context.incrementalDataDir, "arouter/routes/debug/sources")
        val before = folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).path to it.readText() }
        source = writeSource("@Route(path = \"/demo/changed\")")
        val valid = second.readText()
        second.writeText(valid.replace("path=\"/demo/second\"", "path=unknown()"))
        assertTrue(generate().isEmpty())
        assertEquals(before, folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).path to it.readText() })
        second.writeText(valid)
        val result = generate()
        assertTrue(result.any { it.readText().contains("/demo/changed") && it.readText().contains("/demo/second") })
    }

    @Test
    fun constantImpactFlowCompilesGeneratedIndexesToDexAndResolvesTheNewRoute() {
        File(temporary.root, ".git").mkdirs()
        val paths = File(source.baseDir, "demo/Paths.java").apply {
            writeText("package demo; public class Paths { public static final String PATH = \"/demo/first\"; }")
        }
        source = writeSource("@Route(path = Paths.PATH)")
        establishGradleBaseline()
        assertEquals(setOf("/demo/first"), resolvedRoutes())
        configureSourceCompilation()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val engine = com.sickworm.intellij.jugg.compiler.constref.ConstRefEngine(
            analyzer = com.sickworm.intellij.jugg.compiler.constref.ConstRefAnalyzer(context.logger),
            database = com.sickworm.intellij.jugg.compiler.constref.ConstRefCacheDatabase(File(temporary.root, "constref.db"), context.logger),
            logger = context.logger,
            backgroundTaskRunner = com.sickworm.intellij.jugg.project.CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = com.sickworm.intellij.jugg.compiler.constref.RepoSharedFingerprintStore(context.logger, File(temporary.root, "fingerprints.db")),
        )
        try {
            engine.initializeFullScan(module.sourceDirs.map { it.canonicalFile })
            engine.awaitAnalysis(listOf(paths.canonicalPath, source.file.canonicalPath), timeoutMs = 10_000)
            engine.getEffectedFiles(listOf(paths.canonicalPath))
            engine.acknowledgeEffectedFilesAfterDeployCommit()
            paths.writeText(paths.readText().replace("/demo/first", "/demo/changed"))
            val sourceCompiler = SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
            val constant = CompileFile(CompileFile.Type.Java, paths, source.baseDir, module)
            val first = sourceCompiler.compile(CompileTask(listOf(constant), File(temporary.root, "constant-dex"), CompileStatusHolder.DEFAULT))
            assertTrue(first.isAllSuccess, first.failedFiles.toString())
            assertFalse(first.outputs.any { it.file.name.contains("ARouter") })
            engine.onFileSaved(paths.canonicalPath)
            engine.awaitAnalysis(listOf(paths.canonicalPath), timeoutMs = 10_000)
            val effected = engine.getEffectedFiles(listOf(paths.canonicalPath))
            assertEquals(setOf(source.file.canonicalPath), effected.map { File(it.refFilePath).canonicalPath }.toSet())
            val unchanged = source.file.readText()
            val followup = effected.map { CompileFile(CompileFile.Type.Java, File(it.refFilePath), source.baseDir.canonicalFile, module) }
            val result = sourceCompiler.compile(CompileTask(followup, File(temporary.root, "route-dex"), CompileStatusHolder.DEFAULT))
            assertTrue(result.isAllSuccess, result.failedFiles.toString())
            assertTrue(result.outputs.any { it.type == com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex && it.file.name.contains("ARouter") })
            assertEquals(unchanged, source.file.readText())
            assertEquals(setOf("/demo/changed"), resolvedRoutes())
        } finally {
            engine.dispose()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun firstBaselineRecoversMultipleAndNestedSourceIdentities() {
        source.file.writeText(source.file.readText().replace("Activity {}", """Activity {
            @com.alibaba.android.arouter.facade.annotation.Route(path="/demo/nested")
            public static class NestedScreen extends android.app.Activity {}
        }
        class UnrelatedHelper {}
        """.trimIndent()))
        establishGradleBaseline()
        source.file.appendText("\n// ordinary body and formatting edit")
        assertTrue(generate().isEmpty())
        source.file.writeText(source.file.readText().replace("path=\"/demo/nested\"", "path=\"/demo/moved\""))
        assertTrue(generate().any { it.readText().contains("/demo/moved") && it.readText().contains("/demo/first") })
    }

    @Test
    fun aConstantInAnotherClassOfTheCurrentFileUsesItsCurrentValue() {
        source = writeSource("@Route(path = Paths.PATH)")
        source.file.appendText("\nclass Paths { static final String PATH = \"/demo/first\"; }")
        assertTrue(generate().any { it.readText().contains("/demo/first") })
        source.file.writeText(source.file.readText().replace("/demo/first", "/demo/changed"))
        assertTrue(generate().any { it.readText().contains("/demo/changed") })
    }

    @Test
    fun firstBaselineRecoversStringAndReferenceInjectionTypes() {
        source.file.writeText(source.file.readText().replace("Activity {}", """Activity {
            @com.alibaba.android.arouter.facade.annotation.Autowired public String user;
            @com.alibaba.android.arouter.facade.annotation.Autowired public java.util.Date date;
        }""".trimIndent()))
        establishGradleBaseline()
        source.file.appendText("\n// ordinary edit")
        assertTrue(generate().isEmpty())
        source.file.writeText(source.file.readText().replace("/demo/first", "/demo/changed"))
        val result = generate()
        assertTrue(result.any { it.readText().contains("/demo/changed") })
        assertTrue(result.any { it.name.contains("Autowired") && it.readText().contains("Date") })
    }

    @Test
    fun exactDeclarationIdentityDetectsAnnotatedChangesWithoutSourceFileDebugMetadata() {
        establishGradleBaseline()
        val file = File(module.buildPathInfo.javaClassPath, "demo/Screen.class")
        val writer = com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter(0)
        com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader(file.readBytes()).accept(writer,
            com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader.SKIP_DEBUG)
        file.writeBytes(writer.toByteArray())
        source.file.writeText(source.file.readText().replace("/demo/first", "/demo/changed"))
        assertTrue(generate().any { it.readText().contains("/demo/changed") })
    }

    @Test
    fun missingGeneratedBaselineDoesNotRegenerateAnExistingAnnotatedClass() {
        establishGradleBaseline()
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(emptyList())
        assertTrue(generate().isEmpty())
        assertFalse(File(context.incrementalDataDir, "arouter/routes/debug/inputs.json").exists())
    }

    @Test
    fun movingACompiledSourceAndRemovingItsLastAnnotationLeaveGeneratedSourcesUnchanged() {
        generate()
        compileBusinessSources()
        val moved = File(source.baseDir, "moved/Screen.java").apply { parentFile.mkdirs() }
        assertTrue(source.file.renameTo(moved))
        source = source.copy(file = moved)
        assertTrue(generate().isEmpty())
        moved.writeText("package demo; public class Screen extends android.app.Activity {}")
        assertTrue(generate().isEmpty())
    }

    @Test
    fun deletingObsoleteMetadataDoesNotAffectComparisonOrGeneration() {
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/demo/latest\")")
        generate()
        compileBusinessSources()
        val folder = File(context.incrementalDataDir, "arouter/routes/debug")
        File(folder, "inputs.json").writeText("obsolete")
        File(folder, "checkpoint.txt").writeText("obsolete")
        assertTrue(File(folder, "inputs.json").delete())
        assertTrue(File(folder, "checkpoint.txt").delete())
        compiler = JuggAptCompiler(context, object : Disposable { override fun dispose() = Unit })
        assertTrue(generate().isEmpty())
        source = writeSource("@Route(path = \"/demo/next\")")
        assertTrue(generate().any { it.readText().contains("/demo/next") })
    }

    @Test
    fun corruptObsoleteMetadataIsIgnoredAndRemovedAfterSuccessfulGeneration() {
        establishGradleBaseline()
        val folder = File(context.incrementalDataDir, "arouter/routes/debug").apply { mkdirs() }
        File(folder, "inputs.json").writeText("{\"version\":999}")
        File(folder, "checkpoint.txt").writeText("invalid manifest")
        assertTrue(generate().isEmpty())
        source = writeSource("@Route(path = \"/demo/latest\")")
        assertTrue(generate().isNotEmpty())
        assertFalse(File(folder, "inputs.json").exists())
        assertFalse(File(folder, "checkpoint.txt").exists())
    }

    @Test
    fun pendingGenerationDoesNotSuppressAnotherInputChangeOrARepeatedApt() {
        val executions = installCountingProcessor()
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/demo/latest\")")
        assertTrue(generate().isNotEmpty())
        assertTrue(generate().isNotEmpty(), "Unchanged old business class permits repeated APT after language failure")
        assertEquals(2, executions.readLines().size)
        source = writeSource("@Route(path = \"/demo/next\")")
        assertTrue(generate().any { it.readText().contains("/demo/next") })
        assertEquals(3, executions.readLines().size)
        compileBusinessSources()
        assertTrue(generate().isEmpty())
    }

    @Test
    fun missingKnownBusinessClassesSkipInsteadOfUsingEmptyOldInputs() {
        establishGradleBaseline()
        File(module.buildPathInfo.javaClassPath, "demo/Screen.class").delete()
        whenever(context.containsApkClass(any())).thenReturn(listOf(fragmentNode("demo/Screen")))
        source = writeSource("@Route(path = \"/demo/next\")")
        assertTrue(generate().isEmpty())
    }

    @Test
    fun generatedSourceContentsAreNotUsedAsTheInputComparisonBaseline() {
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/demo/latest\")")
        val latest = generate()
        compileBusinessSources()
        latest.first().appendText("\n// externally changed output")
        assertTrue(generate().isEmpty())
        source = writeSource("@Route(path = \"/demo/next\")")
        assertTrue(generate().any { it.readText().contains("/demo/next") })
    }

    @Test
    fun compiledAnnotationPriorityChangesAreComparedWithoutGeneratedTemplateValidation() {
        establishGradleBaseline()
        val modified = source.file.readText().replace("path = \"/demo/first\"", "path = \"/demo/first\", priority = 1")
        source.file.writeText(modified)
        val javac = javax.tools.ToolProvider.getSystemJavaCompiler()
        val paths = context.getModuleDependencies(module, CompileTask(listOf(source), temporary.root, CompileStatusHolder.DEFAULT))
        assertEquals(0, javac.run(null, null, null, "-proc:none", "-classpath", paths.joinToString(File.pathSeparator),
            "-d", module.buildPathInfo.javaClassPath.path, source.file.path))
        source.file.writeText(modified.replace(", priority = 1", ""))
        assertTrue(generate().isNotEmpty())
        assertFalse(File(context.incrementalDataDir, "arouter/routes/debug/inputs.json").exists())
    }

    @Test
    fun documentationInputChangesRunAptEvenWhenGeneratedRoutesAreEqual() {
        val executions = installCountingProcessor()
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/demo/first\", name = \"changed description\")")
        assertTrue(generate().isNotEmpty())
        assertEquals(1, executions.readLines().size)
    }

    @Test
    fun runtimeImportWithOverlayDoesNotWarnOrRunApt() {
        establishGradleBaseline()
        val logger = org.mockito.kotlin.spy(context.logger)
        whenever(context.logger).thenReturn(logger)
        val overlay = com.sickworm.intellij.jugg.compiler.CompileOutput(
            com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex, File(temporary.root, "overlay.dex"), temporary.root)
        whenever(context.deployedFiles).thenReturn(listOf(overlay))
        val ordinary = File(source.baseDir, "demo/MainActivity.java").apply {
            writeText("package demo; import com.alibaba.android.arouter.launcher.ARouter; class MainActivity { void work() {} }")
        }
        source = CompileFile(CompileFile.Type.Java, ordinary, source.baseDir, module)
        assertTrue(generate().isEmpty())
        org.mockito.kotlin.verify(logger, org.mockito.kotlin.never()).warn(any<String>(), any<Throwable>())
    }

    @Test
    fun aChangedInputCannotHideAnUnresolvedInputInTheSameRound() {
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/demo/changed\")")
        val broken = File(source.baseDir, "demo/Broken.java").apply {
            writeText("package demo; import com.alibaba.android.arouter.facade.annotation.Route; @Route(path=unknown()) class Broken extends android.app.Activity {}")
        }
        val result = compiler.compile(CompileTask(listOf(source,
            CompileFile(CompileFile.Type.Java, broken, source.baseDir, module)), temporary.root, CompileStatusHolder.DEFAULT))
        assertTrue(result.outputs.isEmpty())
    }

    @Test
    fun customFixtureAcceptsAppAndEightArgumentRouteMetaBuild() {
        useCustomFixture()
        source = writeSource("@Route(path = \"/demo/first\", app = \"demo\")")
        establishGradleBaseline()
        assertTrue(generate().isEmpty())
        source = writeSource("@Route(path = \"/demo/first\", app = \"other\")")
        val generated = generate()
        assertTrue(generated.any { it.readText().contains("\"other\"") && it.readText().contains("RouteMeta.build") })
        assertTrue(generated.any { file -> com.github.javaparser.StaticJavaParser.parse(file)
            .findAll(com.github.javaparser.ast.expr.MethodCallExpr::class.java).any { it.nameAsString == "build" && it.arguments.size == 8 } })
        compileBusinessSources()
        assertTrue(generate().isEmpty())
        source = writeSource("@Route(path = \"/demo/next\", app = \"other\")")
        configureSourceCompilation()
        val flow = SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
        val result = flow.compile(CompileTask(listOf(source), File(temporary.root, "fixture-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        assertTrue(result.outputs.any { it.file.name.contains("ARouter") })
        assertEquals(setOf("/demo/next"), resolvedRoutes("com.tencent.yb.routes.ARouter\$\$Group\$\$demo"))
    }

    @Test
    fun customFixtureMixedAggregationSupportsAppAttributesAndTypealiases() {
        useCustomFixture()
        val toolchain = System.getProperty("arouter.test.kotlin").split(File.pathSeparator).map(::File)
        module = module.copy(kotlinPlugins = toolchain, kotlinJvmTarget = "1.8")
        libraryJars += toolchain.filter { it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("annotations-") }
        updateModule()
        source = writeSource("@Route(path = \"/demo/first\", app = \"other\")")
        val root = module.sourceDirs.single()
        val payload = File(root, "demo/Payload.kt").apply { writeText("package demo\nclass Payload") }
        val alias = File(root, "demo/alias/PayloadAlias.kt").apply {
            parentFile.mkdirs()
            writeText("package demo.alias\ntypealias PayloadAlias = demo.Payload")
        }
        val kotlin = File(root, "demo/KotlinScreen.kt").apply {
            writeText("""
                package demo
                import demo.alias.PayloadAlias
                @com.alibaba.android.arouter.facade.annotation.Route(path = "/demo/kotlin", app = "other")
                class KotlinScreen : android.app.Activity() {
                    fun payload(): PayloadAlias = PayloadAlias()
                }
            """.trimIndent())
        }
        configureSourceCompilation()
        val inputs = listOf(source) + listOf(payload, alias, kotlin).map { CompileFile(CompileFile.Type.Kotlin, it, root, module) }
        val flow = SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
        val result = flow.compile(CompileTask(inputs, File(temporary.root, "custom-mixed-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        assertTrue(result.outputs.any { it.type == com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex })
        assertEquals(setOf("/demo/first", "/demo/kotlin"), resolvedRoutes("com.tencent.yb.routes.ARouter\$\$Group\$\$demo"))
        val group = File(context.incrementalDataDir, "arouter/routes/debug/sources/com/tencent/yb/routes/ARouter\$\$Group\$\$demo.java")
        assertTrue(group.readText().contains("\"other\""))
    }

    @Test
    fun unannotatedParentTypeChangeFlowLeavesRouteIndexesForFullBuild() {
        val parent = File(source.baseDir, "demo/BaseScreen.java").apply {
            writeText("package demo; public class BaseScreen extends android.app.Activity {}")
        }
        source.file.writeText(source.file.readText().replace("extends android.app.Activity", "extends BaseScreen"))
        establishGradleBaseline()
        configureSourceCompilation()
        parent.writeText("package demo; public class BaseScreen extends android.app.Service { public android.os.IBinder onBind(android.content.Intent intent) { return null; } }")
        val input = CompileFile(CompileFile.Type.Java, parent, source.baseDir, module)
        val flow = SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
        val result = flow.compile(CompileTask(listOf(input), File(temporary.root, "parent-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        assertFalse(result.outputs.any { it.file.name.contains("ARouter") })
        val index = File(context.incrementalDataDir, "arouter/routes/debug/sources")
        assertFalse(index.exists())
        val generated = File(temporary.root, "gradle-generated")
        assertTrue(generated.walkTopDown().any { it.isFile && it.readText().contains("RouteType.ACTIVITY") })
        establishGradleBaseline()
        assertTrue(generated.walkTopDown().any { it.isFile && it.readText().contains("RouteType.SERVICE") })
        val followup = flow.compile(CompileTask(listOf(source), File(temporary.root, "child-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(followup.isAllSuccess, followup.failedFiles.toString())
        assertEquals(setOf("/demo/first"), resolvedRoutes())
    }

    @Test
    fun inheritedAutowiredTypeChangeFlowCompilesUpdatedParameterIndexes() {
        val parent = File(source.baseDir, "demo/BaseScreen.java").apply {
            writeText("package demo; public class BaseScreen extends android.app.Activity { @com.alibaba.android.arouter.facade.annotation.Autowired public int user; }")
        }
        source.file.writeText(source.file.readText().replace("extends android.app.Activity", "extends BaseScreen"))
        establishGradleBaseline()
        configureSourceCompilation()
        parent.writeText(parent.readText().replace("int user", "String user"))
        val input = CompileFile(CompileFile.Type.Java, parent, source.baseDir, module)
        val flow = SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
        val result = flow.compile(CompileTask(listOf(input), File(temporary.root, "parent-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        assertTrue(result.outputs.any { it.file.name.contains("ARouter") })
        val followup = flow.compile(CompileTask(listOf(source), File(temporary.root, "child-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(followup.isAllSuccess, followup.failedFiles.toString())
        val paths = listOf(module.buildPathInfo.javaClassPath, context.androidJar) + libraryJars + processorJars
        java.net.URLClassLoader(paths.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val type = loader.loadClass("com.alibaba.android.arouter.routes.ARouter\$\$Group\$\$demo")
            val routes = linkedMapOf<String, Any>()
            type.getMethod("loadInto", Map::class.java).invoke(type.getDeclaredConstructor().newInstance(), routes)
            val meta = routes.getValue("/demo/first")
            assertEquals(mapOf("user" to 8), meta.javaClass.getMethod("getParamsType").invoke(meta))
        }
    }

    @Test
    fun javaFailureWithPendingSourcesAndAnotherRouteEditConsumesTheLatestGeneration() {
        val executions = installCountingProcessor()
        establishGradleBaseline()
        configureSourceCompilation()
        source = writeSource("@Route(path = \"/demo/latest\")")
        source.file.writeText(source.file.readText().replace("Activity {}", "Activity { void work() { missing(); } }"))
        val tracked = linkedSetOf<File>()
        val trackingContext = object : ICompileContext by context {
            override fun addChangedFile(files: List<ChangedFile>) { tracked += files.map { it.file } }
            override fun removeChangedFile(files: List<File>) { tracked -= files.toSet() }
        }
        val flow = SourceCompiler(trackingContext, object : Disposable { override fun dispose() = Unit })
        val failed = flow.compile(CompileTask(listOf(source), File(temporary.root, "failed-dex"), CompileStatusHolder.DEFAULT))
        assertFalse(failed.isAllSuccess)
        assertTrue(tracked.isNotEmpty())
        source = writeSource("@Route(path = \"/demo/next\")")
        val root = File(context.incrementalDataDir, "arouter/routes/debug/sources")
        val pending = tracked.map { CompileFile(CompileFile.Type.Java, it, root, module) }
        val retry = flow.compile(CompileTask(listOf(source) + pending, File(temporary.root, "retry-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(retry.isAllSuccess, retry.failedFiles.toString())
        assertEquals(2, executions.readLines().size)
        assertEquals(setOf("/demo/next"), resolvedRoutes())
    }

    @Test
    fun kotlinRouteGenerationAcceptsWindowsTextFormatsWithoutRewritingSources() {
        val toolchain = System.getProperty("arouter.test.kotlin").split(File.pathSeparator).map(::File)
        module = module.copy(kotlinPlugins = toolchain, kotlinJvmTarget = "1.8")
        libraryJars += toolchain.filter { it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("annotations-") }
        assertTrue(source.file.delete())
        val kotlin = File(source.baseDir, "demo/KotlinScreen.kt")
        source = CompileFile(CompileFile.Type.Kotlin, kotlin, source.baseDir, module)
        updateModule()
        val formats = listOf("" to "\n", "" to "\r\n", "" to "\r", "\uFEFF" to "\n", "\uFEFF" to "\r\n")
        formats.forEachIndexed { index, (prefix, newline) ->
            val route = "/demo/format$index"
            val content = prefix + listOf("package demo",
                "@com.alibaba.android.arouter.facade.annotation.Route(path = \"$route\")",
                "class KotlinScreen : android.app.Activity()").joinToString(newline)
            kotlin.writeText(content)
            val generated = generate()
            assertEquals(3, generated.size, "Missing indexes for text format $index")
            assertTrue(generated.any { it.readText().contains(route) })
            assertEquals(content, kotlin.readText())
        }
    }

    @Test
    fun kotlinBusinessClassComparisonSkipsEquivalentAliasesAndDefaultsAfterRealCompilation() {
        val executions = installCountingProcessor()
        val java = source
        val toolchain = System.getProperty("arouter.test.kotlin").split(File.pathSeparator).map(::File)
        module = module.copy(kotlinPlugins = toolchain, kotlinJvmTarget = "1.8")
        libraryJars += toolchain.filter { it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("annotations-") }
        val kotlin = File(source.baseDir, "demo/KotlinScreen.kt").apply {
            writeText("package demo\nimport com.alibaba.android.arouter.facade.annotation.Route as Destination\n@Destination(path = \"/demo/kotlin\")\nclass KotlinScreen : android.app.Activity()")
        }
        source = CompileFile(CompileFile.Type.Kotlin, kotlin, source.baseDir, module)
        updateModule()
        configureSourceCompilation()
        val flow = SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
        val result = flow.compile(CompileTask(listOf(source, java.copy(module = module)), File(temporary.root, "kotlin-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        assertEquals(1, executions.readLines().size)
        kotlin.writeText("package demo\n@com.alibaba.android.arouter.facade.annotation.Route(name = \"\", group = \"\", path = \"/demo/kotlin\")\nclass KotlinScreen : android.app.Activity()")
        assertTrue(generate().isEmpty())
        assertEquals(1, executions.readLines().size)
        kotlin.writeText(kotlin.readText().replace("/demo/kotlin", "/demo/changed"))
        val followup = flow.compile(CompileTask(listOf(source), File(temporary.root, "kotlin-followup"), CompileStatusHolder.DEFAULT))
        assertTrue(followup.isAllSuccess, followup.failedFiles.toString())
        assertEquals(2, executions.readLines().size)
        assertEquals(setOf("/demo/first", "/demo/changed"), resolvedRoutes())
    }

    @Test
    fun anExistingEmptyJuggDirectoryDoesNotBorrowTheGradleGeneratedClassList() {
        establishGradleBaseline()
        File(context.incrementalDataDir, "arouter/routes/debug/sources").mkdirs()
        source = writeSource("@Route(path = \"/other/first\")")
        assertTrue(generate().any { it.name.contains("other") })
    }

    @Test
    fun aNewAnnotatedDeclarationCanBeComparedAgainstAnEmptyOldContribution() {
        establishGradleBaseline()
        val second = File(source.baseDir, "demo/Second.java").apply {
            writeText("package demo; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/demo/second\") public class Second extends android.app.Activity {}")
        }
        source = CompileFile(CompileFile.Type.Java, second, source.baseDir, module)
        assertTrue(generate().any { it.readText().contains("/demo/second") && it.readText().contains("/demo/first") })
    }

    @Test
    fun kotlinSuccessAndJavaFailureRetainPendingAndRetryGeneratesTheNewJavaRoute() {
        val executions = installCountingProcessor()
        val toolchain = System.getProperty("arouter.test.kotlin").split(File.pathSeparator).map(::File)
        module = module.copy(kotlinPlugins = toolchain, kotlinJvmTarget = "1.8")
        libraryJars += toolchain.filter { it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("annotations-") }
        updateModule()
        val kotlin = File(source.baseDir, "demo/KotlinScreen.kt").apply {
            writeText("package demo\n@com.alibaba.android.arouter.facade.annotation.Route(path = \"/demo/kotlin\")\nclass KotlinScreen : android.app.Activity()")
        }
        val kotlinInput = CompileFile(CompileFile.Type.Kotlin, kotlin, source.baseDir, module)
        source.file.writeText(source.file.readText().replace("Activity {}", "Activity { void work() { missing(); } }"))
        configureSourceCompilation()
        val tracked = linkedSetOf<File>()
        val trackingContext = object : ICompileContext by context {
            override fun addChangedFile(files: List<ChangedFile>) { tracked += files.map { it.file } }
            override fun removeChangedFile(files: List<File>) { tracked -= files.toSet() }
        }
        val flow = SourceCompiler(trackingContext, object : Disposable { override fun dispose() = Unit })
        val failed = flow.compile(CompileTask(listOf(kotlinInput, source), File(temporary.root, "mixed-failure"), CompileStatusHolder.DEFAULT))
        assertFalse(failed.isAllSuccess)
        assertTrue(File(module.buildPathInfo.kotlinClassPath, "demo/KotlinScreen.class").isFile)
        assertTrue(tracked.any { it.name.contains("ARouter") })
        source = writeSource("@Route(path = \"/demo/next\")")
        val root = File(context.incrementalDataDir, "arouter/routes/debug/sources")
        val pending = tracked.map { CompileFile(CompileFile.Type.Java, it, root, module) }
        val retry = flow.compile(CompileTask(listOf(source, kotlinInput) + pending, File(temporary.root, "mixed-retry"), CompileStatusHolder.DEFAULT))
        assertTrue(retry.isAllSuccess, retry.failedFiles.toString())
        assertEquals(2, executions.readLines().size)
        assertEquals(setOf("/demo/next", "/demo/kotlin"), resolvedRoutes())
    }

    @Test
    fun dexPhaseFailureAfterLanguageCompilationRetriesWithoutAnotherApt() {
        val executions = installCountingProcessor()
        configureSourceCompilation()
        val destination = File(temporary.root, "dex-output")
        whenever(context.getDesugarInfo(any<com.sickworm.intellij.jugg.compiler.ClassPreparation>(), any(), any()))
            .thenReturn(com.sickworm.intellij.jugg.compiler.DesugarInfo.EMPTY.copy(desugaredLibraryConfiguration = "invalid json"))
        val tracked = linkedSetOf<File>()
        val trackingContext = object : ICompileContext by context {
            override fun addChangedFile(files: List<ChangedFile>) { tracked += files.map { it.file } }
            override fun removeChangedFile(files: List<File>) { tracked -= files.toSet() }
        }
        val flow = SourceCompiler(trackingContext, object : Disposable { override fun dispose() = Unit })
        val failed = flow.compile(CompileTask(listOf(source), destination, CompileStatusHolder.DEFAULT))
        assertFalse(failed.isAllSuccess)
        assertTrue(File(module.buildPathInfo.javaClassPath, "demo/Screen.class").isFile)
        assertTrue(tracked.any { it.name.contains("ARouter") })
        assertEquals(1, executions.readLines().size)
        whenever(context.getDesugarInfo(any<com.sickworm.intellij.jugg.compiler.ClassPreparation>(), any(), any()))
            .thenReturn(com.sickworm.intellij.jugg.compiler.DesugarInfo.EMPTY)
        val root = File(context.incrementalDataDir, "arouter/routes/debug/sources")
        val pending = tracked.map { CompileFile(CompileFile.Type.Java, it, root, module) }
        val retry = flow.compile(CompileTask(listOf(source) + pending, destination, CompileStatusHolder.DEFAULT))
        assertTrue(retry.isAllSuccess, retry.failedFiles.toString())
        assertTrue(retry.outputs.any { it.type == com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex })
        assertEquals(1, executions.readLines().size)
        assertEquals(setOf("/demo/first"), resolvedRoutes())
    }

    @Test
    fun providerInterfaceChangeUpdatesTheActualRegistrationKeys() {
        for (name in listOf("FirstProvider", "SecondProvider")) {
            File(source.baseDir, "demo/$name.java").writeText("package demo; public interface $name extends com.alibaba.android.arouter.facade.template.IProvider {}")
        }
        source.file.writeText(source.file.readText().replace("extends android.app.Activity {}", "implements FirstProvider { public void init(android.content.Context context) {} }"))
        establishGradleBaseline()
        configureSourceCompilation()
        source.file.writeText(source.file.readText().replace("implements FirstProvider", "implements SecondProvider"))
        val flow = SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
        val result = flow.compile(CompileTask(listOf(source), File(temporary.root, "provider-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        val paths = listOf(module.buildPathInfo.javaClassPath, context.androidJar) + libraryJars + processorJars
        java.net.URLClassLoader(paths.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val type = loader.loadClass("com.alibaba.android.arouter.routes.ARouter\$\$Providers\$\$routes")
            val providers = linkedMapOf<String, Any>()
            type.getMethod("loadInto", Map::class.java).invoke(type.getDeclaredConstructor().newInstance(), providers)
            assertEquals(setOf("demo.SecondProvider"), providers.keys)
        }
    }

    @Test
    fun genericAutowiredTypeChangesRegenerateEvenWhenErasedFieldTypesAreEqual() {
        source.file.writeText(source.file.readText().replace("Activity {}", "Activity { @com.alibaba.android.arouter.facade.annotation.Autowired public java.util.List<String> users; }"))
        establishGradleBaseline()
        assertTrue(generate().isEmpty())
        source.file.writeText(source.file.readText().replace("List<String>", "List<Integer>"))
        val generated = generate()
        assertTrue(generated.any { it.name.contains("Autowired") && it.readText().contains("List<Integer>") })
    }

    @Test
    fun ambiguousSameSourceFileAcrossPackagesSkipsInsteadOfInventingAnEmptyOldInput() {
        File(source.baseDir, "other/Screen.java").apply {
            parentFile.mkdirs()
            writeText("package other; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/demo/other\") public class Screen extends android.app.Activity {}")
        }
        establishGradleBaseline()
        assertTrue(generate().isEmpty(), "Exact package/declaration association must ignore another package's SourceFile")
        val moved = File(source.baseDir, "third/Screen.java").apply { parentFile.mkdirs() }
        assertTrue(source.file.renameTo(moved))
        moved.writeText(moved.readText().replace("package demo;", "package third;"))
        source = source.copy(file = moved)
        assertTrue(generate().isEmpty())
    }

    @Test
    fun removalChecksIncludeEveryJavaClassInTheOwnedGenerationDirectory() {
        generate()
        val helper = File(context.incrementalDataDir, "arouter/routes/debug/sources/demo/LegacyRegistry.java").apply {
            parentFile.mkdirs()
            writeText("package demo; public class LegacyRegistry {}")
        }
        source = writeSource("@Route(path = \"/demo/next\")")
        assertFailsWith<JuggAptFatalException> { generate() }
        assertTrue(helper.isFile, "A shrinking generation must keep every prior source")
    }

    /** Advances business classes with the normal Java compiler, leaving generated indexes untouched. */
    private fun useCustomFixture() {
        val fixture = System.getenv("JUGG_AROUTER_FIXTURE")?.let(::File)
        org.junit.Assume.assumeTrue("Set JUGG_AROUTER_FIXTURE to the custom project's processor artifacts", fixture?.isDirectory == true)
        val root = checkNotNull(fixture)
        val api = File(temporary.root, "fixture-api.jar")
        JarFile(File(root, "arouter-api-release.aar")).use { jar ->
            api.writeBytes(jar.getInputStream(jar.getJarEntry("classes.jar")).use { it.readBytes() })
        }
        processorJars = listOf(File(root, "arouter-compiler.jar"), File(root, "arouter-annotation.jar")) +
            File(root, "processor-deps").listFiles()!!.filter { it.extension == "jar" }
        libraryJars = libraryJars.filterNot { it.name.startsWith("arouter-api") } + api
        module = module.copy(annotationProcessorDependencies = processorJars.map { LibraryDependency(it.name, it) })
        updateModule()
    }

    /** Creates a real processor whose execution must fail, to check selection and fail-closed behavior. */
    private fun rejectingProcessor(name: String): File {
        val root = temporary.newFolder()
        val packageName = name.substringBeforeLast('.')
        val simpleName = name.substringAfterLast('.')
        val java = File(root, "$simpleName.java").apply {
            writeText("""
                package $packageName;
                @javax.annotation.processing.SupportedAnnotationTypes("*")
                public class $simpleName extends javax.annotation.processing.AbstractProcessor {
                    @Override public synchronized void init(javax.annotation.processing.ProcessingEnvironment environment) {
                        throw new IllegalStateException("Rejected processor executed");
                    }
                    @Override public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> annotations,
                            javax.annotation.processing.RoundEnvironment round) { return false; }
                }
            """.trimIndent())
        }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "8", "-proc:none", "-d", root.path, java.path))
        return File(root, "rejected-processor.jar").apply {
            JarOutputStream(outputStream()).use { output ->
                val path = "${name.replace('.', '/')}.class"
                output.putNextEntry(JarEntry(path))
                output.write(File(root, path).readBytes())
                output.closeEntry()
                output.putNextEntry(JarEntry("META-INF/services/javax.annotation.processing.Processor"))
                output.write(name.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun compileBusinessSources() {
        val sources = module.sourceDirs.flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "java" }.toList() }
        val paths = context.getModuleDependencies(module, CompileTask(listOf(source), temporary.root, CompileStatusHolder.DEFAULT))
        module.buildPathInfo.javaClassPath.mkdirs()
        val arguments = listOf("-proc:none", "-classpath", paths.joinToString(File.pathSeparator),
            "-d", module.buildPathInfo.javaClassPath.path) + sources.map { it.path }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
    }

    private fun configureSourceCompilation() {
        val classpath = context.getModuleDependencies(module, CompileTask(listOf(source), temporary.root, CompileStatusHolder.DEFAULT))
        whenever(context.getModuleDependencies(any(), any())).thenReturn(classpath + module.buildPathInfo.javaClassPath.path + module.buildPathInfo.kotlinClassPath.path)
        whenever(context.moduleBelongsApkMap).thenReturn(com.sickworm.intellij.jugg.ModuleApkBelongs(emptyMap(), emptyMap()))
        whenever(context.getDesugarInfo(any<com.sickworm.intellij.jugg.compiler.ClassPreparation>(), any(), any())).thenReturn(com.sickworm.intellij.jugg.compiler.DesugarInfo.EMPTY)
    }

    private fun resolvedRoutes(groupClass: String = "com.alibaba.android.arouter.routes.ARouter\$\$Group\$\$demo"): Set<String> {
        val paths = listOf(module.buildPathInfo.javaClassPath, module.buildPathInfo.kotlinClassPath, context.androidJar) + libraryJars + processorJars
        java.net.URLClassLoader(paths.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val type = loader.loadClass(groupClass)
            val routes = linkedMapOf<String, Any>()
            type.getMethod("loadInto", Map::class.java).invoke(type.getDeclaredConstructor().newInstance(), routes)
            return routes.keys
        }
    }

    /** Counts real RouteProcessor initialization through its public JSR 269 extension point. */
    private fun installCountingProcessor(): File {
        val original = compilerJar()
        val root = temporary.newFolder("counting-processor")
        val counter = File(root, "executions.txt")
        val java = File(root, "CountingRouteProcessor.java").apply {
            writeText("""
                package com.alibaba.android.arouter.compiler.processor;
                @javax.annotation.processing.SupportedAnnotationTypes("com.alibaba.android.arouter.facade.annotation.Route")
                public class CountingRouteProcessor extends RouteProcessor {
                    @Override public synchronized void init(javax.annotation.processing.ProcessingEnvironment environment) {
                        try (java.io.FileWriter writer = new java.io.FileWriter(environment.getOptions().get("arouterCounter"), true)) {
                            writer.write("init\n");
                        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                        super.init(environment);
                    }
                }
            """.trimIndent())
        }
        val cp = processorJars.joinToString(File.pathSeparator) { it.path }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-proc:none", "-classpath", cp, "-d", root.path, java.path))
        val replacement = File(root, "counted.jar")
        JarFile(original).use { jar ->
            JarOutputStream(replacement.outputStream()).use { output ->
                jar.entries().asSequence().forEach { entry ->
                    output.putNextEntry(JarEntry(entry.name))
                    val content = jar.getInputStream(entry).use { it.readBytes() }
                    output.write(if (entry.name == "META-INF/services/javax.annotation.processing.Processor") {
                        String(content).replace("processor.RouteProcessor", "processor.CountingRouteProcessor").toByteArray()
                    } else content)
                    output.closeEntry()
                }
                val path = "com/alibaba/android/arouter/compiler/processor/CountingRouteProcessor.class"
                output.putNextEntry(JarEntry(path))
                output.write(File(root, path).readBytes())
                output.closeEntry()
            }
        }
        replaceCompiler(original, replacement)
        module = module.copy(javaAnnotationProcessorOptions = module.javaAnnotationProcessorOptions.orEmpty() + ("arouterCounter" to counter.path))
        updateModule()
        return counter
    }

    private fun establishGradleBaseline() {
        File(context.incrementalDataDir, "arouter").deleteRecursively()
        val generated = File(temporary.root, "gradle-generated").apply { deleteRecursively(); mkdirs() }
        val classes = module.buildPathInfo.javaClassPath.apply { deleteRecursively(); mkdirs() }
        val javac = javax.tools.ToolProvider.getSystemJavaCompiler()
        val paths = context.getModuleDependencies(module, CompileTask(listOf(source), classes, CompileStatusHolder.DEFAULT)) + supportFragment.path
        javac.getStandardFileManager(null, null, null).use { manager ->
            val sources = module.sourceDirs.flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "java" }.toList() }
            val arguments = listOf("-classpath", paths.joinToString(File.pathSeparator), "-processorpath", paths.joinToString(File.pathSeparator),
                "-processor", "com.alibaba.android.arouter.compiler.processor.AutowiredProcessor,com.alibaba.android.arouter.compiler.processor.InterceptorProcessor,com.alibaba.android.arouter.compiler.processor.RouteProcessor",
                "-AAROUTER_MODULE_NAME=routes", "-s", generated.path, "-d", classes.path)
            assertTrue(javac.getTask(null, manager, null, arguments, null, manager.getJavaFileObjectsFromFiles(sources)).call())
        }
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(listOf(generated))
        compiler = JuggAptCompiler(context, object : Disposable { override fun dispose() = Unit })
    }

    private fun compilerJar(): File = processorJars.single { it.name.startsWith("arouter-compiler-") || it.parentFile == temporary.root }

    private fun updateModule() {
        source = source.copy(module = module)
        whenever(context.modulesWithOrder).thenReturn(listOf(module))
        whenever(context.modules).thenReturn(mapOf(module.name to module))
    }

    private fun replaceCompiler(original: File, replacement: File) {
        processorJars = processorJars.map { if (it == original) replacement else it }
        module = module.copy(annotationProcessorDependencies = processorJars.map { LibraryDependency(it.name, it) })
        updateModule()
    }

    private fun useSupportClasspath() {
        libraryJars = libraryJars.filterNot { it.name.startsWith("fragment-") } + supportFragment
    }

    private fun fragmentNode(name: String) = ClassNode("", "L$name;", 0, emptyList(), emptyList(), emptyList(), "", null)

    private fun jetifyCompiler() {
        val original = compilerJar()
        val converted = File(temporary.root, "androidx-compiler.jar")
        Processor.createProcessor4(checkNotNull(ConfigParser.loadDefaultConfig()))
            .transform2(setOf(FileMapping(original, converted)), skipLibsWithAndroidXReferences = false)
        replaceCompiler(original, converted)
    }

    private fun rewriteJar(original: File, name: String, spi: String? = null, comment: String = ""): File {
        val result = File(temporary.root, name)
        JarFile(original).use { input ->
            JarOutputStream(result.outputStream()).use { output ->
                output.setComment(comment)
                input.entries().asSequence().forEach { entry ->
                    output.putNextEntry(JarEntry(entry.name).apply { time = entry.time })
                    val content = if (spi != null && entry.name == "META-INF/services/javax.annotation.processing.Processor") {
                        spi.toByteArray()
                    } else input.getInputStream(entry).use { it.readBytes() }
                    output.write(content)
                    output.closeEntry()
                }
            }
        }
        return result
    }

    private fun generate(): List<File> = compiler.compile(
        CompileTask(listOf(source), File(temporary.root, "out"), CompileStatusHolder.DEFAULT),
    ).outputs.map { it.file }

    private fun writeSource(annotation: String): CompileFile {
        val root = module.sourceDirs.single()
        val file = File(root, "demo/Screen.java").apply {
            parentFile.mkdirs()
            writeText("""
                package demo;
                import com.alibaba.android.arouter.facade.annotation.Route;
                $annotation
                public class Screen extends android.app.Activity {}
            """.trimIndent())
        }
        return CompileFile(CompileFile.Type.Java, file, root, module)
    }
}
