package com.sickworm.intellij.jugg.manager

import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.LayoutDumpResult
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.LastChangedDeployRegistry
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdb
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.mock.GradleBuildHelper
import com.sickworm.intellij.jugg.mock.RequiresDeviceRule
import com.sickworm.intellij.jugg.mock.projectInfo
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopLevelFlowTest {

    companion object {
        @ClassRule @JvmField val deviceRule = RequiresDeviceRule()
        private val jugg = MockJugg()
    }

    @Before
    fun resetAllState() {
        jugg.resetAllState()
    }

    @Test
    fun testInstallAndLaunch() {
        assertEquals(JuggDeployState.State.READY_FULL_COMPILE, jugg.deployStateManager.deployState.state)
        jugg.deploy()
        assertEquals(JuggDeployState.State.READY_DEPLOY, jugg.deployStateManager.deployState.state)
        assertEquals(1, jugg.deployTargetManager.getApks().size)
        assertEquals(1, jugg.compileContextManager.compileContext.apkInfos.size)
        val appBuildDir = jugg.compileContextManager.compileContext.modules.getValue("app").buildPathInfo.buildDir
        assertTrue(appBuildDir.invariantSeparatorsPath.endsWith("/build/app"))
        assertTrue(appBuildDir.isDirectory)
    }

    @Test
    fun testDeploy() {
        testInstallAndLaunch()

        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)

        jugg.deploy()
    }

    @Test
    fun testLayoutDumpWithBundledDragonflyRuntime() {
        testInstallAndLaunch()

        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
        jugg.deployCompiledApp()

        val device = jugg.deployTargetManager.getSelectedDevices().single()
        assertTrue(jugg.deployTargetManager.restartApp(device))
        jugg.waitingLaunchAppAndCheck()
        val result = waitForLayoutDump(ViewHierarchyClient(IdeaDeviceAdb(device, logger), projectInfo.packageName))
        assertEquals(null, result.errorMessage)
        val windows = JsonParser.parseString(requireNotNull(result.payloadJson))
            .asJsonObject.getAsJsonArray("windows")
        assertTrue(windows.size() > 0, "Dragonfly layout dump returned no windows")
        assertTrue(windows[0].asJsonObject.has("root"), "Dragonfly layout dump returned no root")
    }

    private fun waitForLayoutDump(client: ViewHierarchyClient): LayoutDumpResult {
        repeat(20) {
            val result = client.dumpLayout()
            if (result?.payloadJson != null || result?.errorMessage != null) {
                return result
            }
            Thread.sleep(250)
        }
        throw AssertionError("ViewHierarchy server did not return a layout dump")
    }

    @Test
    fun testLastChangedDeploymentSnapshot() {
        testInstallAndLaunch()

        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
        jugg.deployCompiledApp()

        val snapshot = requireNotNull(LastChangedDeployRegistry.INSTANCE.get(jugg.projectDir.path))
        assertTrue(snapshot.files.contains(File(jugg.projectDir, "app/src/main/java/com/example/myapplication/MainActivity2.java")))
    }

    @Test
    fun testDeploy2() {
        testInstallAndLaunch()

        jugg.changeFileAndNotify("MainActivity2.changeImageAndToast.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)

        jugg.deploy()
    }

    @Test
    fun testDeployKtActivity() {
        testInstallAndLaunch()

        jugg.changeFileAndNotify("MainActivity.kt" to "MainActivity.kt")
        jugg.checkCompileResult("MainActivity.kt",
            hotFixModifiedClassesSize = 1, hotReloadModifiedClassesSize = 4)

        jugg.deploy()
    }

    @Test
    fun testDeployIncrementalDataBindingSetterStore() {
        testInstallAndLaunch()

        changeAndRevert(
            "IncrementalBindingAdapters.kt" to "IncrementalBindingAdapters.kt",
            directory = "app/src/main/java/com/sickworm/jugg/demo/testcase/databinding",
        ) { sourceFiles ->
            changeAndRevert(
                "activity_data_binding_incremental_setter_store.xml" to
                    "activity_data_binding_incremental_setter_store.xml",
                directory = "app/src/main/res/layout",
            ) { resourceFiles ->
                jugg.notifyFileChanges(sourceFiles + resourceFiles)
                jugg.compileChangedFiles()

                assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty())
                assertTrue(File(
                    jugg.pathManager.stagingDir,
                    "classes/com/sickworm/jugg/demo/testcase/databinding/IncrementalBindingAdapters.dex",
                ).isFile)
                assertTrue(File(
                    jugg.pathManager.stagingDir,
                    "classes/com/example/myapplication/databinding/ActivityDataBindingIncrementalSetterStoreBindingImpl.dex",
                ).isFile)
                val deployData = jugg.deployFileManager.getDeployData()
                assertTrue(deployData.overlays.any {
                    it.name.endsWith("activity_data_binding_incremental_setter_store.xml")
                })

                jugg.deploy()
            }
        }
    }
}

