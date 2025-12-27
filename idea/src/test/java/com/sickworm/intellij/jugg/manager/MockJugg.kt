package com.sickworm.intellij.jugg.manager

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.AdbClient
import com.android.tools.idea.log.LogWrapper
import com.intellij.openapi.module.Module
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.MockitoFixer
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.gradle.LocalGradleCompileClientTest
import com.sickworm.intellij.jugg.gradle.LocalGradleCompileClientTest.Companion
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.dependency.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import org.mockito.Mockito
import org.mockito.Mockito.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


class MockJugg(val projectDir: File = projectInfo.projectRoot) {

    lateinit var project: JuggMockProject
    lateinit var juggManager: JuggManager
    lateinit var pathManager: JuggPathManager
    lateinit var fileChangesHandler: FileChangesHandler
    lateinit var fileChangesDetector: MockFileChangesDetector
    lateinit var deployTargetManager: IDeployTargetManager
    lateinit var compileContextManager: CompileContextManager
    lateinit var juggDeployerHelper: JuggDeployerHelper
    lateinit var deployHistoryManager: IDeployHistoryManager
    lateinit var deployFileManager: DeployFileManager
    lateinit var deployStateManager: DeployStateManager
    lateinit var dependencyChangeManager: IDependencyChangeManager
    lateinit var taskRunnerManager: TaskRunnerManager
    lateinit var gradleProjectInfoLocalFetchManager: GradleProjectInfoLocalFetchManager
    lateinit var customCompilerManager: CustomCompilerManager
    val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val adbDeviceHelper = AdbDeviceHelper()

    private val ideDeployStateHelper = object : IIdeDeployStateHelper {
        override fun getIdeDeployState(device: IDevice?, packageName: String?): IdeDeployState {
            return if (adbDeviceHelper.hasLaunchedApp(packageName!!)) {
                IdeDeployState.ok
            } else {
                IdeDeployState.appNotRunningOrNotDebuggable
            }
        }
    }

    companion object {
        init {
            MockitoFixer.tryFix()
        }

        fun compileFile(projectDir: String, file: String): List<CompileOutput> {
            val jugg = MockJugg(File(projectDir))
            jugg.loadFromHistory()
            jugg.deployStateManager.updateDeployState()
            if (!jugg.deployStateManager.deployState.isReadyIncCompile) {
                throw IllegalStateException("no history, exit")
            }
            jugg.notifyFileChanges(listOf(File(file)))
            jugg.compileChangedFiles()

            checkCompileStatus(jugg)
            return jugg.deployFileManager.getStagingFiles()
        }

        private fun checkCompileStatus(jugg: MockJugg) {
            val uncompiledFiles = jugg.deployFileManager.getUncompiledFiles()
            assertEquals(0, uncompiledFiles.size, "not all files are compiled")

            val stagingFiles = jugg.deployFileManager.getStagingFiles()
            assertTrue(stagingFiles.isNotEmpty(), "no staging files")
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

    fun loadFromHistory() {
        renewComponents(isMockCompileContextManager = false)
        renewManager()
        juggManager.recoverDeployContext()
        deployFileManager.reset()

        if (!deployHistoryManager.hasBeenFullCompiled) {
            throw RuntimeException("loadFromHistory failed")
        }
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
                juggManager.updateDeployState()
            }.start()
        }

//        juggManager.deploy() TODO fixme
        waitingLaunchAppAndCheck()
        juggManager.updateDeployState()
    }

