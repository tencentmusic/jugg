package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
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
        jugg.deployAndroidTest(initialSpec)
        assertTrue(jugg.deployStateManager.deployState.isReadyIncCompile)
        assertTrue(jugg.deployTargetManager.getApks().any { it.isTestApk })
        assertEquals(2, jugg.compileContextManager.compileContext.apkInfos.size)

        val logStart = System.currentTimeMillis() / 1000
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

        val logs = jugg.readLogcatSince(logStart, "JuggAndroidTest")
        assertTrue(logs.contains(MARKER), "Updated androidTest marker not found in logcat:\n$logs")
        val projectLog = jugg.readLatestProjectLog()
        assertFalse(projectLog.contains("Gradle BUILD_AND_INSTALL SUCCESSFUL"), "Expected incremental deploy, got install log:\n$projectLog")
        assertTrue(
            projectLog.contains("Apply Changes successfully finished"),
            "Expected incremental Jugg deploy log, got:\n$projectLog",
        )
    }
}
