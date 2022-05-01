package com.sickworm.intellij.jugg.manager

import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Need an Android device for this test.
 */
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
        // already test assert in resetAllState
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
