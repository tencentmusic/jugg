package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.mock.RequiresDevice
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import kotlin.test.assertEquals

@RequiresDevice
class TopLevelFlowTest {

    companion object {
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
        Mockito.verify(jugg.juggStateListener, Mockito.times(1)).onDeployStateUpdate(JuggDeployState.READY)
    }

    @Test
    fun testDeploy() {
        testInstallAndLaunch()

        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)

        jugg.deploy()
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

        // TODO new class after desugar lambda. Use R8?
        jugg.changeFileAndNotify("MainActivity.kt" to "MainActivity.kt")
        jugg.checkCompileResult("MainActivity.kt",
            newClassesSize = 1, hotFixModifiedClassesSize = 1)

        jugg.deploy()
    }
}
