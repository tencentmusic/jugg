package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.mock.RequiresDeviceRule
import org.junit.Before
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
