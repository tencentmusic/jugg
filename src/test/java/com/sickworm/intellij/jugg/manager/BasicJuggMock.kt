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
import org.junit.Before
import org.junit.BeforeClass
import org.mockito.Mockito.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

open class BasicJuggMock {

    protected lateinit var project: Project
    protected lateinit var projectDir: String
    protected lateinit var apkInfos: List<ApkInfo>

    protected lateinit var juggManager: JuggManager
    protected lateinit var fileChangesManager: FileChangesManager
    protected lateinit var deviceStatusListener: DeviceStatusListener
    protected lateinit var deployTargetManager: DeployTargetManager
    protected lateinit var compileContextManager: CompileContextManager
    protected lateinit var fileChangeEventSender: FileChangeEventSender
    protected lateinit var juggDeployerHelper: JuggDeployerHelper
    protected lateinit var deployDataManager: DeployDataManager

    val device = DeviceImpl(null, "192.168.31.82:34267", IDevice.DeviceState.ONLINE)

    private fun renewComponents() {
        val application = MockApplication {}
        ApplicationManager.setApplication(application) {}
        application.registerService(PropertiesComponent::class.java, DummyPropertiesComponent())
        application.registerService(MessagesService::class.java, DummyMessagesService())

        project = JuggMockProject()
        projectDir = assetsAndroidDir.absolutePath
        apkInfos = listOf(ApkInfo(assetsApkFile, androidApkPackage))

        deviceStatusListener = mock(DeviceStatusListener::class.java)

        deployTargetManager = mock(DeployTargetManager::class.java)
        `when`(deployTargetManager.getApks()).thenReturn(apkInfos)

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

    private fun initEnv() {
        val state = DeployState(isReadyInstall = true, isReadyApply = true, disableMessage = null)
        juggManager.updateStatus(state)

        assertEquals(1, deployTargetManager.getApks().size)
        assertEquals(1, compileContextManager.compileContext.parsedApks.size)
        assertTrue(::fileChangeEventSender.isInitialized)
        verify(deviceStatusListener, times(1)).updateStatus(state)
    }

    private val testSourceDirectory = "app/src/main/java/${androidApkPackage.replace('.', '/')}"

    protected fun changeFileAndNotify(vararg fileNamePairs: Pair<String, String>, directory: String = testSourceDirectory) {
        val pairs = fileNamePairs.map { (sourceFileName, destFileName) ->
            val sourceFile = File(assetsAndroidModifySourceDir, "$directory/$sourceFileName")
            val destFile = File(assetsAndroidDir, "$directory/$destFileName")
            sourceFile to destFile
        }
        val revertFileMark = pairs.map { (_, destFile) ->
            destFile to destFile.exists()
        }
        fileChangeEventSender.copyAndNotifyFileChanges(pairs)

        // revert
        revertFileMark.forEach { (destFile, isExist) ->
            revertFile(destFile.name, isAdd = !isExist, directory = directory)
        }
    }

    private fun revertFile(originFile: String, isAdd: Boolean = false, directory: String) {
        val sourceFile = File(assetsAndroidModifySourceDir, "$directory/$originFile")
        val destFile = File(assetsAndroidDir, "$directory/$originFile")
        if (isAdd) {
            destFile.delete()
            return
        }
        sourceFile.copyTo(destFile, overwrite = true)
    }

    protected fun checkCompileResult(
        vararg fileNames: String,
        filePackageName: String = androidApkPackage,
        newClassesSize: Int = 0,
        hotFixModifiedClassesSize: Int = 0,
        hotReloadModifiedClassesSize: Int = 0,
        overlaysSize: Int = 0,
    ) {
        fileNames.forEach { fileName ->
            val relativePath = filePackageName.replace('.', '/')
            val className = File(fileName).nameWithoutExtension + ".class"
            val classPathFile = File(compileContextManager.compileContext.classPathDir, "$relativePath/$className")
            assertTrue(classPathFile.exists(), "$classPathFile not exists")
            assertTrue(classPathFile.length() > 0)

            val dexName = File(fileName).nameWithoutExtension + ".dex"
            val dexFile = File(compileContextManager.stagingDir, "classes/$relativePath/$dexName")
            assertTrue(dexFile.exists())
            assertTrue(dexFile.length() > 0)
        }

        assertEquals(0, deployDataManager.getUncompiledFiles().size)
        val deployData = deployDataManager.getDeployData()
        assertEquals(1, deployData.apks.size)
        assertEquals(newClassesSize, deployData.newClasses.size)
        assertEquals(hotFixModifiedClassesSize, deployData.hotFixModifiedClasses.size)
        assertEquals(hotReloadModifiedClassesSize, deployData.hotReloadModifiedClasses.size)
        assertEquals(overlaysSize, deployData.overlays.size)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun initApk() {
            BuildDemoApkTest().buildApkIfNeeded()
        }

        @BeforeClass
        @JvmStatic
        fun initAdb() {
            AndroidDebugBridge.init(true)
        }
    }

    @Before
    fun init() {
        MockitoFixer.tryFix()
        clearBuild()
        renewComponents()
        renewManager()
        initEnv()
    }
}
