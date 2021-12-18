package com.sickworm.intellij.jugg.manager

import com.android.tools.deployer.JuggDeployData
import com.android.tools.deployer.JuggDeployerHelper
import com.sickworm.intellij.jugg.mock.androidApkPackage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopLevelFlowTest: BasicJuggMock() {

    @Test
    fun testDeviceStatusUpdate() {
        // just test assert in initEnv()
    }

    @Test
    fun testApkStructureReader() {
        val parsedApks = compileContextManager.compileContext.parsedApks
        assertEquals(1, parsedApks.size)

        val parsedApk = parsedApks[0]
        assertEquals(androidApkPackage, parsedApk.apkInfo.applicationId)
        assertTrue(parsedApk.apkInfo.file.exists())

        assertEquals(2394, parsedApk.classes.entries.size)
        assertEquals(12291, parsedApk.classes.entries.sumBy { it.value.fields.size })
        assertEquals(19352, parsedApk.classes.entries.sumBy { it.value.methods.size })
        assertEquals(748, parsedApk.overlayFiles.size)
    }

    @Test
    fun testCompileJavaFile() {
        changeFileAndNotify("ABC.java" to "ABC.java")
        checkCompileResult("ABC.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testCompileActivity() {
        changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testInstall() {
        val data = deployDataManager.getDeployData()
        juggDeployerHelper.runTask(data, project, true)
    }

    @Test
    fun testDeploy() {
        testInstall()

        changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)

        juggManager.deploy()
    }

    @Test
    fun testDeploy2() {
        testInstall()

        changeFileAndNotify("MainActivity2.changeImageAndToast.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)

        juggManager.deploy()
    }
}
