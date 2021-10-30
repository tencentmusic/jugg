package com.sickworm.intellij.jugg

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.deploy.DeployState
import com.sickworm.intellij.jugg.deploy.DeployTargetManager
import com.sickworm.intellij.jugg.ide.toolWindow.DeviceStatusListener
import com.sickworm.intellij.jugg.project.JuggLogger
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.BaseCompileContext
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.FileChangesManager
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
    private lateinit var compileContext: ICompileContext

    private lateinit var juggManager: JuggManager
    private lateinit var fileChangesManager: FileChangesManager
    private lateinit var deviceStatusListener: DeviceStatusListener
    private lateinit var deployTargetManager: DeployTargetManager
    private lateinit var compileContextManager: CompileContextManager
    private lateinit var fileChangeEventSender: FileChangeEventSender

    private fun renewComponents() {
        project = JuggMockProject()
        projectDir = assetsAndroidDir.absolutePath
        apkInfos = listOf(ApkInfo(assetsApkFile, androidApkPackage))
        compileContext = BaseCompileContext(
            logger = JuggLogger.getInstance(project, "#Jugg-Compiler"),
            androidHome = androidHome,
            tempCompileDir = tempCompileDir,
            classPathDir = classPathDir,
            modules = mapOf(
                "app" to ModuleInfo(
                    name = "app",
                    sourceDirs = listOf(File(assetsAndroidDir, "app/src/main/java")),
                    assetsDirs = listOf(File(assetsAndroidDir, "app/src/main/assets")),
                    resourceDirs = listOf(File(assetsAndroidDir, "app/src/main/res")),
                    compileVersion = "30",
                    buildToolsVersion = "30.0.3"
                )
            )
        )

        deviceStatusListener = mock(DeviceStatusListener::class.java)

        deployTargetManager = mock(DeployTargetManager::class.java)
        `when`(deployTargetManager.getApks()).thenReturn(apkInfos)

        compileContextManager = spy(CompileContextManager(project, projectDir))
        doNothing().`when`(compileContextManager).init()
        doReturn(compileContext).`when`(compileContextManager).compileContext

        val virtualFileManager = mock(VirtualFileManager::class.java)
        `when`(virtualFileManager.addAsyncFileListener(any(), any())).then {
            val asyncFileListener = it.arguments[0] as AsyncFileListener
            fileChangeEventSender = FileChangeEventSender(asyncFileListener)
            return@then Unit
        }
        fileChangesManager = FileChangesManager(project, projectDir, virtualFileManager)

        JuggLogger.listenProjectLog(project, StdLogger("test"))
    }

    private fun renewManager() {
        juggManager = JuggManager(
            project, projectDir, deviceStatusListener,
            fileChangesManager = fileChangesManager,
            deployTargetManager = deployTargetManager,
            compileThread = SyncExecutorService(),
            deployThread = SyncExecutorService(),
            compileContextManager = compileContextManager
        )
        juggManager.init()
    }

    private fun initEnv() {
        val state = DeployState(isReadyInstall = true, isReadyApply = true, disableMessage = null)
        juggManager.updateStatus(state)

        assertEquals(1, deployTargetManager.getApks().size)
        assertEquals(1, compileContextManager.compileContext.apks.size)
        assertTrue(::fileChangeEventSender.isInitialized)
        verify(deviceStatusListener, times(1)).updateStatus(state)
    }

    @Before
    fun init() {
        clearBuild()
        renewComponents()
        renewManager()
        initEnv()
    }

    @Test
    fun testDeviceStatusUpdate() {
        initEnv()
    }

    @Test
    fun testCompileJavaFile() {
        val relativePath = "app/src/main/java/com/example/myapplication/ABC.java"
        val sourceFile = File(assetsAndroidModifySourceDir, relativePath)
        val destFile = File(assetsAndroidDir, relativePath)
        fileChangeEventSender.copyAndNotifyFileChanges(sourceFile, destFile)

        val classPathFile = File(compileContextManager.compileContext.classPathDir, "com/example/myapplication/ABC.class")
        assertTrue(classPathFile.exists())
        assertEquals(406, classPathFile.length())

        val dexFile = File(compileContextManager.stagingDir, "classes/com/example/myapplication/ABC.dex")
        assertTrue(dexFile.exists())
        assertEquals(716, dexFile.length())
    }

    @Test
    fun testCompileActivity() {
        val relativePath = "app/src/main/java/com/example/myapplication/MainActivity2.java"
        val sourceFile = File(assetsAndroidModifySourceDir, relativePath)
        val destFile = File(assetsAndroidDir, relativePath)
        fileChangeEventSender.copyAndNotifyFileChanges(sourceFile, destFile)

        val classPathFile = File(compileContextManager.compileContext.classPathDir, "com/example/myapplication/MainActivity2.class")
        // TODO fix this
        assertTrue(!classPathFile.exists())
    }
}
