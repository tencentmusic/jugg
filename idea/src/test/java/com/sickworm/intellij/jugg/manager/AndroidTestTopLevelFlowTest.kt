package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.RequiresDeviceRule
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidTestTopLevelFlowTest {

    companion object {
        @ClassRule @JvmField val deviceRule = RequiresDeviceRule()
        private val jugg = MockJugg()
        private const val TEST_CLASS = "com.example.myapplication.AppLogicInstrumentedTest"
        private const val TEST_METHOD = "incrementalAndroidTestMarker"
        private const val MARKER = "JUGG_ANDROID_TEST_INCREMENTAL_MARKER_V2"
    }

    @Before
    fun resetAllState() {
        jugg.resetAllState()
    }

    @Test
    fun androidTestIncrementalDeployRunsUpdatedTestApk() {
        val initialSpec = AndroidTestRunSpec(TEST_CLASS, "targetContextUsesAppPackage")
        val incrementalSpec = AndroidTestRunSpec(TEST_CLASS, TEST_METHOD)

        assertEquals(JuggDeployState.State.READY_FULL_COMPILE, jugg.deployStateManager.deployState.state)

        val logStart = System.currentTimeMillis() / 1000
        jugg.deployAndroidTest(initialSpec)
        assertTrue(jugg.deployStateManager.deployState.isReadyIncCompile)
        assertTrue(jugg.deployTargetManager.getApks().any { it.isTestApk })
        assertEquals(2, jugg.compileContextManager.compileContext.apkInfos.size)

        jugg.changeFileAndNotify(
            "AppLogicInstrumentedTest.incremental.kt" to "AppLogicInstrumentedTest.kt",
            directory = "app/src/androidTest/java/com/example/myapplication",
        )
        jugg.checkCompileResult(
            "AppLogicInstrumentedTest.kt",
            filePackageName = "com.example.myapplication",
            hotFixModifiedClassesSize = 1,
            apksSize = 2,
        )

        jugg.deployCompiledAndroidTest(incrementalSpec)

        // Verify both full deploy and incremental deploy tests ran via a single logcat read
        val logcat = jugg.readLogcatSince(logStart, "AppLogicInstrumentedTest")
        assertTrue(logcat.contains(MARKER), "Updated androidTest marker not found in logcat:\n$logcat")
        assertTrue(
            logcat.contains("[targetContextUsesAppPackage]"),
            "Full deploy androidTest log not found in logcat, deploy may not have taken effect:\n$logcat",
        )

        val projectLog = jugg.readLatestProjectLog()
        assertFalse(projectLog.contains("Gradle BUILD_AND_INSTALL SUCCESSFUL"), "Expected incremental deploy, got install log:\n$projectLog")
        assertTrue(
            projectLog.contains("Apply Changes successfully finished"),
            "Expected incremental Jugg deploy log, got:\n$projectLog",
        )
    }

    @Test
    fun appRunWithAndroidTestBuildTargetUsesNormalNoChangeFlow() {
        val oldConfirmSetting = JuggSettings.isConfirmFallbackWhenNoFileChanges
        try {
            JuggSettings.isConfirmFallbackWhenNoFileChanges = false
            val logStart = System.currentTimeMillis()
            jugg.deployAndroidTest(AndroidTestRunSpec(TEST_CLASS, "targetContextUsesAppPackage"))
            // The first app run establishes hasRun after the androidTest full build resets it.
            jugg.deployAppWithAndroidTestEnabled()

            jugg.deployAppWithAndroidTestEnabled()

            val projectLog = jugg.readProjectLogsSince(logStart)
            assertTrue(projectLog.contains("No file changes. will fallback to gradle compile."), projectLog)
            assertFalse(projectLog.contains("No file changes for androidTest, but current run should deploy directly."), projectLog)
        } finally {
            JuggSettings.isConfirmFallbackWhenNoFileChanges = oldConfirmSetting
        }
    }
}
