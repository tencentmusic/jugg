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
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.sickworm.intellij.jugg.BuildDemoApkTest
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.MockitoFixer
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.ide.toolWindow.DeviceStatusListener
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.FileChangesManager
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggLogger
import org.mockito.Mockito.*
import java.io.File
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


class MockJugg {

    lateinit var project: Project
    lateinit var projectDir: String

    lateinit var juggManager: JuggManager
    lateinit var fileChangesManager: FileChangesManager
    lateinit var deviceStatusListener: DeviceStatusListener
    lateinit var deployTargetManager: DeployTargetManager
    lateinit var compileContextManager: CompileContextManager
    lateinit var fileChangeEventSender: FileChangeEventSender
    lateinit var juggDeployerHelper: JuggDeployerHelper
    lateinit var deployDataManager: DeployDataManager

    private val adbDeviceHelper = AdbDeviceHelper()

    companion object {
        private var hasInitOnce: Boolean = false
    }

    fun initEnv() {
        if (!hasInitOnce) {
            hasInitOnce = true
            MockitoFixer.tryFix()
        }

        adbDeviceHelper.init()

        BuildDemoApkTest().buildApkIfNeeded()
    }

    fun resetAllState() {
        clearBuild()
        renewComponents()
        renewManager()
        markAsReadyToDeploy()
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
        val data = deployDataManager.getDeployData()
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
        val deployData = deployDataManager.getDeployData()
        deployDataManager.commit(deployData)
    }

    /**
     * Notify that some files have changed and Jugg will compile it.
     */
    fun notifyFileChanges(file: List<File>) {
        fileChangeEventSender.notifyFileChanges(file)
    }

    fun compileChangedFiles() {
        juggManager.compileChanges()
    }

    private fun renewComponents() {
        val application = MockApplication {}
        ApplicationManager.setApplication(application) {}
        application.registerService(PropertiesComponent::class.java, DummyPropertiesComponent())
        application.registerService(MessagesService::class.java, DummyMessagesService())

        project = JuggMockProject()
        projectDir = assetsAndroidDir.absolutePath

        deviceStatusListener = mock(DeviceStatusListener::class.java)

        val deviceGetter = object : IDeviceGetter {
            override fun getDevice(): IDevice {
                return this@MockJugg.getDevice()
            }
        }
        val apks = mutableListOf(projectInfo.apkInfo)
        val apkProvider = object : ApkProvider {
            override fun getApks(device: IDevice): MutableCollection<ApkInfo> {
                return apks
            }

            override fun validate(): MutableList<ValidationError> {
                return mutableListOf()
            }
        }
        val realDeployTargetManager = DeployTargetManager(project, deviceGetter)
        deployTargetManager = spy(realDeployTargetManager)
        doReturn(apkProvider).`when`(deployTargetManager).getApkProvider()
        doReturn(apks).`when`(deployTargetManager).getApks()

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

        juggDeployerHelper = JuggDeployerHelper(project, deployTargetManager, MockExecutor())
        juggDeployerHelper.installPathProvider = Computable {
            return@Computable "./src/test/assets/libs/installer"
        }

        deployDataManager = DeployDataManager(compileContextManager, logger)

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
            project, projectDir, deviceStatusListener,
            fileChangesManager = fileChangesManager,
            deployTargetManager = deployTargetManager,
            compileThread = SyncExecutorService(),
            deployThread = SyncExecutorService(),
            compileContextManager = compileContextManager,
            deployDataManager = deployDataManager,
            juggDeployerHelper = juggDeployerHelper,
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
