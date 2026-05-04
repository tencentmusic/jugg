package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleDependency
import com.sickworm.intellij.jugg.project.data.ModuleInfo
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

    private fun createContext(vararg modules: ModuleInfo): SimpleCompileContext {
        val moduleMap = linkedMapOf<String, ModuleInfo>()
        modules.forEach { moduleMap[it.name] = it }
        return SimpleCompileContext(
            logger = TestGlobal.logger,
            tempCompileDir = File(testRoot, "compiled"),
            tempModuleDir = File(testRoot, "temp_module"),
            androidHome = TestGlobal.androidHome,
            androidJar = TestGlobal.androidJar,
            modules = moduleMap,
            apkInfos = listOf(ApkInfo(File(testRoot, "app.apk"), "com.example.myapplication")),
            projectDir = testRoot,
            incrementalDataDir = File(testRoot, "incremental"),
            deployedFiles = mutableListOf(),
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
            buildPathInfo = ModuleBuildPathInfo(testRoot, moduleRootDir, buildVariant),
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
}
