package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.RequiresDeviceRule
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import kotlin.test.assertTrue

/** Verifies Hilt injection after Jugg recompiles Android entry points without Gradle transforms. */
class HiltTopLevelFlowTest {

    companion object {
        private const val HILT_SOURCE_DIR =
            "app/src/hilt/java/com/sickworm/jugg/demo/testcase/hilt"
        private const val ACTIVITY_COMPONENT =
            "com.example.myapplication/com.sickworm.jugg.demo.testcase.hilt.HiltTestActivity"
        private const val RECEIVER_COMPONENT =
            "com.example.myapplication/com.sickworm.jugg.demo.testcase.hilt.HiltTestReceiver"
        private const val RECEIVER_ACTION = "com.example.myapplication.HILT_TEST"
        private val fixtureCommand = listOf(":app:assembleDebug", "-PjuggHiltFixture=true")

        @ClassRule
        @JvmField
        val deviceRule = RequiresDeviceRule()

        private lateinit var jugg: MockJugg

        @BeforeClass
        @JvmStatic
        fun prepareFixture() {
            AssembleAndroidProjectOnce.ensure(compileCommand = fixtureCommand, forceAssemble = true)
            jugg = MockJugg(
                compileCommand = "./gradlew ${fixtureCommand.joinToString(" ")}",
                isIdeSynced = true,
            )
        }

        @AfterClass
        @JvmStatic
        fun restoreDefaultFixture() {
            AssembleAndroidProjectOnce.ensure(
                compileCommand = listOf(":app:assembleDebug"),
                forceAssemble = true,
            )
        }
    }

    @Before
    fun resetAllState() {
        jugg.resetAllState()
    }

    @Test
    fun preservesActivityAndReceiverInjectionAfterIncrementalUpdates() {
        jugg.deploy()
        assertRuntimeMarker("activity:baseline:injected") { launchActivity() }
        assertRuntimeMarker("receiver:baseline:injected") { sendBroadcast() }

        jugg.changeFileAndNotify(
            "HiltTestActivity.kt" to "HiltTestActivity.kt",
            "HiltTestReceiver.kt" to "HiltTestReceiver.kt",
            directory = HILT_SOURCE_DIR,
        )
        jugg.checkCompileResult(
            "HiltTestActivity.kt",
            "HiltTestReceiver.kt",
            filePackageName = "com.sickworm.jugg.demo.testcase.hilt",
            hotFixModifiedClassesSize = 2,
        )
        jugg.deployCompiledApp()

        assertRuntimeMarker("activity:updated:injected") { launchActivity() }
        assertRuntimeMarker("receiver:updated:injected") { sendBroadcast() }

        jugg.changeFileAndNotify(
            "HiltTestActivitySecond.kt" to "HiltTestActivity.kt",
            directory = HILT_SOURCE_DIR,
        )
        jugg.checkCompileResult(
            "HiltTestActivity.kt",
            filePackageName = "com.sickworm.jugg.demo.testcase.hilt",
            hotReloadModifiedClassesSize = 1,
        )
        jugg.deployCompiledApp()

        assertRuntimeMarker("activity:second:injected") { launchActivity() }
    }

    private fun launchActivity() {
        runAdb("shell", "am", "start", "-W", "-n", ACTIVITY_COMPONENT)
    }

    private fun sendBroadcast() {
        runAdb(
            "shell",
            "am",
            "broadcast",
            "-a",
            RECEIVER_ACTION,
            "-n",
            RECEIVER_COMPONENT,
        )
    }

    private fun assertRuntimeMarker(expected: String, trigger: () -> Unit) {
        val logStart = System.currentTimeMillis() / 1000
        trigger()
        var logcat = ""
        repeat(40) {
            logcat = jugg.readLogcatSince(logStart, "HiltFlow")
            if (expected in logcat) {
                return
            }
            Thread.sleep(250)
        }
        throw AssertionError("Missing $expected in logcat:\n$logcat")
    }

    private fun runAdb(vararg arguments: String) {
        val process = ProcessBuilder(jugg.adbCommand(*arguments)).start()
        val output = String(process.inputStream.readBytes()) + String(process.errorStream.readBytes())
        assertTrue(process.waitFor() == 0, output)
    }
}
