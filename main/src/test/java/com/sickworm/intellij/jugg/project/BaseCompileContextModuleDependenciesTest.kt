package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class BaseCompileContextModuleDependenciesTest {

    @Test
    fun `included build module uses target application R before module outputs`() {
        withFixture { fixture ->
            val dependencies = fixture.context.getModuleDependencies(fixture.includedModule, fixture.task)

            assertTrue(dependencies.indexOf(fixture.applicationR.absolutePath) <
                    dependencies.indexOf(fixture.includedKotlinClasses.absolutePath))
        }
    }

    @Test
    fun `included build source inlines target application resource id`() {
        withFixture { fixture ->
            compileRClass(fixture.root, fixture.targetRClasses, 1)
            writeClassesToJar(fixture.targetRClasses, fixture.applicationR)
            compileRClass(fixture.root, fixture.includedKotlinClasses, 2)
            val dependencies = fixture.context.getModuleDependencies(fixture.includedModule, fixture.task)
            val outputDir = File(fixture.root, "compiled-source")
            val source = File(fixture.root, "UseR.java").apply {
                writeText("public class UseR { public static final int VALUE = sample.R.id.value; }")
            }

            val result = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-classpath",
                dependencies.joinToString(File.pathSeparator),
                "-d",
                outputDir.absolutePath,
                source.absolutePath,
            )

            assertEquals(0, result)
            URLClassLoader(arrayOf(outputDir.toURI().toURL()), null).use { classLoader ->
                assertEquals(1, classLoader.loadClass("UseR").getField("VALUE").getInt(null))
            }
        }
    }

    @Test
    fun `included build source uses host feature R before included outputs`() {
        withFixture { fixture ->
            compileRClass(fixture.root, fixture.targetFeatureRClasses, 1)
            writeClassesToJar(fixture.targetFeatureRClasses, fixture.featureR)
            compileRClass(fixture.root, fixture.includedKotlinClasses, 2)
            val dependencies = fixture.context.getModuleDependencies(fixture.includedModule, fixture.task)
            val outputDir = File(fixture.root, "compiled-feature-source")
            val source = File(fixture.root, "UseFeatureR.java").apply {
                writeText("public class UseFeatureR { public static final int VALUE = sample.R.id.value; }")
            }

            val result = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-classpath",
                dependencies.joinToString(File.pathSeparator),
                "-d",
                outputDir.absolutePath,
                source.absolutePath,
            )

            assertEquals(0, result)
            URLClassLoader(arrayOf(outputDir.toURI().toURL()), null).use { classLoader ->
                assertEquals(1, classLoader.loadClass("UseFeatureR").getField("VALUE").getInt(null))
            }
        }
    }

    @Test
    fun `primary build module keeps module outputs before final R`() {
        withFixture { fixture ->
            val dependencies = fixture.context.getModuleDependencies(fixture.primaryLibraryModule, fixture.task)

            assertTrue(dependencies.indexOf(fixture.primaryLibraryKotlinClasses.absolutePath) <
                    dependencies.indexOf(fixture.applicationR.absolutePath))
        }
    }

    @Test
    fun `included build application keeps its module outputs before target R`() {
        withFixture { fixture ->
            val dependencies = fixture.context.getModuleDependencies(fixture.includedApplicationModule, fixture.task)

            assertTrue(dependencies.indexOf(fixture.includedApplicationKotlinClasses.absolutePath) <
                    dependencies.indexOf(fixture.applicationR.absolutePath))
        }
    }

    @Test
    fun `included build unknown module keeps its module outputs before target R`() {
        withFixture { fixture ->
            val unknownModule = fixture.includedModule.copy(moduleType = ModuleInfo.Type.Unknown)
            val dependencies = fixture.context.getModuleDependencies(unknownModule, fixture.task)

            assertTrue(dependencies.indexOf(fixture.includedKotlinClasses.absolutePath) <
                    dependencies.indexOf(fixture.applicationR.absolutePath))
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("jugg_include_build_r_order_").toFile()
        try {
            block(createFixture(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createFixture(root: File): Fixture {
        val projectDir = File(root, "main").apply { mkdirs() }
        val applicationModule = module(projectDir, File(projectDir, "app"), "app", ModuleInfo.Type.Application)
        val featureModule = module(
            projectDir,
            File(projectDir, "feature"),
            "feature",
            ModuleInfo.Type.DynamicFeature,
        )
        val includedModule = module(projectDir, File(root, "included/library"), "includedLibrary", ModuleInfo.Type.Library)
        val includedApplicationModule = module(
            projectDir,
            File(root, "included/app"),
            "includedApp",
            ModuleInfo.Type.Application,
        )
        val primaryLibraryModule = module(projectDir, File(root, "external/library"), "primaryLibrary", ModuleInfo.Type.Library)
        val applicationR = createRJar(applicationModule)
        val featureR = createRJar(featureModule)
        val targetRClasses = File(root, "target-r-classes")
        val targetFeatureRClasses = File(root, "target-feature-r-classes")
        val includedKotlinClasses = includedModule.buildPathInfo.kotlinClassPath.apply { mkdirs() }
        val includedApplicationKotlinClasses = includedApplicationModule.buildPathInfo.kotlinClassPath.apply { mkdirs() }
        val primaryLibraryKotlinClasses = primaryLibraryModule.buildPathInfo.kotlinClassPath.apply { mkdirs() }
        createRJar(includedModule)
        createRJar(includedApplicationModule)
        createRJar(primaryLibraryModule)

        val androidHome = File(root, "android-sdk")
        File(androidHome, "platforms/android-34/android.jar").apply {
            parentFile.mkdirs()
            JarOutputStream(outputStream()).use { }
        }
        val apk = File(root, "app.apk").apply { createNewFile() }
        val context = BaseCompileContext(
            logger = StdLogger("BaseCompileContextModuleDependenciesTest"),
            tempCompileDir = File(root, "compiled"),
            tempModuleDir = File(root, "temp-module"),
            androidHome = androidHome,
            modules = linkedMapOf(
                applicationModule.name to applicationModule,
                featureModule.name to featureModule,
                includedModule.name to includedModule,
                includedApplicationModule.name to includedApplicationModule,
                primaryLibraryModule.name to primaryLibraryModule,
            ),
            apkInfos = listOf(ApkInfo(apk, "com.example.app")),
            projectDir = projectDir,
            incrementalDataDir = File(root, "incremental"),
            cmdCompileEnv = emptyList(),
            scene = ICompileContext.Scene.IDE,
            deployFileManager = mock<DeployFileManager>(),
            deployHistoryManager = mock<IDeployHistoryManager>(),
            customCompilerManager = mock<CustomCompilerManager>(),
            includedBuildModuleRoots = setOf(
                includedModule.moduleRootDir,
                includedApplicationModule.moduleRootDir,
            ),
        )
        val task = CompileTask(emptyList(), File(root, "output"), CompileStatusHolder.DEFAULT)
        return Fixture(
            root,
            context,
            task,
            applicationR,
            featureR,
            targetRClasses,
            targetFeatureRClasses,
            includedModule,
            includedKotlinClasses,
            includedApplicationModule,
            includedApplicationKotlinClasses,
            primaryLibraryModule,
            primaryLibraryKotlinClasses,
        )
    }

    private fun module(
        projectDir: File,
        moduleDir: File,
        name: String,
        type: ModuleInfo.Type,
    ): ModuleInfo {
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = type,
            moduleRootDir = moduleDir,
            projectRootDir = projectDir,
            buildVariant = "debug",
            compileVersion = "34",
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir, "debug", buildDirRelativePath = ""),
        )
    }

    private fun createRJar(module: ModuleInfo): File {
        return File(
            module.buildPathInfo.buildDir,
            "intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar",
        ).apply {
            parentFile.mkdirs()
            JarOutputStream(outputStream()).use { }
        }
    }

    private fun compileRClass(root: File, outputDir: File, value: Int) {
        val source = File(root, "r-$value/sample/R.java").apply {
            parentFile.mkdirs()
            writeText(
                "package sample; public final class R { " +
                        "public static final class id { public static final int value = $value; } }"
            )
        }
        val result = ToolProvider.getSystemJavaCompiler().run(
            null,
            null,
            null,
            "-d",
            outputDir.absolutePath,
            source.absolutePath,
        )
        assertEquals(0, result)
    }

    private fun writeClassesToJar(classesDir: File, jarFile: File) {
        JarOutputStream(jarFile.outputStream()).use { output ->
            listOf("sample/R.class", "sample/R\$id.class").forEach { path ->
                output.putNextEntry(JarEntry(path))
                File(classesDir, path).inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
    }

    private data class Fixture(
        val root: File,
        val context: BaseCompileContext,
        val task: CompileTask,
        val applicationR: File,
        val featureR: File,
        val targetRClasses: File,
        val targetFeatureRClasses: File,
        val includedModule: ModuleInfo,
        val includedKotlinClasses: File,
        val includedApplicationModule: ModuleInfo,
        val includedApplicationKotlinClasses: File,
        val primaryLibraryModule: ModuleInfo,
        val primaryLibraryKotlinClasses: File,
    )
}
