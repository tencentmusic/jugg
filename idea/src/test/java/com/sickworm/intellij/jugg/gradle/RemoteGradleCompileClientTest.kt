@file:Suppress("IncorrectParentDisposable")

package com.sickworm.intellij.jugg.gradle

import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.RemoteGradleCompileClient
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
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

class RemoteGradleCompileClientTest {

    companion object {

        private lateinit var juggGradleCompileOptions: JuggGradleCompileOptions
        private lateinit var project: JuggMockProject
        private var isNeedTest = false

        @JvmStatic
        @BeforeClass
        fun beforeClass() {
            val homeDir = System.getProperty("user.home")
            val clientInfoFile = File("$homeDir/Downloads/remote_compile_client_info.json")
            if (!clientInfoFile.exists()) {
                logger.warn("RemoteClient login failed, client info file not found: ${clientInfoFile.absolutePath}, ignore.")
                return
            }

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
                it.remoteToLocalSyncPath = jsonObject.getString("remoteToLocalSyncPath")
                it.localToRemoteIftConfigName = jsonObject.getString("localToRemoteIftConfigName")
                it.httpProxyIp = jsonObject.getString("httpProxyIp")
                it.httpProxyPort = jsonObject.getInt("httpProxyPort")
            }
            juggGradleCompileOptions = options.toCompileOptions(pathManager)

            isNeedTest = true
        }
    }

    @Test
    fun testCompile() {
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
    fun testCancel() {
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
}