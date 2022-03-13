package com.sickworm.intellij.jugg.manager

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.internal.DeviceImpl
import com.android.tools.deployer.JuggDeployerHelper
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.run.ApkInfo
import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockApplication
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.ui.messages.MessagesService
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.sickworm.intellij.jugg.BuildDemoApkTest
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.MockitoFixer
import com.sickworm.intellij.jugg.deploy.DeployDataManager
import com.sickworm.intellij.jugg.deploy.DeployState
import com.sickworm.intellij.jugg.deploy.DeployTargetManager
import com.sickworm.intellij.jugg.ide.toolWindow.DeviceStatusListener
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.FileChangesManager
import com.sickworm.intellij.jugg.project.JuggLogger
import org.mockito.Mockito.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BasicJuggMock {

    lateinit var project: Project
    lateinit var projectDir: String
    lateinit var apkInfoList: List<ApkInfo>

    lateinit var juggManager: JuggManager
    lateinit var fileChangesManager: FileChangesManager
    lateinit var deviceStatusListener: DeviceStatusListener
    lateinit var deployTargetManager: DeployTargetManager
    lateinit var compileContextManager: CompileContextManager
    lateinit var fileChangeEventSender: FileChangeEventSender
    lateinit var juggDeployerHelper: JuggDeployerHelper
    lateinit var deployDataManager: DeployDataManager

    // TODO use adb command
    val device = DeviceImpl(null, "192.168.31.243:33105", IDevice.DeviceState.ONLINE)

    companion object {
        private var hasInitOnce: Boolean = false
    }

    fun initEnv() {
        if (!hasInitOnce) {
            hasInitOnce = true
            MockitoFixer.tryFix()
            AndroidDebugBridge.init(true)
        }
        BuildDemoApkTest().buildApkIfNeeded()
    }

    fun resetAllState() {
        clearBuild()
        renewComponents()
        renewManager()
        markAsReadyToDeploy()
    }

    /**
     * Just simply mark changes as deployed. Use this we don't need an android device to run tests.
     */
    fun dryDeploy() {
        val deployData = deployDataManager.getDeployData()
        deployDataManager.commit(deployData)
    }

    private fun renewComponents() {
        val application = MockApplication {}
        ApplicationManager.setApplication(application) {}
        application.registerService(PropertiesComponent::class.java, DummyPropertiesComponent())
        application.registerService(MessagesService::class.java, DummyMessagesService())

        project = JuggMockProject()
        projectDir = assetsAndroidDir.absolutePath
        apkInfoList = listOf(ApkInfo(assetsApkFile, androidApkPackage))

        deviceStatusListener = mock(DeviceStatusListener::class.java)

        deployTargetManager = mock(DeployTargetManager::class.java)
        `when`(deployTargetManager.getApks()).thenReturn(apkInfoList)

        val moduleManager = mock(ModuleManager::class.java)
        val modules = GradleSettingsDummyReader(assetsAndroidDir).readProjectDirs().map {
            MockModule(it)
        }.toTypedArray()
        doReturn(modules).`when`(moduleManager).modules
        val projectJdkTable = mock(ProjectJdkTable::class.java)
        doReturn(arrayOf(MockAndroid30Sdk())).`when`(projectJdkTable).allJdks
        val projectBuildModel = mock(ProjectBuildModel::class.java)
        doReturn(MockGradleBuildModel()).`when`(projectBuildModel).getModuleBuildModel(any<Module>())
        compileContextManager = CompileContextManager(project, projectDir,
            moduleManager, projectJdkTable, projectBuildModel)

        val virtualFileManager = mock(VirtualFileManager::class.java)
        `when`(virtualFileManager.addAsyncFileListener(any(), any())).then {
            val asyncFileListener = it.arguments[0] as AsyncFileListener
            fileChangeEventSender = FileChangeEventSender(asyncFileListener)
            return@then Unit
        }
        fileChangesManager = FileChangesManager(project, projectDir, virtualFileManager)

        juggDeployerHelper = spy(JuggDeployerHelper(MockExecutor()))
        doReturn(device).`when`(juggDeployerHelper).getIDevice(project)
        juggDeployerHelper.installPathProvider = Computable {
            return@Computable "./src/test/assets/libs/installer"
        }

        deployDataManager = DeployDataManager(compileContextManager, logger)

        JuggLogger.listenProjectLog(project, StdLogger("test"))
    }

    private fun renewManager() {
        juggManager = JuggManager(
            project, projectDir, deviceStatusListener,
            fileChangesManager = fileChangesManager,
            deployTargetManager = deployTargetManager,
            compileThread = SyncExecutorService(),
            deployThread = SyncExecutorService(),
            compileContextManager = compileContextManager,
            juggDeployerHelper = juggDeployerHelper,
            deployDataManager = deployDataManager
        )
        juggManager.init()
    }

    private fun markAsReadyToDeploy() {
        val state = DeployState(isReadyInstall = true, isReadyApply = true, disableMessage = null)
        juggManager.updateStatus(state)

        assertEquals(1, deployTargetManager.getApks().size)
        assertEquals(1, compileContextManager.compileContext.parsedApks.size)
        assertTrue(::fileChangeEventSender.isInitialized)
        verify(deviceStatusListener, times(1)).updateStatus(state)
    }
}
