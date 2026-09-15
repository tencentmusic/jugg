package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.context.BaseCompileContext
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleDependency
import com.sickworm.intellij.jugg.project.info.ModuleInfo
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

    @Test
    fun `unknown module uses module compile R when a stale aggregate R also exists`() {
        withFixture { fixture ->
            val unknownModule = fixture.unknownModule
            writeDrawableRJar(fixture.root, rJarAt(unknownModule, AGGREGATE_R_DIR), "other_icon", 1)
            val moduleCompileR =
                writeDrawableRJar(fixture.root, rJarAt(unknownModule, MODULE_COMPILE_R_DIR), ICON_FIELD, 2)

            val dependencies = fixture.context.getModuleDependencies(unknownModule, fixture.task)

            assertTrue(dependencies.contains(moduleCompileR.absolutePath))
            assertEquals(2, compileAndReadIconValue(fixture.root, dependencies, "unknown-module-source"))
        }
    }

    @Test
    fun `unknown module resolved as apk owner keeps using aggregate R`() {
        withRoot { root ->
            val projectDir = File(root, "main").apply { mkdirs() }
            val applicationModule = module(projectDir, File(projectDir, "app"), "app", ModuleInfo.Type.Unknown)
            val aggregateR = writeDrawableRJar(root, rJarAt(applicationModule, AGGREGATE_R_DIR), ICON_FIELD, 1)
            writeDrawableRJar(root, rJarAt(applicationModule, MODULE_COMPILE_R_DIR), ICON_FIELD, 2)
            // The aggregate R.jar must exist before the context resolves this unknown module as APK owner.
            val context = createContext(root, projectDir, linkedMapOf(applicationModule.name to applicationModule))
            val task = CompileTask(emptyList(), File(root, "output"), CompileStatusHolder.DEFAULT)

            val dependencies = context.getModuleDependencies(applicationModule, task)

            assertEquals(applicationModule.moduleRootDir.path, context.applicationModule?.moduleRootDir?.path)
            assertTrue(dependencies.contains(aggregateR.absolutePath))
            assertEquals(1, compileAndReadIconValue(root, dependencies, "unknown-apk-owner-source"))
        }
    }

    @Test
    fun `jugg temp R stays before gradle module compile R`() {
        withFixture { fixture ->
            val tempClasses = fixture.context.tempModule.buildPathInfo.javaClassPath.apply { mkdirs() }
            compileDrawableRClass(fixture.root, tempClasses, ICON_FIELD, 3)
            val moduleCompileR =
                writeDrawableRJar(fixture.root, rJarAt(fixture.unknownModule, MODULE_COMPILE_R_DIR), ICON_FIELD, 2)

            val dependencies = fixture.context.getModuleDependencies(fixture.unknownModule, fixture.task)

            assertTrue(dependencies.indexOf(tempClasses.absolutePath) < dependencies.indexOf(moduleCompileR.absolutePath))
            assertEquals(3, compileAndReadIconValue(fixture.root, dependencies, "temp-r-source"))
        }
    }

    @Test
    fun `androidTest module uses own aggregate R before owner module R`() {
        withRoot { root ->
            val projectDir = File(root, "main").apply { mkdirs() }
            val applicationModule = module(projectDir, File(projectDir, "app"), "app", ModuleInfo.Type.Application)
            val ownerModule = module(projectDir, File(projectDir, "library"), "library", ModuleInfo.Type.Library)
            val androidTestModule = ownerModule.copy(
                name = "library.androidTest",
                buildVariant = "debugAndroidTest",
                buildPathInfo = ownerModule.buildPathInfo.copy(buildVariant = "debugAndroidTest"),
                instrumentationTargetPackage = "sample.test",
                moduleDependencies = listOf(ModuleDependency(ownerModule.name)),
            )
            createRJar(applicationModule)
            val ownerR = writeDrawableRJar(
                root,
                rJarAt(ownerModule, MODULE_COMPILE_R_DIR),
                "owner_icon",
                1,
            )
            val androidTestR = writeDrawableRJar(
                root,
                rJarAt(androidTestModule, AGGREGATE_R_DIR),
                ICON_FIELD,
                2,
            )
            val context = createContext(
                root,
                projectDir,
                linkedMapOf(
                    applicationModule.name to applicationModule,
                    ownerModule.name to ownerModule,
                    androidTestModule.name to androidTestModule,
                ),
            )
            val task = CompileTask(emptyList(), File(root, "output"), CompileStatusHolder.DEFAULT)

            val dependencies = context.getModuleDependencies(androidTestModule, task)

            assertTrue(dependencies.indexOf(androidTestR.absolutePath) < dependencies.indexOf(ownerR.absolutePath))
            assertEquals(2, compileAndReadIconValue(root, dependencies, "android-test-source"))
        }
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("jugg_module_r_provider_").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        withRoot { root ->
            block(createFixture(root))
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
        val unknownModule = module(projectDir, File(root, "external/unknown-library"), "unknownLibrary", ModuleInfo.Type.Unknown)
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

        val context = createContext(
            root,
            projectDir,
            linkedMapOf(
                applicationModule.name to applicationModule,
                featureModule.name to featureModule,
                includedModule.name to includedModule,
                includedApplicationModule.name to includedApplicationModule,
                primaryLibraryModule.name to primaryLibraryModule,
                unknownModule.name to unknownModule,
            ),
            setOf(includedModule.moduleRootDir, includedApplicationModule.moduleRootDir),
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
            unknownModule,
        )
    }

    private fun createContext(
        root: File,
        projectDir: File,
        modules: Map<String, ModuleInfo>,
        includedBuildModuleRoots: Set<File> = emptySet(),
    ): BaseCompileContext {
        val androidHome = File(root, "android-sdk")
        File(androidHome, "platforms/android-34/android.jar").apply {
            parentFile.mkdirs()
            JarOutputStream(outputStream()).use { }
        }
        val apk = File(root, "app.apk").apply { createNewFile() }
        return BaseCompileContext(
            logger = StdLogger("BaseCompileContextModuleDependenciesTest"),
            tempCompileDir = File(root, "compiled"),
            tempModuleDir = File(root, "temp-module"),
            androidHome = androidHome,
            modules = modules,
            apkInfos = listOf(ApkInfo(apk, "com.example.app")),
            projectDir = projectDir,
            incrementalDataDir = File(root, "incremental"),
            cmdCompileEnv = emptyList(),
            scene = ICompileContext.Scene.IDE,
            deployFileManager = mock<DeployFileManager>(),
            deployHistoryManager = mock<IDeployHistoryManager>(),
            customCompilerManager = mock<CustomCompilerManager>(),
            includedBuildModuleRoots = includedBuildModuleRoots,
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
        return rJarAt(module, AGGREGATE_R_DIR).apply {
            parentFile.mkdirs()
            JarOutputStream(outputStream()).use { }
        }
    }

    private fun rJarAt(module: ModuleInfo, rJarDir: String): File {
        return File(module.buildPathInfo.buildDir, "intermediates/$rJarDir/${module.buildVariant}/R.jar")
    }

    private fun compileDrawableRClass(root: File, outputDir: File, fieldName: String, value: Int) {
        val source = File(root, "r-source-$fieldName-$value/sample/R.java").apply {
            parentFile.mkdirs()
            writeText(
                "package sample; public final class R { " +
                        "public static final class drawable { public static final int $fieldName = $value; } }"
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

    /** Writes a real R.jar declaring sample.R.drawable.[fieldName], mimicking one AGP R provider. */
    private fun writeDrawableRJar(root: File, jarFile: File, fieldName: String, value: Int): File {
        val classesDir = File(root, "r-classes-$fieldName-$value")
        compileDrawableRClass(root, classesDir, fieldName, value)
        jarFile.parentFile.mkdirs()
        writeClassesToJar(classesDir, jarFile)
        return jarFile
    }

    /** Compiles source reading sample.R.drawable.icon_close_cand_normal_v2_t and returns its inlined value. */
    private fun compileAndReadIconValue(root: File, dependencies: List<String>, sourceDirName: String): Int {
        val outputDir = File(root, "$sourceDirName-classes")
        val source = File(root, "$sourceDirName/UseDrawableR.java").apply {
            parentFile.mkdirs()
            writeText(
                "public class UseDrawableR { " +
                        "public static final int VALUE = sample.R.drawable.$ICON_FIELD; }"
            )
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
            return classLoader.loadClass("UseDrawableR").getField("VALUE").getInt(null)
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
        val relativePaths = classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(classesDir).path }
            .sorted()
            .toList()
        JarOutputStream(jarFile.outputStream()).use { output ->
            relativePaths.forEach { path ->
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
        val unknownModule: ModuleInfo,
    )

    private companion object {
        const val ICON_FIELD = "icon_close_cand_normal_v2_t"
        const val AGGREGATE_R_DIR = "compile_and_runtime_not_namespaced_r_class_jar"
        const val MODULE_COMPILE_R_DIR = "compile_r_class_jar"
    }
}
