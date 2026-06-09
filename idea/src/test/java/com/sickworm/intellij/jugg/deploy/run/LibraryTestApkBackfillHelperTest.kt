package com.sickworm.intellij.jugg.deploy.run

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestTargetResolveException
import com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBackfillPlan
import com.sickworm.intellij.jugg.deploy.run.instrument.LibraryTestApkBackfillHelper
import com.sickworm.intellij.jugg.gradle.compile.GradleCompileResult
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class LibraryTestApkBackfillHelperTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `missing self targeting library test apk runs only the owning androidTest task`() {
        val projectDir = temp.newFolder("project")
        val sourceRoot = File(projectDir, "library1/src/androidTest/kotlin").apply { mkdirs() }
        val sourceFile = File(sourceRoot, "FooTest.kt").apply { writeText("class FooTest") }
        val testApkFile = File(projectDir, "library1/build/outputs/apk/androidTest/debug/library1-debug-androidTest.apk")
        testApkFile.parentFile.mkdirs()
        testApkFile.writeText("test apk")
        val module = androidTestModule(projectDir, sourceRoot)
        val compileClient = RecordingCompileClient(testApkFile)
        val uiHandler = RecordingUiHandler()
        var backfilledApks = emptyList<ApkInfo>()
        var installedApks = emptyList<ApkInfo>()
        val buildRecords = mutableListOf<String>()
        val helperContext = createHelper(
            projectDir = projectDir,
            module = module,
            compileClient = compileClient,
            onApksBackfilled = { backfilledApks = it },
            recordBuildHistory = { recordModule, plan ->
                buildRecords += "${recordModule.name}:${recordModule.buildVariant}:${plan.gradleTask}"
            },
        )

        val result = helperContext.helper.backfillIfNeeded(
            spec = AndroidTestRunSpec(null, null, sourcePath = sourceFile.path),
            data = JuggDeployData.forInstall(emptyList()),
            uiHandler = uiHandler,
            installBackfilledApks = { installedApks = it },
        )

        assertEquals(listOf("Library Test APK missing. Run Gradle compile once to build the test APK."), uiHandler.balloons)
        assertEquals("./gradlew :library1:assembleDebugAndroidTest", compileClient.compileCommand)
        assertEquals("library1/build/outputs/apk/androidTest/debug/*.apk", compileClient.outputApkName)
        assertEquals(listOf("com.example.library1.test"), result.apks.map { it.applicationId })
        assertEquals(result.apks, backfilledApks)
        assertEquals(result.apks, installedApks)
        verify(helperContext.manager).updateApkInfos(result.apks)
        assertEquals(
            listOf("library1.androidTest:debugAndroidTest::library1:assembleDebugAndroidTest"),
            buildRecords,
        )
    }

    @Test
    fun `failed backfill does not record build history`() {
        val projectDir = temp.newFolder("project")
        val sourceRoot = File(projectDir, "library1/src/androidTest/kotlin").apply { mkdirs() }
        val sourceFile = File(sourceRoot, "FooTest.kt").apply { writeText("class FooTest") }
        val testApkFile = File(projectDir, "library1/build/outputs/apk/androidTest/debug/library1-debug-androidTest.apk")
        val module = androidTestModule(projectDir, sourceRoot)
        val compileClient = RecordingCompileClient(testApkFile, succeeds = false)
        val buildRecords = mutableListOf<String>()
        val helperContext = createHelper(
            projectDir = projectDir,
            module = module,
            compileClient = compileClient,
            onApksBackfilled = {},
            recordBuildHistory = { recordModule, plan ->
                buildRecords += "${recordModule.name}:${recordModule.buildVariant}:${plan.gradleTask}"
            },
        )

        assertThrows(AndroidTestTargetResolveException::class.java) {
            helperContext.helper.backfillIfNeeded(
                spec = AndroidTestRunSpec(null, null, sourcePath = sourceFile.path),
                data = JuggDeployData.forInstall(emptyList()),
                uiHandler = RecordingUiHandler(),
                installBackfilledApks = {},
            )
        }

        assertEquals(emptyList<String>(), buildRecords)
    }

    @Test
    fun `install callback failure still records successful build history`() {
        val projectDir = temp.newFolder("project")
        val sourceRoot = File(projectDir, "library1/src/androidTest/kotlin").apply { mkdirs() }
        val sourceFile = File(sourceRoot, "FooTest.kt").apply { writeText("class FooTest") }
        val testApkFile = File(projectDir, "library1/build/outputs/apk/androidTest/debug/library1-debug-androidTest.apk")
        testApkFile.parentFile.mkdirs()
        testApkFile.writeText("test apk")
        val module = androidTestModule(projectDir, sourceRoot)
        val compileClient = RecordingCompileClient(testApkFile)
        val buildRecords = mutableListOf<String>()
        val helperContext = createHelper(
            projectDir = projectDir,
            module = module,
            compileClient = compileClient,
            onApksBackfilled = {},
            recordBuildHistory = { recordModule, plan ->
                buildRecords += "${recordModule.name}:${recordModule.buildVariant}:${plan.gradleTask}"
            },
        )

        assertThrows(IllegalStateException::class.java) {
            helperContext.helper.backfillIfNeeded(
                spec = AndroidTestRunSpec(null, null, sourcePath = sourceFile.path),
                data = JuggDeployData.forInstall(emptyList()),
                uiHandler = RecordingUiHandler(),
                installBackfilledApks = { throw IllegalStateException("install failed") },
            )
        }

        assertEquals(
            listOf("library1.androidTest:debugAndroidTest::library1:assembleDebugAndroidTest"),
            buildRecords,
        )
    }

    @Test
    fun `build history failure does not fail successful backfill`() {
        val projectDir = temp.newFolder("project")
        val sourceRoot = File(projectDir, "library1/src/androidTest/kotlin").apply { mkdirs() }
        val sourceFile = File(sourceRoot, "FooTest.kt").apply { writeText("class FooTest") }
        val testApkFile = File(projectDir, "library1/build/outputs/apk/androidTest/debug/library1-debug-androidTest.apk")
        testApkFile.parentFile.mkdirs()
        testApkFile.writeText("test apk")
        val module = androidTestModule(projectDir, sourceRoot)
        val compileClient = RecordingCompileClient(testApkFile)
        val helperContext = createHelper(
            projectDir = projectDir,
            module = module,
            compileClient = compileClient,
            onApksBackfilled = {},
            recordBuildHistory = { _, _ -> throw IllegalStateException("history failed") },
        )

        val result = helperContext.helper.backfillIfNeeded(
            spec = AndroidTestRunSpec(null, null, sourcePath = sourceFile.path),
            data = JuggDeployData.forInstall(emptyList()),
            uiHandler = RecordingUiHandler(),
            installBackfilledApks = {},
        )

        assertEquals(listOf("com.example.library1.test"), result.apks.map { it.applicationId })
        verify(helperContext.manager).updateApkInfos(result.apks)
    }

    private fun createHelper(
        projectDir: File,
        module: ModuleInfo,
        compileClient: RecordingCompileClient,
        onApksBackfilled: (List<ApkInfo>) -> Unit,
        recordBuildHistory: (
            module: ModuleInfo,
            plan: LibraryTestApkBackfillPlan,
        ) -> Unit = { _, _ -> },
    ): HelperContext {
        return HelperContext().also {
            it.helper = it.createHelper(projectDir, module, compileClient, onApksBackfilled, recordBuildHistory)
        }
    }

    private class HelperContext {
        lateinit var helper: LibraryTestApkBackfillHelper
        lateinit var manager: CompileContextManager

        fun createHelper(
            projectDir: File,
            module: ModuleInfo,
            compileClient: RecordingCompileClient,
            onApksBackfilled: (List<ApkInfo>) -> Unit,
            recordBuildHistory: (
                module: ModuleInfo,
                plan: LibraryTestApkBackfillPlan,
            ) -> Unit,
        ): LibraryTestApkBackfillHelper {
            val project = mock(Project::class.java)
            whenever(project.basePath).thenReturn(projectDir.path)
            val history = mock(IDeployHistoryManager::class.java)
            whenever(history.getFullBuildInfo()).thenReturn(FullBuildInfo("./gradlew :app:assembleDebug", com.sickworm.intellij.jugg.compiler.BuildTarget.APP, 0L))
            manager = mock(CompileContextManager::class.java)
            whenever(manager.getProjectInfo()).thenReturn(JuggProjectInfo(mapOf(module.name to module)))
            return LibraryTestApkBackfillHelper(
                project = project,
                pathManager = JuggPathManager(projectDir),
                deployHistoryManager = history,
                compileContextManager = manager,
                compileClientFactory = { compileClient },
                logger = Logger.getInstance(LibraryTestApkBackfillHelperTest::class.java),
                apkInfoReader = {
                    listOf(
                        ApkInfo(
                            files = listOf(
                                ApkFileUnit(
                                    "com.example.library1.test",
                                    "",
                                    true,
                                    it.single()
                                )
                            ),
                            applicationId = "com.example.library1.test",
                            instrumentationTargetPackage = "com.example.library1.test",
                        )
                    )
                },
                onApksBackfilled = onApksBackfilled,
                recordBuildHistory = recordBuildHistory,
            )
        }
    }

    private class RecordingUiHandler : CompileUiHandler by CompileUiHandler.DEFAULT {
        val balloons = mutableListOf<String>()

        override fun notifyByBalloon(text: String) {
            balloons += text
        }
    }

    private fun androidTestModule(projectDir: File, sourceRoot: File): ModuleInfo {
        return ModuleInfo.virtualModule.copy(
            name = "library1.androidTest",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = File(projectDir, "library1"),
            projectRootDir = projectDir,
            sourceDirs = listOf(sourceRoot),
            buildVariant = "debugAndroidTest",
            applicationId = "com.example.library1.test",
            instrumentationTargetPackage = "com.example.library1.test",
            buildPathInfo = ModuleBuildPathInfo(projectDir, File(projectDir, "library1"), "debugAndroidTest"),
        )
    }

    private class RecordingCompileClient(
        private val apkFile: File,
        private val succeeds: Boolean = true,
    ) : IGradleCompileClient {
        override var terminalOutputListener: IGradleCompileClient.TerminalOutputListener =
            IGradleCompileClient.TerminalOutputListener.DEFAULT
        lateinit var compileCommand: String
        lateinit var outputApkName: String

        override fun login(juggGradleCompileOptions: com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions) {
            compileCommand = juggGradleCompileOptions.compileCommand
            outputApkName = juggGradleCompileOptions.outputApkName
        }

        override fun compileAndFetchResult(isOnlyFetchResult: Boolean): GradleCompileResult {
            if (!succeeds) {
                return GradleCompileResult.failed(false, "compile failed")
            }
            return GradleCompileResult.success(listOf(apkFile))
        }

        override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>) = null
        override fun fetchLibraryChanges(incDeployTimes: Int) = null
        override fun cancelAction(isByUser: Boolean) = Unit
        override fun dispose() = Unit
    }
}
