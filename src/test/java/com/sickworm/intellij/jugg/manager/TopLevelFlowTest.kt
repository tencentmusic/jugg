package com.sickworm.intellij.jugg.manager

import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.AdbClient
import com.android.tools.idea.log.LogWrapper
import com.sickworm.intellij.jugg.BuildDemoApkTest
import com.sickworm.intellij.jugg.mock.DeviceClientMonitorTask
import com.sickworm.intellij.jugg.mock.androidApkPackage
import com.sickworm.intellij.jugg.mock.logger
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

        BuildDemoApkTest().checkApkStructure(parsedApk)
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
        Runtime.getRuntime()
            .exec("adb shell am force-stop $androidApkPackage")
            .waitFor()

        val data = jugg.deployDataManager.getDeployData()
        jugg.juggDeployerHelper.runTask(data, jugg.project, true)

        Runtime.getRuntime()
            .exec("adb shell am start -n $androidApkPackage/com.example.myapplication.MainActivity")
            .waitFor()

        checkDeployState()
    }

    private fun checkDeployState() {
        val device = jugg.device

        if (device.clients.size == 1) {
            val logger = LogWrapper(logger)
            val adb = AdbClient(device, logger)
            val pids = adb.getPids(androidApkPackage)
            if (pids.size == 1) {
                return
            }
        }

        // wait app launch
        var times = 0
        var isReady = false
        val monitor = DeviceClientMonitorTask()
        while (times++ < 5) {
            println("check app launch $times time")
            val socket = monitor.register(device)
            if (monitor.run(socket, device)) {
                isReady = true
            }
            socket.close()

            if (isReady) {
                break
            } else {
                Thread.sleep(1000)
            }
        }
        if (isReady) {
            println("app launched")
        } else {
            println("app not launched")
        }
        assertTrue(isReady)

        val clients = device.clients
        assertEquals(1, clients.size)

        val logger = LogWrapper(logger)
        val adb = AdbClient(device, logger)
        val pids = adb.getPids(androidApkPackage)
        assertEquals(1, pids.size)

        val arch = adb.getArch(pids)
        assertEquals(Deploy.Arch.ARCH_64_BIT, arch)
    }

    private fun checkBeforeDeploy() {
        checkDeployState()
    }

    @Test
    fun testDeploy() {
        testInstall()

        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)

        jugg.juggManager.deploy()
    }

    @Test
    fun testDeploy2() {
        testInstall()

        jugg.changeFileAndNotify("MainActivity2.changeImageAndToast.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)

        jugg.juggManager.deploy()
    }
}
