package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.info.KspInfo
import com.sickworm.intellij.jugg.project.info.LibraryDependency
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import kotlin.test.*

/** L1 contract tests execute both real engines against the supplied custom ARouter processor. */
@RunWith(Parameterized::class)
class KspProcessorRunnerTest(private val ksp2: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "KSP2={0}")
        fun engines() = listOf(arrayOf(false), arrayOf(true))
    }
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: ICompileContext
    private lateinit var module: ModuleInfo
    private lateinit var source: File
    private lateinit var work: File
    private val fixture = ARouterKspFixture
    private val options = mapOf("AROUTER_MODULE_NAME" to "fixture", "MODULE_NAME" to "fixture")

    @Before fun prepare() {
        val root = temporary.newFolder("src")
        source = File(root, "Screen.kt").apply { writeText("""
            package demo
            object Paths { const val ROUTE = "/demo/before" }
            @com.alibaba.android.arouter.facade.annotation.Route(path = Paths.ROUTE, app = "custom")
            class Screen
        """.trimIndent()) }
        module = ModuleInfo.virtualModule.copy(name = "fixture", sourceDirs = listOf(root),
            moduleRootDir = temporary.root, projectRootDir = temporary.root, kotlinJvmTarget = "17",
            kotlinPlugins = fixture.compiler,
            kspInfo = KspInfo(useKsp2 = ksp2, languageVersion = "2.1", apiVersion = "2.1",
                runtimeDependencies = fixture.runtime(ksp2).map { LibraryDependency(it.name, it) }))
        work = File(temporary.root, "generation")
        context = mock()
        whenever(context.logger).thenReturn(StdLogger("KspRunnerTest"))
        whenever(context.getModuleDependencies(any(), any())).thenReturn((listOf(fixture.annotation) + fixture.compiler).map { it.path })
    }

    private fun generate(arguments: Map<String, String> = options): File = KspProcessorRunner().generate(
        context, module, listOf(source), listOf(fixture.processor, fixture.annotation) + fixture.processorDependencies,
        listOf(ARouterKspFixture.provider), arguments, work)

    @Test fun changedConstantGeneratesExecutableFreshRoutesWithCustomApp() {
        assertTrue(generate().walkTopDown().any { it.isFile && it.readText().contains("/demo/before") })
        source.writeText(source.readText().replace("/demo/before", "/demo/after"))
        val generated = generate().walkTopDown().filter { it.extension == "kt" }.toList()
        assertEquals(3, generated.size)
        assertFalse(generated.any { it.readText().contains("/demo/before") })
        val api = System.getProperty("arouter.test.libraries").split(File.pathSeparator).map(::File)
            .single { it.name.startsWith("arouter-api-") }
        val apiJar = File(temporary.root, "api.jar")
        JarFile(api).use { jar -> apiJar.writeBytes(jar.getInputStream(jar.getJarEntry("classes.jar")).use { it.readBytes() }) }
        val classes = temporary.newFolder("classes")
        fixture.compile(listOf(source) + generated, listOf(fixture.annotation, apiJar), classes)
        val paths = listOf(classes, fixture.annotation, apiJar) + fixture.compiler
        URLClassLoader(paths.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val name = generated.single { it.name.contains("Group") && !it.name.contains("Providers") }.readText()
            val typeName = Regex("class `([^`]+)`").find(name)!!.groupValues[1]
            val pkg = Regex("package ([\\w.]+)").find(name)!!.groupValues[1]
            val type = loader.loadClass("$pkg.$typeName")
            val routes = linkedMapOf<String, Any>()
            type.getMethod("loadInto", Map::class.java).invoke(type.getConstructor().newInstance(), routes)
            assertEquals(setOf("/demo/after"), routes.keys)
            assertEquals("custom", routes.values.single().javaClass.getMethod("getApp").invoke(routes.values.single()))
        }
    }

    @Test fun javaAndKotlinRoutesShareTheAggregate() {
        val java = File(source.parentFile, "Other.java").apply { writeText("""
            package demo;
            @com.alibaba.android.arouter.facade.annotation.Route(path = "/demo/java", app = "custom")
            public class Other {}
        """.trimIndent()) }
        val generated = KspProcessorRunner().generate(context, module, listOf(source, java),
            listOf(fixture.processor, fixture.annotation) + fixture.processorDependencies,
            listOf(ARouterKspFixture.provider), options, work)
        assertTrue(generated.walkTopDown().any { it.isFile &&
            it.readText().contains("/demo/java") && it.readText().contains("/demo/before") })
    }

    @Test fun processorFailureDoesNotReturnPreviousOutputs() {
        generate()
        assertFails { generate(emptyMap()) }
        assertFalse(File(work, "sources").walkTopDown().any { it.isFile })
        assertEquals(3, generate().walkTopDown().count { it.extension == "kt" })
    }

    @Test fun emptyCurrentOutputDoesNotReadOldGradleIndexes() {
        val gradle = File(temporary.root, "build/generated/ksp/debug/kotlin/Stale.kt")
        gradle.parentFile.mkdirs()
        gradle.writeText("stale index")
        source.writeText("package demo\nclass Screen")
        assertFalse(generate().walkTopDown().any { it.isFile })
        assertEquals("stale index", gradle.readText())
    }
}