/** Exercises Compose resource compilation through real device deployment and runtime consumption. */
class KmpComposeDeployFlowTest {

    companion object {
        private const val COMPILE_COMMAND = "./gradlew :app:assembleDebug"
        private const val LOG_TAG = "KmpComposeFlow"
        @ClassRule @JvmField val deviceRule = RequiresDeviceRule()
        private val pathManager = JuggPathManager(projectInfo.projectRoot)
        private val projectInfoFiles = listOf(pathManager.ideProjectInfoFile, pathManager.gradleProjectInfoFile)
        private val projectInfoBackups = mutableMapOf<File, ByteArray?>()

        @BeforeClass
        @JvmStatic
        fun prepareFixture() {
            projectInfoFiles.forEach { projectInfoBackups[it] = it.takeIf(File::exists)?.readBytes() }
            GradleBuildHelper.switchKotlinVersion("2.1")
            assembleFixture()
            writeCommonMainIdeProjectInfo()
        }

        @AfterClass
        @JvmStatic
        fun restoreFixture() {
            try {
                GradleBuildHelper.switchKotlinVersion("1.9")
            } finally {
                projectInfoBackups.forEach { (file, content) ->
                    if (content == null) file.delete() else file.apply { parentFile.mkdirs(); writeBytes(content) }
                }
            }
        }

        private fun assembleFixture() {
            val initScript = File("../main/src/main/resources/gradle/readProjectInfo.gradle.kts").absoluteFile
            val process = ProcessBuilder(
                "./gradlew", ":app:assembleDebug", "--no-daemon",
                "-I", initScript.absolutePath,
            ).directory(projectInfo.projectRoot).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "KMP Compose fixture assemble failed:\n$output" }
        }

