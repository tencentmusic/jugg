package com.sickworm.intellij.jugg.compiler.source.apt

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*
import java.io.File
import java.util.jar.JarFile
import kotlin.test.*

/** Uses the official processor to protect source generation and the ordinary-source fast path. */
class ButterKnifeJuggAptProcessorTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: ICompileContext
    private lateinit var module: ModuleInfo
    private lateinit var source: File
    private lateinit var libraries: List<File>
    private lateinit var compiler: JuggAptCompiler

    @Before fun setUp() {
        val processors = System.getProperty("butterknife.test.processors").split(File.pathSeparator).map(::File)
        libraries = System.getProperty("butterknife.test.libraries").split(File.pathSeparator).map(::File).map { file ->
            if (file.extension != "aar") file else JarFile(file).use { jar ->
                File(temporary.root, file.nameWithoutExtension + ".jar").apply {
                    writeBytes(jar.getInputStream(jar.getJarEntry("classes.jar")).readBytes())
                }
            }
        } + processors + System.getProperty("arouter.test.libraries").split(File.pathSeparator).map(::File).filter { it.name.startsWith("annotation-") }
        val root = temporary.newFolder("src")
        module = ModuleInfo.virtualModule.copy(name = "binding", sourceDirs = listOf(root),
            projectRootDir = temporary.root, moduleRootDir = temporary.root,
            buildPathInfo = ModuleBuildPathInfo(temporary.root, temporary.root, "debug", buildDirRelativePath = "build"),
            annotationProcessorDependencies = processors.map { LibraryDependency(it.name, it) })
        val work = temporary.newFolder("work")
        val incremental = temporary.newFolder("incremental")
        val android = File(System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk", "platforms/android-34/android.jar")
        assertTrue(android.isFile)
        context = mock {
            on { logger } doReturn StdLogger("ButterKnifeTest")
            on { tempCompileDir } doReturn work
            on { incrementalDataDir } doReturn incremental
            on { customCompilers } doReturn emptyList()
            on { modules } doReturn mapOf(module.name to module)
            on { modulesWithOrder } doReturn listOf(module)
            on { tempModule } doReturn ModuleInfo.virtualModule
            on { cmdCompileEnv } doReturn emptyList()
            on { projectDir } doReturn temporary.root
            on { androidJar } doReturn android
        }
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(emptyList())
        whenever(context.getModuleDependencies(any(), any())).thenAnswer {
            listOf(android.path, module.buildPathInfo.javaClassPath.path) + libraries.map { it.path }
        }
        compiler = JuggAptCompiler(context, object : Disposable { override fun dispose() = Unit })
        source = File(root, "demo/Screen.java").apply { parentFile.mkdirs() }
    }

    @Test fun officialProcessorGeneratesFieldAndListenerBindings() {
        source.writeText("""
            package demo;
            public class Screen {
                @butterknife.BindView(android.R.id.text1) android.widget.TextView title;
                @butterknife.OnClick(android.R.id.text1) void clicked() {}
            }
        """.trimIndent())
        val generated = generate(source)
        val binding = generated.single { it.name == "Screen_ViewBinding.java" }.readText()
        assertTrue(binding.contains("target.title ="), binding)
        assertTrue(binding.contains("target.clicked()"), binding)
        assertTrue(binding.contains("android.R.id.text1"), binding)
    }

    @Test fun projectOptionsStillControlGeneratedBinding() {
        module = module.copy(javaAnnotationProcessorOptions = mapOf("AROUTER_MODULE_NAME" to "demo",
            "butterknife.minSdk" to "21"), kaptArguments = mapOf("butterknife.debuggable" to "false"))
        whenever(context.modules).thenReturn(mapOf(module.name to module))
        whenever(context.modulesWithOrder).thenReturn(listOf(module))
        source.writeText("package demo; public class Screen { @butterknife.BindView(android.R.id.text1) android.widget.TextView title; }")
        val binding = generate(source).single { it.name == "Screen_ViewBinding.java" }.readText()
        assertTrue(binding.contains("source.findViewById(android.R.id.text1)"), binding)
        assertFalse(binding.contains("Utils.findRequiredView"), binding)
    }

    @Test fun unrelatedSourceNeverResolvesClasspathOrParsesSyntax() {
        source.writeText("@Override This is intentionally not valid Java and has no ButterKnife annotations")
        assertTrue(generate(source).isEmpty())
        verify(context, never()).getModuleDependencies(any(), any())
    }

    @Test fun processorAbsentNeverReadsSource() {
        module = module.copy(annotationProcessorDependencies = emptyList())
        assertTrue(generate(source).isEmpty())
        verify(context, never()).getModuleDependencies(any(), any())
    }

    @Test fun commentMentionDoesNotRunProcessor() {
        source.writeText("package demo; /* butterknife.BindView */ public class Screen {}")
        assertTrue(generate(source).isEmpty())
        verify(context, never()).getModuleDependencies(any(), any())
    }

    @Test fun r2ReferencesUseRuntimeRAndNewNamesCompile() {
        val r = File(source.parentFile, "R.java").apply { writeText("package demo; public class R { public static class id { public static final int title = 123; } }") }
        val r2 = File(source.parentFile, "R2.java").apply { writeText("package demo; public class R2 { public static class id { public static final int old = 1; } }") }
        compileBaseline(listOf(r, r2))
        source.writeText("package demo; public class Screen { @butterknife.BindView(R2.id.title) android.widget.TextView title; }")
        val binding = generate(source).single { it.name == "Screen_ViewBinding.java" }
        assertTrue(binding.readText().contains("R.id.title"), binding.readText())
        assertFalse(binding.readText().contains("R2.id.title"))
        compileBaseline(listOf(source, binding, r, File(context.tempCompileDir, "butterknife/binding/r2/demo/R2.java")))
    }

    @Test fun parentCanBeResolvedFromBaselineWithoutItsSourceInput() {
        val parent = File(source.parentFile, "Parent.java").apply {
            writeText("package demo; public class Parent { @butterknife.BindView(android.R.id.text1) android.widget.TextView parent; }")
        }
        compileBaseline(listOf(parent) + generate(parent))
        source.writeText("package demo; public class Screen extends Parent { @butterknife.BindView(android.R.id.text2) android.widget.TextView title; }")
        val binding = generate(source).single { it.name == "Screen_ViewBinding.java" }
        assertTrue(binding.readText().contains("extends Parent_ViewBinding"), binding.readText())
        compileBaseline(listOf(source, binding))
    }

    @Test fun firstParentBindingRegeneratesExistingChildBinding() {
        val parent = File(source.parentFile, "Parent.java").apply { writeText("package demo; public class Parent {}") }
        compileBaseline(listOf(parent))
        source.writeText("package demo; public class Screen extends Parent { @butterknife.BindView(android.R.id.text2) android.widget.TextView title; }")
        compileBaseline(listOf(source) + generate(source))
        parent.writeText("package demo; public class Parent { @butterknife.BindView(android.R.id.text1) android.widget.TextView parent; }")
        val generated = generate(parent)
        val binding = generated.single { it.name == "Screen_ViewBinding.java" }
        assertTrue(binding.readText().contains("extends Parent_ViewBinding"), binding.readText())
        assertTrue(generated.contains(source))
        compileBaseline(generated + parent)
    }

    @Test fun kotlinAliasesAndListenerCompileThroughIsolatedKapt() {
        val toolchain = System.getProperty("arouter.test.kotlin").split(File.pathSeparator).map(::File)
        module = module.copy(kotlinPlugins = toolchain, kotlinJvmTarget = "1.8")
        libraries += toolchain.filter { it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("annotations-") }
        whenever(context.modules).thenReturn(mapOf(module.name to module))
        whenever(context.modulesWithOrder).thenReturn(listOf(module))
        val kotlin = File(source.parentFile, "KotlinScreen.kt").apply { writeText("""
            package demo
            import butterknife.BindView as BV
            import butterknife.OnClick
            class KotlinScreen {
                @field:BV(android.R.id.text1) @JvmField var title: android.widget.TextView? = null
                @OnClick(android.R.id.text1) fun clicked() {}
            }
        """.trimIndent()) }
        val binding = generate(kotlin).single { it.name == "KotlinScreen_ViewBinding.java" }.readText()
        assertTrue(binding.contains("target.title ="), binding)
        assertTrue(binding.contains("target.clicked()"), binding)
    }

    @Test fun sourceFlowProducesDexForBusinessAndGeneratedBinding() {
        source.writeText("package demo; public class Screen { @butterknife.BindView(android.R.id.text1) android.widget.TextView title; }")
        whenever(context.moduleBelongsApkMap).thenReturn(com.sickworm.intellij.jugg.ModuleApkBelongs(emptyMap(), emptyMap()))
        whenever(context.getDesugarInfo(any<ClassPreparation>(), any(), any())).thenReturn(DesugarInfo.EMPTY)
        val flow = com.sickworm.intellij.jugg.compiler.source.SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
        val input = CompileFile(CompileFile.Type.Java, source, module.sourceDirs.first(), module)
        val result = flow.compile(CompileTask(listOf(input), File(temporary.root, "dex"), CompileStatusHolder.DEFAULT))
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        assertTrue(result.outputs.any { it.type == CompileOutput.Type.Dex && it.file.name.contains("Screen_ViewBinding") })
        assertTrue(File(module.buildPathInfo.javaClassPath, "demo/Screen.class").isFile)
    }

    @Test fun removingLastBindingFailsInsteadOfPublishingStaleBindings() {
        source.writeText("package demo; public class Screen { @butterknife.BindView(android.R.id.text1) android.widget.TextView title; }")
        generate(source)
        source.writeText("package demo; public class Screen { android.widget.TextView title; }")
        assertFailsWith<JuggAptFatalException> { generate(source) }
    }

    @Test fun removingLastNestedBindingFailsInsteadOfKeepingStaleBinding() {
        source.writeText("package demo; public class Screen { public static class Inner { @butterknife.BindView(android.R.id.text1) android.widget.TextView title; } }")
        assertTrue(generate(source).any { it.name == "Screen\$Inner_ViewBinding.java" })
        source.writeText("package demo; public class Screen { public static class Inner { android.widget.TextView title; } }")
        assertFailsWith<JuggAptFatalException> { generate(source) }
    }

    @Test fun r2SourceFlowCompilesNewPlaceholderAndRuntimeBindingTogether() {
        val r = File(source.parentFile, "R.java").apply { writeText("package demo; public class R { public static class id { public static final int title = 123; } }") }
        val r2 = File(source.parentFile, "R2.java").apply { writeText("package demo; public class R2 { public static class id { public static final int old = 1; } }") }
        compileBaseline(listOf(r, r2))
        source.writeText("package demo; public class Screen { @butterknife.BindView(R2.id.title) android.widget.TextView title; }")
        whenever(context.moduleBelongsApkMap).thenReturn(com.sickworm.intellij.jugg.ModuleApkBelongs(emptyMap(), emptyMap()))
        whenever(context.getDesugarInfo(any<ClassPreparation>(), any(), any())).thenReturn(DesugarInfo.EMPTY)
        val flow = com.sickworm.intellij.jugg.compiler.source.SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
        val input = CompileFile(CompileFile.Type.Java, source, module.sourceDirs.first(), module)
        val result = flow.compile(CompileTask(listOf(input), File(temporary.root, "r2-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        assertTrue(result.outputs.any { it.type == CompileOutput.Type.Dex && it.file.name.contains("Screen_ViewBinding") })
    }

    @Test fun kotlinR2AliasPreservesRuntimeResourceSymbols() {
        val r = File(source.parentFile, "R.java").apply { writeText("package demo; public class R { public static class id { public static final int title = 123; } }") }
        val r2 = File(source.parentFile, "R2.java").apply { writeText("package demo; public class R2 { public static class id { public static final int old = 1; } }") }
        compileBaseline(listOf(r, r2))
        val toolchain = System.getProperty("arouter.test.kotlin").split(File.pathSeparator).map(::File)
        module = module.copy(kotlinPlugins = toolchain, kotlinJvmTarget = "1.8")
        libraries += toolchain.filter { it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("annotations-") }
        whenever(context.modules).thenReturn(mapOf(module.name to module))
        whenever(context.modulesWithOrder).thenReturn(listOf(module))
        val kotlin = File(source.parentFile, "KotlinScreen.kt").apply { writeText("""
            package demo
            import butterknife.BindView as BV
            import demo.R2 as Resources
            class KotlinScreen {
                @field:BV(Resources.id.title) @JvmField var title: android.widget.TextView? = null
            }
        """.trimIndent()) }
        val binding = generate(kotlin).single { it.name == "KotlinScreen_ViewBinding.java" }.readText()
        assertTrue(binding.contains("R.id.title"), binding)
        assertFalse(binding.contains("R2.id.title"), binding)
    }

    private fun compileBaseline(files: List<File>) {
        val out = module.buildPathInfo.javaClassPath.apply { mkdirs() }
        val cp = libraries.map { it.path } + context.androidJar.path + out.path
        val args = listOf("-proc:none", "-classpath", cp.joinToString(File.pathSeparator), "-d", out.path) + files.distinct().map { it.path }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray()))
    }

    private fun generate(vararg files: File): List<File> {
        val inputs = files.map { CompileFile(if (it.extension == "kt") CompileFile.Type.Kotlin else CompileFile.Type.Java,
            it, module.sourceDirs.first(), module) }
        return compiler.compile(CompileTask(inputs, File(temporary.root, "out"), CompileStatusHolder.DEFAULT))
            .outputs.map { it.file }
    }
}