    /**
     * Just simply mark changes as full compiled. Use this we don't need an android device to run tests.
     */
    fun dryFullCompile() {
        renewComponents(isMockCompileContextManager = false)
        renewManager()
        juggManager.initIncrementalCompileAfterFullBuild(System.currentTimeMillis())
        juggManager.updateDeployState()
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
        deployFileManager.reset(null)
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

    private fun renewComponents(isMockCompileContextManager: Boolean = true) {
        project = JuggMockProject(projectDir)
        val moduleManager = mock(ModuleManager::class.java)
        doReturn(arrayOf<Module>()).`when`(moduleManager).modules
        project.registerService(ModuleManager::class.java, moduleManager)

        pathManager = JuggPathManager(projectDir)
        JuggLogger.register(project, pathManager.logDir)

        deployTargetManager = object: IDeployTargetManager {

            override fun setApks(apks: List<ApkInfo>) {
            }

            override fun getApks(): List<ApkInfo> {
                return projectInfo.apkInfos
            }

            override fun getSelectedDevices(): List<IDevice> {
                return adbDeviceHelper.getDeviceList()
            }

            override fun getConnectedDevices(): List<IDevice> {
                return adbDeviceHelper.getDeviceList()
            }

            override fun startApp(device: IDevice): Boolean {
                AdbCmdHelper(device, logger).startDefaultApp(projectInfo.packageName, projectInfo.apkInfos)
                return true
            }

            override fun restartApp(device: IDevice): Boolean {
                AdbCmdHelper(device, logger).startDefaultApp(projectInfo.packageName, projectInfo.apkInfos)
                return true
            }

            override fun stopApp(device: IDevice): Boolean {
                AdbCmdHelper(device, logger).stopApp(projectInfo.packageName)
                return true
            }

            override fun isAppForeground(device: IDevice): Boolean {
                return AdbCmdHelper(device, logger).isAppForeground(projectInfo.packageName)
            }

            override fun getPackageName(): String {
                return projectInfo.packageName
            }
        }

        fileChangesHandler = FileChangesHandler(pathManager.projectDir, pathManager.juggRootDir, logger)
        fileChangesDetector = MockFileChangesDetector()

        deployHistoryManager = DeployHistoryManager(pathManager, fileChangesHandler, logger)
        deployFileManager = DeployFileManager(projectDir, logger, pathManager.tmpDir, pathManager.databaseDir, coroutineScope)
        deployStateManager = DeployStateManager(project, deployTargetManager, deployHistoryManager, ideDeployStateHelper)
        dependencyChangeManager = IDependencyChangeManager.create(logger)

        val juggServer = JuggServer(project, JuggPathManager(File(project.basePath)), CoroutineScope(Dispatchers.IO))
        customCompilerManager = CustomCompilerManager(pathManager.projectDir, pathManager.customCompilerDir, juggServer, logger)

        if (isMockCompileContextManager) {
            compileContextManager = mock(CompileContextManager::class.java)
            doReturn(context.copy(tempCompileDir = File(pathManager.compileRootDir, "compiled"))).`when`(compileContextManager).compileContext
        } else {
            val moduleManager = mock(ModuleManager::class.java)
            doReturn(emptyArray<com.intellij.openapi.module.Module>()).`when`(moduleManager).modules
            compileContextManager = CompileContextManager(project, pathManager, deployFileManager, deployHistoryManager,
                moduleManager = moduleManager, customCompilerManager = customCompilerManager)
        }

        taskRunnerManager = TaskRunnerManager(project, logger, deployStateManager, juggServer, coroutineScope)
        juggDeployerHelper = JuggDeployerHelper(project, deployTargetManager, deployFileManager, deployHistoryManager, deployStateManager, dependencyChangeManager, compileContextManager, juggServer, taskRunnerManager, logger) {
            val downloader = MockAndroidProfilerDownloader()
            val (costTime, isInPlace) = measureTimeMillisWithResult {
                downloader.makeSureComponentIsInPlace()
            }
            println("makeSureComponentIsInPlace cost ${costTime}ms")
            assertTrue(isInPlace)

            downloader.installerFilePath.absolutePath
        }

        gradleProjectInfoLocalFetchManager = GradleProjectInfoLocalFetchManager(project, pathManager, compileContextManager, taskRunnerManager, dependencyChangeManager, logger)

        JuggLogger.listenProjectLog(project, logger)
    }

    private fun renewManager() {
        juggManager = JuggManager(
            project,
            coroutineScope = coroutineScope,
            pathManager = pathManager,
            fileChangesHandler = fileChangesHandler,
            fileChangesDetector = fileChangesDetector,
            deployTargetManager = deployTargetManager,
            compileContextManager = compileContextManager,
            deployFileManager = deployFileManager,
            juggDeployerHelper = juggDeployerHelper,
            deployStateManager = deployStateManager,
            deployHistoryManager = deployHistoryManager,
            dependencyChangeManager = dependencyChangeManager,
            taskRunnerManager = taskRunnerManager,
            gradleProjectInfoLocalFetchManager = gradleProjectInfoLocalFetchManager,
        )

//        juggManager.init() // init will call initProjectInfo(true) and module cache is overridden by MockJugg
        AsDeployerCompat.init(JuggLogger.getInstance(project, "AsDeployerCompat"))
    }
}
