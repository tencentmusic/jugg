package com.sickworm.intellij.jugg

import com.android.tools.idea.run.ApkInfo
import com.intellij.execution.RunManager
import com.intellij.mock.MockProject
import com.intellij.mock.MockRunManager
import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.deploy.DeployState
import com.sickworm.intellij.jugg.deploy.DeployTargetManager
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.ide.toolWindow.DeviceStatusListener
import com.sickworm.intellij.jugg.ide.toolWindow.JuggLogger
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ExecutorService
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JuggManagerTest {

    private lateinit var project: Project
    private lateinit var projectDir: String
    private lateinit var juggManager: JuggManager
    private lateinit var fileChangesManager: MockFileChangesManager
    private lateinit var deviceStatusListener: DeviceStatusListener
    private lateinit var deployTargetManager: DeployTargetManager
    private lateinit var compileThread: ExecutorService
    private lateinit var deployThread: ExecutorService
    private lateinit var compileContextManager: CompileContextManager

    @Before
    fun init() {
        clearBuild()
        renewComponents()
        renewManager()
    }

    private fun renewComponents() {
        project = JuggMockProject()
        projectDir = "src/test/assets/android/MyApplicationIntellij"
        deviceStatusListener = object: DeviceStatusListener {
            override fun updateStatus(state: DeployState) {
            }
        }
        fileChangesManager = MockFileChangesManager(project, projectDir)
        deployTargetManager = MockDeployTargetManager(project)
        compileThread = SyncExecutorService()
        deployThread = SyncExecutorService()
        compileContextManager = MockCompileContextManager(project, projectDir)
    }

    private fun renewManager() {
        juggManager = JuggManager(
            project, projectDir, deviceStatusListener,
            fileChangesManager = fileChangesManager,
            deployTargetManager = deployTargetManager,
            compileThread = compileThread,
            deployThread = deployThread,
            compileContextManager = compileContextManager
        )
        juggManager.init()
    }

    private fun initEnv() {
        val state = DeployState(isReadyInstall = true, isReadyApply = true, disableMessage = null)
        juggManager.updateStatus(state)

        assertEquals(1, deployTargetManager.getApks().size)
        assertEquals(1, compileContextManager.compileContext.apks.size)
    }

    @Test
    fun testDeviceStatusUpdate() {
        var isReadyApply = false
        deviceStatusListener = object: DeviceStatusListener {
            override fun updateStatus(state: DeployState) {
                println("updateStatus $state")
                if (state.isReadyApply) {
                    isReadyApply = true
                }
            }
        }
        renewManager()

        initEnv()
        assertTrue(isReadyApply)
    }

    @Test
    fun testCompileJavaFile() {
        initEnv()

        val relativePath = "app/src/main/java/com/example/myapplication/ABC.java"
        val sourceFile = File(assetsAndroidModifySourceDir, relativePath)
        val destFile = File(assetsAndroidDir, relativePath)
        fileChangesManager.copyAndNotifyFileChanges(sourceFile, destFile)

        val classPathFile = File(compileContextManager.compileContext.classPathDir, "com/example/myapplication/ABC.class")
        assertTrue(classPathFile.exists())
        assertEquals(406, classPathFile.length())

        val dexFile = File(compileContextManager.stagingDir, "classes/com/example/myapplication/ABC.dex")
        assertTrue(dexFile.exists())
        assertEquals(716, dexFile.length())
    }

    @Test
    fun testCompileActivity() {
        initEnv()

        val relativePath = "app/src/main/java/com/example/myapplication/MainActivity2.java"
        val sourceFile = File(assetsAndroidModifySourceDir, relativePath)
        val destFile = File(assetsAndroidDir, relativePath)
        fileChangesManager.copyAndNotifyFileChanges(sourceFile, destFile)

        val classPathFile = File(compileContextManager.compileContext.classPathDir, "com/example/myapplication/MainActivity2.class")
        // TODO fix this
        assertTrue(!classPathFile.exists())
    }
}

class JuggMockProject : MockProject(null, {}) {

    private val runManager = MockRunManager()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getService(serviceClass: Class<T>): T? {
        if (serviceClass == RunManager::class.java) {
            return runManager as T
        }
        return super.getService(serviceClass)
    }
}

class MockDeployTargetManager(project: Project): DeployTargetManager(project) {

    override fun getApks(): List<ApkInfo> {
        return listOf(ApkInfo(assetsApkFile, androidApkPackage))
    }
}

class MockCompileContextManager(project: Project, projectDir: String)
    : CompileContextManager(project, projectDir) {

    override fun init() {
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

        // TODO projectDeps
        val libDep = IntellijLibraryConfigParser(intellijLibraryDir, assetsAndroidDir.absolutePath).parse()!!
        dependencies = listOf(
            classPathDir.absolutePath,
            androidJar.absolutePath
        ) + libDep
    }
}

class MockFileChangesManager(project: Project, projectDir: String)
    : FileChangesManager(project, projectDir) {

    override fun startListen(compileContext: ICompileContext, listener: FileChangesListener) {
        this.compileContext = compileContext
        this.listener = listener
    }

    fun copyAndNotifyFileChanges(sourceFile: File, destFile: File) {
        sourceFile.copyTo(destFile, overwrite = true)
        val file = MockIoVirtualFile(destFile)
        val event = VFileContentChangeEvent(Any(), file, 0L, 0L, false)
        notifyFileChanges(mutableListOf(event))
    }
}

class MockIoVirtualFile(val file: File): MockVirtualFile(file.name, file.readText()) {

    override fun getPath(): String {
        return file.absolutePath
    }
}