package com.sickworm.intellij.jugg

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.sickworm.intellij.jugg.deploy.DeployDataManager
import com.sickworm.intellij.jugg.deploy.DeployState
import com.sickworm.intellij.jugg.deploy.DeployTargetManager
import com.sickworm.intellij.jugg.ide.toolWindow.DeviceStatusListener
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.FileChangesManager
import com.sickworm.intellij.jugg.project.JuggLogger
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JuggManagerTest {

    private lateinit var project: Project
    private lateinit var projectDir: String
    private lateinit var apkInfos: List<ApkInfo>

    private lateinit var juggManager: JuggManager
    private lateinit var fileChangesManager: FileChangesManager
    private lateinit var deviceStatusListener: DeviceStatusListener
    private lateinit var deployTargetManager: DeployTargetManager
    private lateinit var compileContextManager: CompileContextManager
    private lateinit var fileChangeEventSender: FileChangeEventSender
    private lateinit var deployDataManager: DeployDataManager

    private fun renewComponents() {
        project = JuggMockProject()
        projectDir = assetsAndroidDir.absolutePath
        apkInfos = listOf(ApkInfo(assetsApkFile, androidApkPackage))

        deviceStatusListener = mock(DeviceStatusListener::class.java)

        deployTargetManager = mock(DeployTargetManager::class.java)
        `when`(deployTargetManager.getApks()).thenReturn(apkInfos)

        val moduleManager = mock(ModuleManager::class.java)
        doReturn(arrayOf(MockModule(File(assetsAndroidDir, "app")))).`when`(moduleManager).modules
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

    private fun onFileChanges(relativePath: String) {
        val sourceFile = File(assetsAndroidModifySourceDir, relativePath)
        val destFile = File(assetsAndroidDir, relativePath)
        fileChangeEventSender.copyAndNotifyFileChanges(sourceFile, destFile)
    }

    @Before
    fun init() {
        tryFixMockito()
        clearBuild()
        renewComponents()
        renewManager()
        initEnv()
    }

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
        assertEquals(2394, parsedApk.classFiles.size)
        assertEquals(748, parsedApk.overlayFiles.size)
    }

    @Test
    fun testCompileJavaFile() {
        onFileChanges("app/src/main/java/com/example/myapplication/ABC.java")

        val classPathFile = File(compileContextManager.compileContext.classPathDir, "com/example/myapplication/ABC.class")
        assertTrue(classPathFile.exists())
        assertEquals(406, classPathFile.length())

        val dexFile = File(compileContextManager.stagingDir, "classes/com/example/myapplication/ABC.dex")
        assertTrue(dexFile.exists())
        assertEquals(716, dexFile.length())

        assertEquals(0, deployDataManager.getUncompiledFiles().size)
        val deployData = deployDataManager.getDeployData()
        assertEquals(1, deployData.apks.size)
        assertEquals(0, deployData.newClasses.size)
        assertEquals(1, deployData.modifiedClasses.size)
        assertEquals(0, deployData.overlays.size)
    }

    @Test
    fun testCompileActivity() {
        onFileChanges("app/src/main/java/com/example/myapplication/MainActivity2.java")

        val classPathFile = File(compileContextManager.compileContext.classPathDir, "com/example/myapplication/MainActivity2.class")
        assertTrue(classPathFile.exists())
        assertEquals(2539, classPathFile.length())

        val dexFile = File(compileContextManager.stagingDir, "classes/com/example/myapplication/MainActivity2.dex")
        assertTrue(dexFile.exists())
        assertEquals(2716, dexFile.length())

        assertEquals(0, deployDataManager.getUncompiledFiles().size)
        val deployData = deployDataManager.getDeployData()
        assertEquals(1, deployData.apks.size)
        assertEquals(0, deployData.newClasses.size)
        assertEquals(1, deployData.modifiedClasses.size)
        assertEquals(0, deployData.overlays.size)
    }


    companion object {

        private fun tryFixMockito() {
            // actually is fix ByteBuddyAgent used by Mockito

            // 1. ByteBuddyAgent will read System.setProperty("java.home") and invoke,
            // when the property has white space，it will add " between the white space,
            // which will cause invoke failed

            // 2. JDK 1.8 will cause invoke failed"Could not self-attach to current VM using external process",
            // need to use JDK 11
            val propertyJavaHome = System.getProperty("java.home")
            val envJavaHome = System.getenv("JAVA_HOME")
            println("propertyJavaHome: $propertyJavaHome, envJavaHome: $envJavaHome")

            if (propertyJavaHome.contains(" ")) {
                // manual fix by replace with envJavaHome
                if (envJavaHome == null || envJavaHome.contains(" ")) {
                    throw IllegalStateException("please specific \$JAVA_HOME without white space, or Mockito won't work.")
                }
                System.setProperty("java.home", envJavaHome)
            }

            if (!propertyJavaHome.contains("11")) {
                // manual fix by replace with envJavaHome
                if (envJavaHome == null || !envJavaHome.contains("11")) {
                    throw IllegalStateException("please specific \$JAVA_HOME with JDK 11, or Mockito won't work.")
                }
                System.setProperty("java.home", envJavaHome)
            }
        }
    }
}
