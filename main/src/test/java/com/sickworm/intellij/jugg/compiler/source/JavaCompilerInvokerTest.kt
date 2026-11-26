package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.spi.FileSystemProvider
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises Android platform isolation through real javac compilations. */
class JavaCompilerInvokerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val invoker = JavaCompilerInvoker()
    private val logger = StdLogger("JavaCompilerInvokerTest")
    private val androidJar = File(requireNotNull(System.getenv("ANDROID_HOME")), "platforms/android-30/android.jar")
    private val moduleSources = mapOf(
        "demo/Module.java" to "package demo; public class Module {}",
        "Use.java" to """
            import demo.*;
            public class Use extends android.app.Activity {
                Module value;
                java.util.List<String> names;
                public String greeting(String name) {
                    java.util.function.Supplier<String> suffix = () -> name;
                    return "Hi, " + suffix.get();
                }
            }
        """.trimIndent(),
    )
    private val hostOnlySources = mapOf("Use.java" to "public class Use { java.lang.ProcessHandle value; }")

    @Test
    fun `default Java 8 compilation resolves wildcard imported Module`() {
        assertModuleCompilation(compile(moduleSources, null), 52)
    }

    @Test
    fun `Java 11 compilation resolves wildcard imported Module`() {
        assertModuleCompilation(compile(moduleSources, "11"), 55)
    }

    @Test
    fun `Java 17 compilation resolves wildcard imported Module`() {
        assumeTrue(Runtime.version().feature() >= 17)
        assertModuleCompilation(compile(moduleSources, "17"), 61)
    }

    @Test
    fun `Android compilation bypasses unrelated file system providers`() {
        val providers = FileSystemProvider.installedProviders()
        val unrelatedProvider = mock<FileSystemProvider>()
        val unrelatedFileSystem = mock<FileSystem>()
        whenever(unrelatedProvider.scheme).thenReturn("file")
        whenever(unrelatedProvider.newFileSystem(any<Path>(), any())).thenReturn(unrelatedFileSystem)
        whenever(unrelatedFileSystem.getPath("/")).thenReturn(temporaryFolder.newFolder().toPath())
        mockStatic(FileSystemProvider::class.java).use { mocked ->
            mocked.`when`<List<FileSystemProvider>> { FileSystemProvider.installedProviders() }
                .thenReturn(listOf(unrelatedProvider) + providers)
            assertModuleCompilation(compile(moduleSources, "11"), 55)
        }
    }

    @Test
    fun `platform isolation failure retains normal Java compilation`() {
        val platformDir = temporaryFolder.newFolder("broken-platform")
        val platformJar = androidJar.copyTo(File(platformDir, "android.jar"))
        File(platformDir, "core-for-system-modules.jar").writeText("invalid ZIP")
        val sources = mapOf("Use.java" to "public class Use extends android.app.Activity { String value; }")
        assertModuleCompilation(compile(sources, "11", platformJar = platformJar), 55)
        val invalidSources = mapOf("Use.java" to "public class Use { MissingType value; }")
        val failure = compile(invalidSources, "11", platformJar = platformJar)
        assertFalse(failure.isAllSuccess)
        assertTrue(failure.failedFiles.single().getFailure().errorMessages.contains("MissingType"))
        assertTrue(failure.outputs.isEmpty())
    }

    @Test
    fun `platform cleanup failure retains successful Java output`() {
        val zipProvider = FileSystemProvider.installedProviders().first { it.scheme == "jar" }
        val platformJar = File(androidJar.parentFile, "core-for-system-modules.jar")
        zipProvider.newFileSystem(platformJar.toPath(), emptyMap<String, Any>()).use { actualFileSystem ->
            val provider = mock<FileSystemProvider>()
            val fileSystem = mock<FileSystem>()
            whenever(provider.scheme).thenReturn("jar")
            whenever(provider.newFileSystem(any<Path>(), any())).thenAnswer { call ->
                val path = call.getArgument<Path>(0)
                if (path == platformJar.toPath()) fileSystem
                else zipProvider.newFileSystem(path, call.getArgument<Map<String, Any>>(1))
            }
            whenever(fileSystem.getPath("/")).thenReturn(actualFileSystem.getPath("/"))
            doThrow(UnsupportedOperationException("close failed")).whenever(fileSystem).close()
            mockStatic(FileSystemProvider::class.java).use { mocked ->
                mocked.`when`<List<FileSystemProvider>> { FileSystemProvider.installedProviders() }
                    .thenReturn(listOf(provider))
                assertModuleCompilation(compile(moduleSources, "11"), 55)
            }
        }
    }

    @Test
    fun `Java 8 sources with Java 11 target keep Android platform isolation`() {
        assertModuleCompilation(compile(moduleSources, "1.8", targetVersion = "11"), 55)
    }

    @Test
    fun `Android compilation keeps lambdas without a system modules jar`() {
        val legacyJar = androidJar.copyTo(File(temporaryFolder.newFolder("legacy-platform"), "android.jar"))
        listOf("1.8", "11").forEach { version ->
            val classVersion = if (version == "1.8") 52 else 55
            assertModuleCompilation(compile(moduleSources, version, platformJar = legacyJar), classVersion)
        }
    }

    @Test
    fun `Android compilation rejects host-only APIs and keeps source diagnostics`() {
        listOf("1.8", "11").forEach { version ->
            val result = compile(hostOnlySources, version)
            assertFalse(result.isAllSuccess, "Android source $version must not resolve JDK ProcessHandle")
            val errors = result.failedFiles.joinToString { it.getFailure().errorMessages }
            assertTrue("ProcessHandle" in errors && "compiler.err.cant.resolve" in errors, errors)
            assertTrue(result.outputs.isEmpty())
        }
    }

    @Test
    fun `Java libraries retain host JDK APIs with Android jar on classpath`() {
        val result = compile(hostOnlySources, "11", moduleType = ModuleInfo.Type.JavaLibrary)
        assertTrue(result.isAllSuccess)
    }

    @Test
    fun `compilation without Android jar retains host JDK APIs`() {
        val result = compile(hostOnlySources, "11", includeAndroidJar = false)
        assertTrue(result.isAllSuccess)
    }

    @Test
    fun `Android annotation processors retain host JDK APIs and compile generated sources`() {
        val processorDir = temporaryFolder.newFolder("processor")
        val processorSource = File(processorDir, "HostProcessor.java").apply {
            writeText("""
                import javax.annotation.processing.*;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                import java.util.Set;
                @SupportedAnnotationTypes("*")
                @SupportedSourceVersion(SourceVersion.RELEASE_11)
                public class HostProcessor extends AbstractProcessor {
                    private boolean generated;
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (!round.processingOver() && !generated) {
                            try (java.io.Writer writer = processingEnv.getFiler().createSourceFile("Generated").openWriter()) {
                                writer.write("public class Generated { android.app.Activity value; " +
                                    "public static final long PID = " + ProcessHandle.current().pid() + "L; }");
                            } catch (java.io.IOException e) { throw new RuntimeException(e); }
                            generated = true;
                        }
                        return false;
                    }
                }
            """.trimIndent())
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-proc:none", "-d", processorDir.absolutePath, processorSource.absolutePath,
        ))
        File(processorDir, "META-INF/services/javax.annotation.processing.Processor").apply {
            parentFile.mkdirs()
            writeText("HostProcessor")
        }
        val result = compile(
            mapOf("Use.java" to "public class Use { Generated value; }"), "11",
            options = JavaCompilerInvoker.Options(
                isEnableApt = true, isCanAutoRetry = false, aptPaths = listOf(processorDir),
            ),
        )
        assertTrue(result.isAllSuccess, result.failedFiles.joinToString { it.getFailure().errorMessages })
        assertTrue(result.outputs.any { it.file.name == "Generated.class" })
    }

    private fun assertModuleCompilation(result: CompileResult, classVersion: Int) {
        val errors = result.failedFiles.joinToString { it.getFailure().errorMessages }
        assertTrue(result.isAllSuccess, errors)
        val output = result.outputs.single { it.file.name == "Use.class" }
        assertEquals(classVersion, ByteBuffer.wrap(output.file.readBytes()).getShort(6).toInt())
    }

    private fun compile(
        sources: Map<String, String>,
        sourceVersion: String?,
        targetVersion: String? = sourceVersion,
        moduleType: ModuleInfo.Type = ModuleInfo.Type.Application,
        includeAndroidJar: Boolean = true,
        options: JavaCompilerInvoker.Options = JavaCompilerInvoker.Options(isCanAutoRetry = false),
        platformJar: File = androidJar,
    ): CompileResult {
        val root = temporaryFolder.newFolder()
        val sourceDir = File(root, "src")
        val module = ModuleInfo.virtualModule.copy(
            name = "app",
            moduleType = moduleType,
            projectRootDir = root,
            moduleRootDir = root,
            javaSourceCompatibility = sourceVersion,
            javaTargetCompatibility = targetVersion,
            buildPathInfo = ModuleBuildPathInfo(root, root, "debug", buildDirRelativePath = ""),
        )
        val files = sources.map { (path, text) ->
            val file = File(sourceDir, path).apply {
                parentFile.mkdirs()
                writeText(text)
            }
            CompileFile(CompileFile.Type.Java, file, sourceDir, module)
        }
        val task = CompileTask(files, File(root, "classes").apply { mkdirs() }, CompileStatusHolder.DEFAULT)
        val dependencies = if (includeAndroidJar) {
            assertTrue(platformJar.isFile, "Missing Android platform: $platformJar")
            listOf(platformJar.absolutePath)
        } else {
            emptyList()
        }
        val context = mock<ICompileContext>()
        whenever(context.androidHome).thenReturn(androidJar.parentFile.parentFile.parentFile)
        whenever(context.getModuleDependencies(any(), any())).thenReturn(dependencies)
        return invoker.compile(context, module, task, logger, options)
    }
}
