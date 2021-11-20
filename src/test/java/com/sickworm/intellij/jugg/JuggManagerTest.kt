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

    private val testSourceDirectory = "app/src/main/java/$androidApkPackagePath"
    private fun changeFileAndNotify(vararg fileNamePairs: Pair<String, String>, directory: String = testSourceDirectory) {
        val pairs = fileNamePairs.map { (sourceFileName, destFileName) ->
            val sourceFile = File(assetsAndroidModifySourceDir, "$directory/$sourceFileName")
            val destFile = File(assetsAndroidDir, "$directory/$destFileName")
            sourceFile to destFile
        }
        fileChangeEventSender.copyAndNotifyFileChanges(pairs)
    }

    private fun revertFile(originFile: String, isAdd: Boolean, directory: String = testSourceDirectory) {
        val sourceFile = File(assetsAndroidModifySourceDir, "$directory/$originFile")
        val destFile = File(assetsAndroidDir, "$directory/$originFile")
        if (isAdd) {
            destFile.delete()
            return
        }
        sourceFile.copyTo(destFile, overwrite = true)
    }

    private fun checkCompileResult(
        vararg fileNames: String,
        newClassesSize: Int = 0, modifiedClassesSize: Int = 0, overlaysSize: Int = 0,
    ) {
        fileNames.forEach { fileName ->
            val className = File(fileName).nameWithoutExtension + ".class"
            val classPathFile = File(compileContextManager.compileContext.classPathDir, "$androidApkPackagePath/$className")
            assertTrue(classPathFile.exists())
            assertTrue(classPathFile.length() > 0)

            val dexName = File(fileName).nameWithoutExtension + ".dex"
            val dexFile = File(compileContextManager.stagingDir, "classes/$androidApkPackagePath/$dexName")
            assertTrue(dexFile.exists())
            assertTrue(dexFile.length() > 0)
        }

        assertEquals(0, deployDataManager.getUncompiledFiles().size)
        val deployData = deployDataManager.getDeployData()
        assertEquals(1, deployData.apks.size)
        assertEquals(newClassesSize, deployData.newClasses.size)
        assertEquals(modifiedClassesSize, deployData.modifiedClasses.size)
        assertEquals(overlaysSize, deployData.overlays.size)
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
        assertEquals(748, parsedApk.overlayFiles.size)
    }

    @Test
    fun testCompileJavaFile() {
        changeFileAndNotify("ABC.java" to "ABC.java")
        checkCompileResult("ABC.java", modifiedClassesSize = 1)
    }

    @Test
    fun testCompileActivity() {
        changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", modifiedClassesSize = 1)
    }

    /*******************************************************************
     * Source file test case:
     * operation:   add / remove / update value / change signature
     * count:       single / multiple
     * language:    java / kotlin
     * type:        static / non-static
     * object:      variable / method / class / subclass
     *
     * other case:
     * * Kotlin const value update
     * * Kotlin multiple class in one file
     *******************************************************************/

    @Test
    fun testAddSingleJavaClass() {
        changeFileAndNotify("TestNewFile.java" to "TestNewFile.java")
        checkCompileResult("TestNewFile.java", newClassesSize = 1)

        // revert
        revertFile("TestNewFile.java", isAdd = true)
    }

    @Test
    fun testAddMultipleJavaClasses() {
        changeFileAndNotify(
            "TestNewFile.java" to "TestNewFile.java",
            "TestNewFile2.java" to "TestNewFile2.java")
        checkCompileResult("TestNewFile.java", "TestNewFile2.java", newClassesSize = 2)

        // revert
        revertFile("TestNewFile.java", isAdd = true)
        revertFile("TestNewFile2.java", isAdd = true)
    }

    // no remove class

    // no update value class

    @Test
    fun testChangeSignatureJavaClass() {

    }

    companion object {

        private fun tryFixMockito() {
            if (isWindows) {
                return
            }

            // actually is fix ByteBuddyAgent used by Mockito

            // 1. ByteBuddyAgent will read System.setProperty("java.home") and invoke,
            // when the property has white space，it will add " between the path,
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
