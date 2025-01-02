@file:Suppress("IncorrectParentDisposable")

package com.sickworm.intellij.jugg.gradle

import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.RemoteGradleCompileClient
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.toCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteGradleCompileClientTest : LocalGradleCompileClientTest() {

    companion object {

        private lateinit var project: JuggMockProject
        private var isNeedTest = false

        @JvmStatic
        @BeforeClass
        fun beforeClass() {
            val clientInfoFilePath = System.getenv("JUGG_REMOTE_CONFIG_FILE") ?: run {
                logger.warn("RemoteClient login failed, JUGG_REMOTE_CONFIG_FILE not found.")
                return
            }
            val clientInfoFile = File(clientInfoFilePath)
            assertTrue(clientInfoFile.exists())

            project = JuggMockProject(projectInfo.projectRoot)
            val pathManager = JuggPathManager(projectInfo.projectRoot)
            JuggLogger.register(project, pathManager.logDir)

            val options = JuggRunConfigurationOptions().also {
                val jsonObject = JSONObject(clientInfoFile.readText())
                it.compileCommand = jsonObject.getString("compileCommand")
                it.outputApkName = jsonObject.getString("outputApkName")
                it.isRemoteCompile = true
                it.remoteSshUser = jsonObject.getString("remoteSshUser")
                it.remoteSshPassword = jsonObject.getString("remoteSshPassword")
                it.remoteSshIp = jsonObject.getString("remoteSshIp")
                it.remoteSshPort = jsonObject.getInt("remoteSshPort")
                it.httpProxyIp = jsonObject.getString("httpProxyIp")
                it.httpProxyPort = jsonObject.getInt("httpProxyPort")
                it.syncMode = "rsync_simple"
            }
            juggGradleCompileOptions = options.toCompileOptions(pathManager)

            isNeedTest = true

            val initGradleFile = File(juggGradleCompileOptions.projectRootPath, juggGradleCompileOptions.initGradleFileRelativePath)
            JuggCompilerHelper::class.java.getResource("/gradle/readProjectInfo.gradle.kts")!!.openStream().use { ins ->
                val text = ins.reader().readText()
                initGradleFile.parentFile.mkdirs()
                initGradleFile.writeText(text)
            }
        }
    }

    @Test
    override fun testCompile() {
        if (!isNeedTest) return

        val remoteClient = RemoteGradleCompileClient(project, logger = logger)
        remoteClient.login(juggGradleCompileOptions)
        val remoteCompileResult = remoteClient.compileAndFetchResult()
        assertTrue(remoteCompileResult.isSuccess)
    }

    @Test
    fun testFetchClasspath() {
        if (!isNeedTest) return

        val modules = GradleSettingsDummyReader(projectInfo.projectRoot).readProjectDirs()
            .map { ModuleBuildPathInfo(projectInfo.projectRoot, it, mockModule.buildVariant) }

        val remoteClient = RemoteGradleCompileClient(project)
        remoteClient.login(juggGradleCompileOptions)

        val costTime = measureTimeMillis {
            val fetchClasspathResult = remoteClient.fetchClasspathResult(modules)
            assertTrue(fetchClasspathResult != null)
        }
        println("costTime ${costTime}ms")
    }

    @Test
    override fun testCancel() {
        if (!isNeedTest) return

        val remoteClient = RemoteGradleCompileClient(project)
        remoteClient.terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {

            override fun onOutput(line: String, isNeedPrint: Boolean) {
                if (isNeedPrint) {
                    println(line)
                }
                if (line.contains(":preBuild")) {
                    remoteClient.cancelAction(true)
                }
            }

            override fun onOutputErr(line: String) {
                System.err.println(line)
            }
        }
        remoteClient.login(juggGradleCompileOptions)
        val remoteCompileResult = remoteClient.compileAndFetchResult()
        assertFalse(remoteCompileResult.isSuccess)
    }


    override fun testFetchLibraryChanges() {
        if (!isNeedTest) return
        super.testFetchLibraryChanges()
    }

    override fun testFetchLocalLibraryChanges() {
        if (!isNeedTest) return
        super.testFetchLocalLibraryChanges()
    }

    override fun testFetchLocalLibraryAarChanges() {
        if (!isNeedTest) return
        super.testFetchLocalLibraryAarChanges()
    }

    override fun getClient(): IGradleCompileClient {
        return RemoteGradleCompileClient(project, false, logger)
    }
}