        private fun writeCommonMainIdeProjectInfo() {
            val gradleInfo = ProjectInfoSerializer(pathManager.gradleProjectInfoFile, logger).load()
                ?: error("KMP Compose Gradle project info was not generated")
            val owner = gradleInfo.modules.getValue("kmpCompose")
            val ideOwner = owner.copy(
                moduleType = ModuleInfo.Type.Unknown,
                libraryDependencies = emptyList(),
                runtimeLibraryDependencies = emptyList(),
            )
            val commonMain = ModuleInfo.virtualModule.copy(
                name = "kmpCompose.commonMain",
                moduleType = ModuleInfo.Type.Unknown,
                moduleRootDir = owner.moduleRootDir,
                projectRootDir = owner.projectRootDir,
                sourceDirs = listOf(
                    File(owner.moduleRootDir, "src/commonMain/kotlin"),
                    File(owner.moduleRootDir, "src/sharedMain/kotlin"),
                ),
                buildVariant = owner.buildVariant,
                buildPathInfo = owner.buildPathInfo,
            )
            ProjectInfoSerializer(pathManager.ideProjectInfoFile, logger).save(
                JuggProjectInfo(
                    modules = mapOf(
                        ideOwner.name to ideOwner,
                        commonMain.name to commonMain,
                    ),
                    agpR8Classpath = null,
                )
            )
        }
    }

    @Test
    fun deployComposeResourcesAndConsumeAccessorsAtRuntime() {
        val root = projectInfo.projectRoot
        val commonValue = File(root, "kmpCompose/src/commonMain/composeResourcesExtended/values/strings.xml")
        val activity = File(root, "app/src/main/java/com/example/myapplication/MainActivity.kt")
        withPatchedFiles(
            activity to runtimeProbeSource(activity.readText()),
        ) {
            assembleFixture()
            val jugg = MockJugg(compileCommand = COMPILE_COMMAND, isIdeSynced = true)
            jugg.resetAllState()
            val baselineLogStart = System.currentTimeMillis() / 1000
            deployAllowingUnknownEmulatorArch(jugg)
            val baselineLogcat = readRuntimeLog(jugg, baselineLogStart, "|Android baseline title")
            assertTrue(
                baselineLogcat.contains("[JUGG_KMP]") && baselineLogcat.contains("|Android baseline title"),
                "Baseline Compose resources were not consumed before incremental deploy:\n$baselineLogcat",
            )

            withPatchedFiles(
                commonValue to commonValue.readText()
                    .replace("Baseline title", "Runtime common")
                    .replace("Android baseline title", "Runtime android"),
            ) {
                jugg.notifyFileChanges(listOf(commonValue))
                jugg.compileChangedFiles()

                assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty())
                assertTargetApkOwnership(jugg)
                assertNoIncrementalGradle(jugg.readLatestProjectLog())
                val logStart = System.currentTimeMillis() / 1000
                jugg.deployCompiledApp()
                val logcat = readRuntimeLog(jugg, logStart, "|Runtime android")
                assertTrue(
                    logcat.contains("[JUGG_KMP]") && logcat.contains("|Runtime android"),
                    "Updated Compose resources were not consumed after cached baseline:\n$logcat",
                )
            }
        }
    }

    @Test
    fun deployBusinessExpectActualChangesAtRuntime() {
        val jugg = MockJugg(compileCommand = COMPILE_COMMAND, isIdeSynced = true)
        jugg.resetAllState()
        deployAllowingUnknownEmulatorArch(jugg)
        val root = projectInfo.projectRoot
        val common = File(root, "kmpCompose/src/commonMain/kotlin/com/sickworm/jugg/demo/kmp/PlatformLabel.kt")
        val actual = File(root, "kmpCompose/src/androidMain/kotlin/com/sickworm/jugg/demo/kmp/PlatformLabel.android.kt")
        val activity = File(root, "app/src/main/java/com/example/myapplication/MainActivity.kt")
        val originalActual = actual.readText()
        withPatchedFiles(
            common to common.readText().replace(":baseline", ":runtime common"),
            actual to originalActual,
            activity to businessRuntimeProbeSource(activity.readText()),
        ) {
            jugg.notifyFileChanges(listOf(common, activity))
            jugg.compileChangedFiles()

            assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty(), jugg.readLatestProjectLog())
            assertBusinessTargetApkOwnership(jugg)
            assertNoIncrementalGradle(jugg.readLatestProjectLog())
            var logStart = System.currentTimeMillis() / 1000
            jugg.deployCompiledApp()
            var logcat = readRuntimeLog(jugg, logStart, "[JUGG_KMP_BUSINESS]")
            assertTrue(
                logcat.contains("[JUGG_KMP_BUSINESS] common:runtime common|Android"),
                "Changed common source was not consumed at runtime:\n$logcat",
            )

            actual.writeText(originalActual.replace("\"Android\"", "\"Runtime Android\""))
            jugg.notifyFileChanges(listOf(actual))
            jugg.compileChangedFiles()

            assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty(), jugg.readLatestProjectLog())
            assertBusinessTargetApkOwnership(jugg)
            assertNoIncrementalGradle(jugg.readLatestProjectLog())
            logStart = System.currentTimeMillis() / 1000
            jugg.deployCompiledApp()
            logcat = readRuntimeLog(jugg, logStart, "[JUGG_KMP_BUSINESS]")
            assertTrue(
                logcat.contains("[JUGG_KMP_BUSINESS] common:runtime common|Runtime Android"),
                "Changed actual source was not consumed at runtime:\n$logcat",
            )
        }
    }

    private fun readRuntimeLog(jugg: MockJugg, logStart: Long, marker: String = "[JUGG_KMP]"): String {
        var logcat = ""
        repeat(40) {
            logcat = jugg.readLogcatSince(logStart, LOG_TAG)
            if (logcat.contains(marker)) return logcat
            Thread.sleep(250)
        }
        return logcat
    }

    private fun deployAllowingUnknownEmulatorArch(jugg: MockJugg) {
        try {
            jugg.deploy()
        } catch (error: AssertionError) {
            if (error.message?.contains("ARCH_UNKNOWN") != true) throw error
            jugg.juggManager.updateDeployState()
        }
    }

    private fun runtimeProbeSource(source: String): String = injectRuntimeProbe(
        source,
        """kotlin.concurrent.thread {
            val snapshot = kotlinx.coroutines.runBlocking {
                com.sickworm.jugg.demo.kmp.KmpComposeAndroidResourceCase.runtimeSnapshot()
            }
            Log.i("$LOG_TAG", "[JUGG_KMP] ${'$'}snapshot")
        }""",
    )

    private fun businessRuntimeProbeSource(source: String): String = injectRuntimeProbe(
        source,
        """Log.i(
            "$LOG_TAG",
            "[JUGG_KMP_BUSINESS] ${'$'}{com.sickworm.jugg.demo.kmp.platformMarker()}|" +
                com.sickworm.jugg.demo.kmp.platformLabel(),
        )""",
    )

    private fun injectRuntimeProbe(source: String, probe: String): String {
        val sourceWithLogImport = if (source.contains("import android.util.Log")) source else source.replace(
            "import android.os.Bundle",
            "import android.os.Bundle\nimport android.util.Log",
        )
        val benchmark = "Log.i(BENCHMARK_LOG_TAG, BENCHMARK_LOG_MARKER)"
        if (sourceWithLogImport.contains(benchmark)) {
            return sourceWithLogImport.replace(benchmark, "$benchmark\n        $probe")
        }
        return sourceWithLogImport.replace(
            "setContentView(R.layout.activity_main)",
            "setContentView(R.layout.activity_main)\n        $probe",
        )
    }

    private fun assertTargetApkOwnership(jugg: MockJugg) {
        val outputs = jugg.deployFileManager.getStagingFiles().filter {
            it.type == CompileOutput.Type.Asset ||
                (it.type == CompileOutput.Type.Dex && it.relativeFile.path.contains("generated/resources"))
        }
        val apks = jugg.compileContextManager.compileContext.apkInfos
            .flatMap { apk -> apk.files.map { it.apkFile.path } }
            .toSet()
        assertTrue(outputs.isNotEmpty())
        assertTrue(outputs.all { output -> output.apkPath in apks || output.targetApkPaths.any(apks::contains) })
    }

    private fun assertBusinessTargetApkOwnership(jugg: MockJugg) {
        val outputs = jugg.deployFileManager.getStagingFiles().filter {
            it.type == CompileOutput.Type.Dex && it.relativeFile.path.contains("com/sickworm/jugg/demo/kmp")
        }
        val apks = jugg.compileContextManager.compileContext.apkInfos
            .flatMap { apk -> apk.files.map { it.apkFile.path } }
            .toSet()
        assertTrue(outputs.isNotEmpty())
        assertTrue(outputs.all { output -> output.apkPath in apks || output.targetApkPaths.any(apks::contains) })
    }

    private fun assertNoIncrementalGradle(log: String) {
        assertTrue(!log.contains("./gradlew"), log)
        assertTrue(!log.contains("generateComposeResClass"), log)
        assertTrue(!log.contains("generateResourceAccessors"), log)
    }

    private fun withPatchedFiles(vararg patches: Pair<File, String>, block: () -> Unit) {
        val backups = patches.associate { it.first to it.first.readBytes() }
        try {
            patches.forEach { (file, content) -> file.writeText(content) }
            block()
        } finally {
            backups.forEach { (file, content) -> file.writeBytes(content) }
        }
    }
}
