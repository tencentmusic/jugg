package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.ModuleApkBelongs
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleDependency
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class BaseCompilerTest {

    private lateinit var testRoot: File

    @Before
    fun setUp() {
        testRoot = File(TestGlobal.buildDir, "base_compiler_test")
        testRoot.deleteRecursively()
        testRoot.mkdirs()
    }

    @Test
    fun compile_shouldKeepModulesSeparateWhenTheyShareModuleRootDir() {
        val moduleRoot = File(testRoot, "app")
        val appModule = createModule("app", moduleRoot, "debug")
        val androidTestModule = createModule(
            name = "app.androidTest",
            moduleRootDir = moduleRoot,
            buildVariant = "debugAndroidTest",
            moduleDependencies = listOf(ModuleDependency("app")),
            instrumentationTargetPackage = "com.example.myapplication",
        )
        val context = createContext(appModule, androidTestModule)
        val compiler = RecordingCompiler(context)

        val appSourceDir = File(moduleRoot, "src/main/java").apply { mkdirs() }
        val androidTestSourceDir = File(moduleRoot, "src/androidTest/java").apply { mkdirs() }
        val appFile = File(appSourceDir, "AppFile.kt").apply { writeText("class AppFile") }
        val androidTestFile = File(androidTestSourceDir, "AndroidTestFile.kt").apply { writeText("class AndroidTestFile") }

        val task = CompileTask(
            files = listOf(
                CompileFile(CompileFile.Type.Kotlin, appFile, appSourceDir, appModule),
                CompileFile(CompileFile.Type.Kotlin, androidTestFile, androidTestSourceDir, androidTestModule),
            ),
            outputDir = File(testRoot, "staging"),
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        compiler.compile(task)

        assertEquals(
            listOf(
                "app" to listOf("AppFile.kt"),
                "app.androidTest" to listOf("AndroidTestFile.kt"),
            ),
            compiler.moduleCompileRecords,
        )
    }

    @Test
    fun compile_shouldCompileAndroidTestModulesAfterNonTestModules() {
        val moduleRoot = File(testRoot, "app")
        val libraryRoot = File(testRoot, "library")
        val libraryModule = createModule("library", libraryRoot, "debug")
        val appModule = createModule("app", moduleRoot, "debug", moduleDependencies = listOf(ModuleDependency("library")))
        val androidTestModule = createModule(
            name = "app.androidTest",
            moduleRootDir = moduleRoot,
            buildVariant = "debugAndroidTest",
            moduleDependencies = listOf(ModuleDependency("app")),
            instrumentationTargetPackage = "com.example.myapplication",
        )
        val context = createContext(libraryModule, appModule, androidTestModule)
        val compiler = RecordingCompiler(context)

        val librarySourceDir = File(libraryRoot, "src/main/java").apply { mkdirs() }
        val appSourceDir = File(moduleRoot, "src/main/java").apply { mkdirs() }
        val androidTestSourceDir = File(moduleRoot, "src/androidTest/java").apply { mkdirs() }
        val libraryFile = File(librarySourceDir, "LibraryFile.kt").apply { writeText("class LibraryFile") }
        val appFile = File(appSourceDir, "AppFile.kt").apply { writeText("class AppFile") }
        val androidTestFile = File(androidTestSourceDir, "AndroidTestFile.kt").apply { writeText("class AndroidTestFile") }

        val task = CompileTask(
            files = listOf(
                CompileFile(CompileFile.Type.Kotlin, androidTestFile, androidTestSourceDir, androidTestModule),
                CompileFile(CompileFile.Type.Kotlin, appFile, appSourceDir, appModule),
                CompileFile(CompileFile.Type.Kotlin, libraryFile, librarySourceDir, libraryModule),
            ),
            outputDir = File(testRoot, "staging"),
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        compiler.compile(task)

        assertEquals(
            listOf(
                "library" to listOf("LibraryFile.kt"),
                "app" to listOf("AppFile.kt"),
                "app.androidTest" to listOf("AndroidTestFile.kt"),
            ),
            compiler.moduleCompileRecords,
        )
    }

    @Test
    fun compile_shouldKeepRootDirFallbackForNonTestModules() {
        val moduleRoot = File(testRoot, "renamed_app")
        val currentModule = createModule("currentApp", moduleRoot, "debug")
        val staleModule = createModule("staleApp", moduleRoot, "debug")
        val context = createContext(currentModule)
        val compiler = RecordingCompiler(context)

        val appSourceDir = File(moduleRoot, "src/main/java").apply { mkdirs() }
        val appFile = File(appSourceDir, "AppFile.kt").apply { writeText("class AppFile") }

        val task = CompileTask(
            files = listOf(CompileFile(CompileFile.Type.Kotlin, appFile, appSourceDir, staleModule)),
            outputDir = File(testRoot, "staging"),
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        compiler.compile(task)

        assertEquals(
            listOf("currentApp" to listOf("AppFile.kt")),
            compiler.moduleCompileRecords,
        )
    }

    @Test
    fun splitApkAndCompile_shouldCompileAllBelongsApksForOneModule() {
        val moduleRoot = File(testRoot, "library1")
        val libraryModule = createModule("library1", moduleRoot, "debug")
        val baseUnit = ApkFileUnit("com.example.app", "", true, File("/base.apk"))
        val testUnit = ApkFileUnit("com.example.library1.test", "", true, File("/test.apk"))
        val context = createContext(
            libraryModule,
            apkInfos = listOf(
                ApkInfo(listOf(baseUnit), "com.example.app"),
                ApkInfo(listOf(testUnit), "com.example.library1.test"),
            ),
            moduleBelongsApkMap = ModuleApkBelongs(
                primaryApkMap = mapOf(libraryModule to baseUnit),
                allApkMap = mapOf(libraryModule to listOf(baseUnit, testUnit)),
            ),
        )
        val compiler = RecordingApkCompiler(context)
        val assetDir = File(moduleRoot, "src/main/assets").apply { mkdirs() }
        val assetFile = File(assetDir, "config.json").apply { writeText("{}") }
        val task = CompileTask(
            files = listOf(CompileFile(CompileFile.Type.Asset, assetFile, assetDir, libraryModule)),
            outputDir = File(testRoot, "staging"),
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        compiler.splitApkAndCompile(task)

        assertEquals(
            listOf("/base.apk" to listOf("config.json"), "/test.apk" to listOf("config.json")),
            compiler.apkCompileRecords,
        )
    }

    private fun createContext(
        vararg modules: ModuleInfo,
        apkInfos: List<ApkInfo>? = null,
        moduleBelongsApkMap: ModuleApkBelongs? = null,
    ): SimpleCompileContext {
        val moduleMap = linkedMapOf<String, ModuleInfo>()
        modules.forEach { moduleMap[it.name] = it }
        return SimpleCompileContext(
            logger = TestGlobal.logger,
            tempCompileDir = File(testRoot, "compiled"),
            tempModuleDir = File(testRoot, "temp_module"),
            androidHome = TestGlobal.androidHome,
            androidJar = TestGlobal.androidJar,
            modules = moduleMap,
            apkInfos = apkInfos ?: listOf(ApkInfo(File(testRoot, "app.apk"), "com.example.myapplication")),
            projectDir = testRoot,
            incrementalDataDir = File(testRoot, "incremental"),
            deployedFiles = mutableListOf(),
            customModuleBelongsApkMap = moduleBelongsApkMap,
        )
    }

    private fun createModule(
        name: String,
        moduleRootDir: File,
        buildVariant: String,
        moduleDependencies: List<ModuleDependency> = emptyList(),
        instrumentationTargetPackage: String? = null,
    ): ModuleInfo {
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = ModuleInfo.Type.Library,
            projectRootDir = testRoot,
            moduleRootDir = moduleRootDir,
            buildVariant = buildVariant,
            buildPathInfo = ModuleBuildPathInfo(testRoot, moduleRootDir, buildVariant, buildDirRelativePath = ""),
            moduleDependencies = moduleDependencies,
            instrumentationTargetPackage = instrumentationTargetPackage,
        )
    }

    private class RecordingCompiler(context: ICompileContext) : BaseCompiler(context, TestGlobal.mockParentDisposable) {
        val moduleCompileRecords = mutableListOf<Pair<String, List<String>>>()

        override val supportedTypes: List<CompileFile.Type> = listOf(CompileFile.Type.Kotlin)

        override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
            moduleCompileRecords.add(module.name to task.files.map { it.file.name })
            return CompileResult(task, task.files.map { Result.success(it) }, emptyList())
        }
    }

    private class RecordingApkCompiler(context: ICompileContext) : BaseCompiler(context, TestGlobal.mockParentDisposable) {
        val apkCompileRecords = mutableListOf<Pair<String, List<String>>>()

        override val supportedTypes: List<CompileFile.Type> = listOf(CompileFile.Type.Asset)

        override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
            return CompileResult(task, emptyList(), emptyList())
        }

        override fun doApkCompile(task: CompileTask, apkFileUnit: ApkFileUnit): CompileResult {
            apkCompileRecords.add(apkFileUnit.apkFile.path to task.files.map { it.file.name })
            return CompileResult(task, task.files.map { Result.success(it) }, emptyList())
        }
    }
}
