package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.BuildDemoApkTest
import com.sickworm.intellij.jugg.mock.androidApkPackage
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopLevelFlowTest {

    companion object {
        private val jugg = MockJugg()

        @BeforeClass
        @JvmStatic
        fun initEnv() {
            jugg.initEnv()
        }
    }

    @Before
    fun resetAllState() {
        jugg.resetAllState()
    }

    @Test
    fun testDeviceStatusUpdate() {
        // just test assert in initEnv()
    }

    @Test
    fun testApkStructureReader() {
        val parsedApks = jugg.compileContextManager.compileContext.parsedApks
        assertEquals(1, parsedApks.size)

        val parsedApk = parsedApks[0]
        assertEquals(androidApkPackage, parsedApk.apkInfo.applicationId)
        assertTrue(parsedApk.apkInfo.file.exists())

        BuildDemoApkTest().checkApkEntryInfo(parsedApk)
    }

    @Test
    fun testCompileJavaFile() {
        jugg.changeFileAndNotify("ABC.java" to "ABC.java")
        jugg.checkCompileResult("ABC.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testCompileActivity() {
        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testInstall() {
        jugg.installAndReStart()
    }

    @Test
    fun testDeploy() {
        testInstall()

        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)

        jugg.deploy()
    }

    @Test
    fun testDeploy2() {
        testInstall()

        jugg.changeFileAndNotify("MainActivity2.changeImageAndToast.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)

        jugg.deploy()
    }
}
