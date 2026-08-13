package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.RequiresDeviceRule
import com.sickworm.intellij.jugg.mock.projectInfo
import org.junit.ClassRule
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class SplitClassLoaderFlowTest {

    companion object {
        private const val LOG_TAG = "JuggSplitClassLoader"
        private const val SUCCESS_MARKER = "[JUGG_SPLIT] split classloader ready"
        private const val SPLIT_PROPERTY = "-PjuggSplitClassLoaderFixture=true"
        private const val ISOLATED_SPLIT_PROPERTY = "-PjuggIsolatedSplitClassLoaderFixture=true"
        private const val OUTPUT_APKS =
            "build/app/outputs/apk/debug/*.apk;" +
                "build/split_classloader_feature/outputs/apk/debug/*.apk"

        @ClassRule
        @JvmField
        val deviceRule = RequiresDeviceRule()
    }

    @Test
    fun hotFixKeepsInstalledSplitTypesInOneClassLoader() {
        val activity = File(
            projectInfo.projectRoot,
            "app/src/main/java/com/example/myapplication/MainActivity.kt",
        )
        val original = activity.readText()
        var jugg: MockJugg? = null
        try {
            jugg = createJugg()
            jugg.resetAllState()
            clearCachedAgent(jugg)
            jugg.deploy()
            assertTrue(
                jugg.compileContextManager.compileContext.apkInfos.single().files.size == 2,
                "The split fixture must install base and feature APKs",
            )
            forceAndroidNClassLoaderOnNextLaunch(jugg)

            activity.writeText(addRuntimeProbe(original))
            jugg.notifyFileChanges(listOf(activity))
            jugg.compileChangedFiles()

            runAdb(jugg, "logcat", "-c")
            val logStart = System.currentTimeMillis() / 1000
            jugg.deployCompiledApp()
            val logcat = waitForRuntimeResult(jugg, logStart)
            assertTrue(
                logcat.contains(SUCCESS_MARKER) &&
                    logcat.contains("com.sickworm.intellij.jugg.hotfix.AndroidNClassLoader") &&
                    !logcat.contains("[JUGG_SPLIT] failed"),
                logcat,
            )
        } finally {
            activity.writeText(original)
            try {
                jugg?.let(::restoreRuntimeState)
            } finally {
                restoreDefaultFixture()
            }
        }
    }

    @Test
    fun hotFixDoesNotFlattenIsolatedSplits() {
        val activity = File(
            projectInfo.projectRoot,
            "app/src/main/java/com/example/myapplication/MainActivity.kt",
        )
        val original = activity.readText()
        var jugg: MockJugg? = null
        try {
            jugg = createJugg(ISOLATED_SPLIT_PROPERTY)
            jugg.resetAllState()
            clearCachedAgent(jugg)
            jugg.deploy()
            assertIsolatedSplitManifest()
            forceAndroidNClassLoaderOnNextLaunch(jugg)

            activity.writeText(addClassLoaderProbe(original))
            jugg.notifyFileChanges(listOf(activity))
            jugg.compileChangedFiles()

            runAdb(jugg, "logcat", "-c")
            val logStart = System.currentTimeMillis() / 1000
            jugg.deployCompiledApp()
            val logcat = waitForRuntimeResult(jugg, logStart)
            assertTrue(
                logcat.contains("com.sickworm.intellij.jugg.hotfix.AndroidNClassLoader") &&
                    !logcat.contains("split_split_classloader_feature.apk") &&
                    !logcat.contains("[JUGG_SPLIT] failed"),
                logcat,
            )
        } finally {
            activity.writeText(original)
            try {
                jugg?.let(::restoreRuntimeState)
            } finally {
                restoreDefaultFixture()
            }
        }
    }

    private fun createJugg(vararg extraProperties: String): MockJugg {
        val properties = listOf(SPLIT_PROPERTY) + extraProperties
        val command = listOf(":app:assembleDebug", ":split_classloader_feature:assembleDebug") + properties
        return MockJugg(
            compileCommand = "./gradlew ${command.joinToString(" ")}",
            outputApkName = OUTPUT_APKS,
            baselineCompileCommand = command,
        )
    }

    private fun addRuntimeProbe(source: String): String {
        val benchmark = "Log.i(BENCHMARK_LOG_TAG, BENCHMARK_LOG_MARKER)"
        val probe = "runSplitClassLoaderProbe()"
        val sourceWithProbe = source.replace(benchmark, "$benchmark\n        $probe")
        return sourceWithProbe.trimEnd().removeSuffix("}") + """

    private fun runSplitClassLoaderProbe() {
        try {
            val value = com.sickworm.jugg.demo.testcase.splitclassloader.BaseGiftStorage()
                .get()
                .blockingGet()
            val classLoader = com.sickworm.jugg.demo.testcase.splitclassloader.BaseGiftStorage::class.java.classLoader
            Log.i("$LOG_TAG", "[JUGG_SPLIT] ${'$'}value ${'$'}{classLoader.javaClass.name}")
        } catch (error: Throwable) {
            Log.e("$LOG_TAG", "[JUGG_SPLIT] failed", error)
        }
    }

}
"""
    }

    private fun addClassLoaderProbe(source: String): String {
        val benchmark = "Log.i(BENCHMARK_LOG_TAG, BENCHMARK_LOG_MARKER)"
        val probe = "Log.i(\"$LOG_TAG\", " +
            "\"[JUGG_SPLIT] ${'$'}{applicationContext.javaClass.classLoader}\")"
        return source.replace(benchmark, "$benchmark\n        $probe")
    }

    private fun assertIsolatedSplitManifest() {
        val manifest = File(
            projectInfo.projectRoot,
            "build/app/intermediates/merged_manifest/debug/AndroidManifest.xml",
        ).readText()
        assertTrue(manifest.contains("android:isolatedSplits=\"true\""), manifest)
    }

    private fun waitForRuntimeResult(jugg: MockJugg, logStart: Long): String {
        var logcat = ""
        repeat(40) {
            logcat = jugg.readLogcatSince(logStart, LOG_TAG)
            if (logcat.contains("[JUGG_SPLIT]")) return logcat
            Thread.sleep(250)
        }
        return logcat
    }

    private fun forceAndroidNClassLoaderOnNextLaunch(jugg: MockJugg) {
        runAdb(jugg, "shell", "run-as", projectInfo.packageName,
            "rm", "-f", "code_cache/.no_need_fix_dex_path_list")
        runAdb(jugg, "shell", "run-as", projectInfo.packageName,
            "touch", "code_cache/.need_fix_dex_path_list")
    }

    private fun clearCachedAgent(jugg: MockJugg) {
        runAdb(jugg, "shell", "rm", "-rf", "/data/local/tmp/jugg/${BuildConfig.AGENT_VERSION}")
        runAdb(jugg, "shell", "run-as", projectInfo.packageName,
            "rm", "-f", "code_cache/startup_agents/${BuildConfig.AGENT_VERSION}-jugg_jvmti_agent.so",
            "code_cache/startup_agents/${BuildConfig.AGENT_VERSION}-jugg_jvmti_agent_alt.so")
    }

    private fun restoreRuntimeState(jugg: MockJugg) {
        runAdb(jugg, "shell", "am", "force-stop", projectInfo.packageName)
        runAdb(jugg, "shell", "run-as", projectInfo.packageName,
            "rm", "-f", "code_cache/.need_fix_dex_path_list",
            "code_cache/.no_need_fix_dex_path_list")
        runAdb(jugg, "shell", "run-as", projectInfo.packageName,
            "touch", "code_cache/.no_need_fix_dex_path_list")
    }

    private fun restoreDefaultFixture() {
        AssembleAndroidProjectOnce.ensure(
            compileCommand = listOf(":app:assembleDebug"),
            forceAssemble = true,
        )
    }

    private fun runAdb(jugg: MockJugg, vararg arguments: String) {
        val process = ProcessBuilder(jugg.adbCommand(*arguments)).start()
        val output = String(process.inputStream.readBytes()) + String(process.errorStream.readBytes())
        assertTrue(process.waitFor() == 0, output)
    }
}
