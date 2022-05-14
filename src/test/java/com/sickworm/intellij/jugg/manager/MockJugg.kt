package com.sickworm.intellij.jugg.manager

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.AdbClient
import com.android.tools.deployer.JuggDeployerHelper
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.log.LogWrapper
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.ValidationError
import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockApplication
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.ui.messages.MessagesService
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.MockitoFixer
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.ide.toolWindow.JuggStateListener
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.*
import org.mockito.Mockito.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull


class MockJugg {

    val projectDir: File = projectInfo.projectRoot

    lateinit var project: Project
    lateinit var juggManager: JuggManager
    lateinit var pathManager: JuggPathManager
    lateinit var fileChangesHandler: FileChangesHandler
    lateinit var fileChangesDetector: MockFileChangesDetector
    lateinit var juggStateListener: JuggStateListener
    lateinit var deployTargetManager: IDeployTargetManager
    lateinit var compileContextManager: CompileContextManager
    lateinit var juggDeployerHelper: JuggDeployerHelper
    lateinit var deployHistoryManager: IDeployHistoryManager
    lateinit var deployFileManager: DeployFileManager
    lateinit var deployStateManager: DeployStateManager

    private val adbDeviceHelper = AdbDeviceHelper()

    companion object {
        init {
            MockitoFixer.tryFix()
        }
    }

    init {
        renewComponents()
        renewManager()
        adbDeviceHelper.init()
    }

    fun resetAllState() {
        pathManager.juggRootDir.deleteRecursively()
        renewComponents()
        renewManager()
        deployAndCheckState()
    }

    /**
     * Init adb client.
     * Must call this method if you need real device deploy.
     */
    fun checkDeployStateAndRegisterAdb() {
        val device = adbDeviceHelper.waitingForDeviceOfLaunchedApp(androidApkPackage)
        assertNotNull(device, "can not find $androidApkPackage on any device")

        val clients = device.clients
        assertNotEquals(0, clients.size)

        val logger = LogWrapper(logger)
        val adb = AdbClient(device, logger)

        val pids = adb.getPids(androidApkPackage)
        assertEquals(1, pids.size)

        val arch = adb.getArch(pids)
        assertEquals(Deploy.Arch.ARCH_64_BIT, arch)
    }

    /**
     * Run gradle build in [assetsAndroidDir] and install apk.
     */
    fun installAndReStart() {
        val data = deployFileManager.getDeployData()
        juggDeployerHelper.runTask(data, true)

        deployTargetManager.restartApp()
    }

    /**
     * Deploy changes to device connected by adb.
     */
    fun deploy() {
        juggManager.deploy()
    }

    /**
     * Just simply mark changes as deployed. Use this we don't need an android device to run tests.
     */
    fun dryDeploy() {
        val deployData = deployFileManager.getDeployData()
        deployFileManager.commit(deployData)
    }

    /**
     * reset deploy state
     */
    fun resetDeploy() {
        deployFileManager.reset()
    }

    /**
     * Notify that some files have changed and Jugg will compile it.
     */
    fun notifyFileChanges(file: List<File>) {
        fileChangesDetector.notifyFileChanges(file)
    }

    fun compileChangedFiles() {
        juggManager.compileChanges()
    }

    private fun renewComponents() {
        val application = MockApplication {}
        ApplicationManager.setApplication(application) {}
        application.registerService(PropertiesComponent::class.java, DummyPropertiesComponent())
        application.registerService(MessagesService::class.java, DummyMessagesService())

        project = JuggMockProject(projectDir)

        juggStateListener = mock(JuggStateListener::class.java)

        deployTargetManager = object: IDeployTargetManager {
            override fun runFullBuildAndLaunch() {
                GradleBuildHelper.appAssembleDebug()
            }

            override fun getApks(): List<ApkInfo> {
                return projectInfo.apkInfos
            }

            override fun getDevice(): IDevice {
                return this@MockJugg.getDevice()
            }

            override fun restartApp() {
                val apkProvider = object : ApkProvider {
                    override fun getApks(device: IDevice) = projectInfo.apkInfos
                    override fun validate() = mutableListOf<ValidationError>()
                }
                AppStarter().startDefaultApp(projectInfo.packageName, apkProvider, getDevice())
            }
        }

        pathManager = JuggPathManager(projectDir, buildDir)

        val moduleManager = mock(ModuleManager::class.java)
        val modules = GradleSettingsDummyReader(assetsAndroidDir).readProjectDirs().map {
            MockModule(it)
        }.toTypedArray()
        doReturn(modules).`when`(moduleManager).modules
        val projectJdkTable = mock(ProjectJdkTable::class.java)
        doReturn(arrayOf(MockAndroid30Sdk())).`when`(projectJdkTable).allJdks
        val projectBuildModel = mock(ProjectBuildModel::class.java)
        doReturn(MockGradleBuildModel()).`when`(projectBuildModel).getModuleBuildModel(any<Module>())
        compileContextManager = CompileContextManager(project, pathManager,
            moduleManager, projectJdkTable, projectBuildModel)

        fileChangesHandler = FileChangesHandler(project)
        fileChangesDetector = MockFileChangesDetector()

        juggDeployerHelper = JuggDeployerHelper(project, deployTargetManager, MockExecutor())
        juggDeployerHelper.installPathProvider = Computable {
            return@Computable "./src/test/assets/libs/installer"
        }

        deployHistoryManager = DeployHistoryManager(projectInfo.projectRoot, pathManager.historyDir, logger)
        deployFileManager = DeployFileManager(logger)

        val ideDeployStateHelper = mock(IdeDeployStateHelper::class.java)
        val state = JuggDeployState.READY
        `when`(ideDeployStateHelper.getIdeDeployState()).thenReturn(state)
        deployStateManager = DeployStateManager(project, deployHistoryManager, ideDeployStateHelper)

        JuggLogger.listenProjectLog(project, logger)
    }

    private fun getDevice(): IDevice {
        val deviceList = adbDeviceHelper.getDeviceList()
            .filter { it.state == IDevice.DeviceState.ONLINE }
        if (deviceList.isEmpty()) {
            throw JuggException.deviceNotFound()
        }
        if (deviceList.size > 1) {
            throw JuggException.multipleDeviceFound()
        }
        return deviceList.first()
    }

    private fun renewManager() {
        juggManager = JuggManager(
            project, projectInfo.projectRoot, juggStateListener,
            pathManager = pathManager,
            fileChangesHandler = fileChangesHandler,
            fileChangesDetector = fileChangesDetector,
            deployTargetManager = deployTargetManager,
            compileThread = SyncExecutorService(),
            compileContextManager = compileContextManager,
            deployFileManager = deployFileManager,
            juggDeployerHelper = juggDeployerHelper,
            deployStateManager = deployStateManager,
            deployHistoryManager = deployHistoryManager,
        )
        juggManager.init()
    }

    private fun deployAndCheckState() {
        juggManager.deploy()

        assertEquals(JuggDeployState.READY, deployStateManager.deployState)
        assertEquals(1, deployTargetManager.getApks().size)
        assertEquals(1, compileContextManager.compileContext.apkInfos.size)
        verify(juggStateListener, times(1)).onDeployStateUpdate(JuggDeployState.READY)
    }
}
