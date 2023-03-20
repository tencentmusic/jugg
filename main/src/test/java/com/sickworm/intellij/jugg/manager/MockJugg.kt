package com.sickworm.intellij.jugg.manager

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.AdbClient
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.android.AndroidModel
import com.android.tools.idea.gradle.dsl.api.android.CompileOptionsModel
import com.android.tools.idea.gradle.dsl.api.android.KotlinOptionsModel
import com.android.tools.idea.gradle.dsl.api.ext.ResolvedPropertyModel
import com.android.tools.idea.gradle.dsl.api.java.LanguageLevelPropertyModel
import com.android.tools.idea.log.LogWrapper
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.ValidationError
import com.intellij.execution.DefaultExecutionResult
import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockApplication
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.ApplicationInfoImpl
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.messages.MessagesService
import com.intellij.openapi.util.Computable
import com.intellij.pom.java.LanguageLevel
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.MockitoFixer
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.ide.toolWindow.JuggStateListener
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.FileChangesHandler
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import org.mockito.Mockito.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


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

    var isGradleBuilding = false
    private val ideDeployStateHelper = object : IIdeDeployStateHelper {
        override fun getIdeDeployState(): JuggDeployState {
            if (isGradleBuilding) {
                return JuggDeployState(JuggDeployState.State.GRADLE_BUILDING, "mock: gradle building")
            }
            return if (adbDeviceHelper.hasLaunchedApp(projectInfo.packageName)) {
                JuggDeployState.READY
            } else {
                JuggDeployState(JuggDeployState.State.READY_INCREMENTAL_COMPILE, "mock: app not launched")
            }
        }
    }

    companion object {
        init {
            MockitoFixer.tryFix()
        }
    }

    init {
        adbDeviceHelper.init()
        renewComponents()
        renewManager()
    }

    fun resetAllState() {
        pathManager.juggRootDir.deleteRecursively()
        renewComponents()
        renewManager()
    }

    /**
     * Init adb client.
     * Must call this method if you need real device deploy.
     */
    fun waitingLaunchAppAndCheck() {
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
     * Deploy changes to device connected by adb.
     */
    fun deploy() {
        // In this state, Jugg will wait app launched, so we need to update state asynchronously
        val shouldUpdateStateAsync = deployStateManager.deployState.state == JuggDeployState.State.READY_INCREMENTAL_COMPILE
        if (shouldUpdateStateAsync) {
            Thread {
                adbDeviceHelper.waitingForDeviceOfLaunchedApp(projectInfo.packageName)
                juggManager.onActionUpdate()
            }.start()
        }

        juggManager.deploy()
        waitingLaunchAppAndCheck()
        juggManager.onActionUpdate()
    }

    /**
     * Just simply mark changes as full compiled. Use this we don't need an android device to run tests.
     */
    fun dryFullCompile() {
        juggManager.initCompileAfterFullBuild()
        juggManager.onActionUpdate()
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
        application.registerService(MessagesService::class.java, mock(MessagesService::class.java))
        application.registerService(ApplicationInfo::class.java, ApplicationInfoImpl.getShadowInstance())

        project = JuggMockProject(projectDir)
        pathManager = JuggPathManager(project, projectDir, buildDir)
        JuggLogger.register(project, pathManager.logDir)

        juggStateListener = mock(JuggStateListener::class.java)

        deployTargetManager = object: IDeployTargetManager {
            override fun runFullBuildAndLaunch(): DefaultExecutionResult {
                Thread {
                    isGradleBuilding = true
                    juggManager.onActionUpdate()
                }.start()

                GradleBuildHelper.appAssembleDebug()
                juggDeployerHelper.runTask(JuggDeployData(projectInfo.apkInfos, emptyList(), emptyList(), emptyList(), emptyList()), true)
                deployTargetManager.restartApp()

                Thread {
                    isGradleBuilding = false
                    juggManager.onActionUpdate()
                }.start()

                return DefaultExecutionResult()
            }

            override fun setApks(apks: List<ApkInfo>) {
            }

            override fun getApks(): List<ApkInfo> {
                return projectInfo.apkInfos
            }

            override fun getDevice(): IDevice {
                return this@MockJugg.getDevice()
            }

            override fun restartApp(): Boolean {
                val apkProvider = object : ApkProvider {
                    override fun getApks(device: IDevice) = projectInfo.apkInfos
                    override fun validate() = mutableListOf<ValidationError>()
                }
                AdbCmdHelper(getDevice(), logger).startDefaultApp(projectInfo.packageName, projectInfo.apkInfos)
                return true
            }

        }

        val moduleManager = mock(ModuleManager::class.java)
        val modules = GradleSettingsDummyReader(assetsAndroidDir).readProjectDirs().map {
            getModel(it)
        }.toTypedArray()
        doReturn(modules).`when`(moduleManager).modules
        val projectJdkTable = mock(ProjectJdkTable::class.java)
        doReturn(arrayOf(MockAndroid30Sdk())).`when`(projectJdkTable).allJdks
        val projectBuildModel = mock(ProjectBuildModel::class.java)
        val gradleBuildModule = mock(GradleBuildModel::class.java)
        doReturn(getAndroidModel()).`when`(gradleBuildModule).android()
        doReturn(gradleBuildModule).`when`(projectBuildModel).getModuleBuildModel(any<Module>())
        compileContextManager = CompileContextManager(project, pathManager,
            moduleManager, projectJdkTable, projectBuildModel, logger)

        fileChangesHandler = FileChangesHandler(project, logger)
        fileChangesDetector = MockFileChangesDetector()

        juggDeployerHelper = JuggDeployerHelper(project, deployTargetManager, logger)
        juggDeployerHelper.installPathProvider = Computable<String> {
            val downloader = MockAndroidProfilerDownloader()
            val (costTime, isInPlace) = measureTimeMillisWithResult {
                downloader.makeSureComponentIsInPlace()
            }
            println("makeSureComponentIsInPlace cost ${costTime}ms")
            assertTrue(isInPlace)

            downloader.installerFilePath.absolutePath
        }

        deployHistoryManager = DeployHistoryManager(projectInfo.projectRoot, pathManager.historyDir, logger)
        deployFileManager = DeployFileManager(logger)
        deployStateManager = DeployStateManager(project, deployHistoryManager, ideDeployStateHelper)

        JuggLogger.listenProjectLog(project, logger)
    }

    private fun getAndroidModel(): AndroidModel {
        val compileSdkVersion = mock(ResolvedPropertyModel::class.java)
        `when`(compileSdkVersion.valueAsString()).thenReturn(androidBuildTools.name.substring(0, 2))

        val buildToolsVersion = mock(ResolvedPropertyModel::class.java)
        `when`(buildToolsVersion.valueAsString()).thenReturn(androidBuildTools.name)

        val compileOptionsModel = mock(CompileOptionsModel::class.java)
        val languageLevelPropertyModel = mock(LanguageLevelPropertyModel::class.java)
        `when`(languageLevelPropertyModel.toLanguageLevel()).thenReturn(LanguageLevel.JDK_1_8) // TODO read from build.gradle
        `when`(compileOptionsModel.sourceCompatibility()).thenReturn(languageLevelPropertyModel)
        `when`(compileOptionsModel.targetCompatibility()).thenReturn(languageLevelPropertyModel)

        val kotlinOptionsModel = mock(KotlinOptionsModel::class.java)
        val jvmTarget = mock(LanguageLevelPropertyModel::class.java)
        `when`(jvmTarget.toLanguageLevel()).thenReturn(LanguageLevel.JDK_1_8) // TODO read from build.gradle
        `when`(kotlinOptionsModel.jvmTarget()).thenReturn(jvmTarget)

        val androidModel = mock(AndroidModel::class.java)
        `when`(androidModel.sourceSets()).thenReturn(mutableListOf())
        `when`(androidModel.buildToolsVersion()).thenReturn(buildToolsVersion)
        `when`(androidModel.compileSdkVersion()).thenReturn(compileSdkVersion)
        `when`(androidModel.compileOptions()).thenReturn(compileOptionsModel)
        `when`(androidModel.kotlinOptions()).thenReturn(kotlinOptionsModel)

        return androidModel
    }

    private fun getModel(file: File): Module {
        val virtualFile = MockIoVirtualFile(file)
        val manager = MockModuleRootManager(virtualFile)
        val module = mock(Module::class.java)
        doReturn(manager).`when`(module).getComponent(ModuleRootManager::class.java)
        doReturn(virtualFile).`when`(module).moduleFile
        doReturn(virtualFile.name).`when`(module).name

        return module
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
            project,
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
        juggManager.deployStateListener = juggStateListener
        juggManager.init()
    }
}
