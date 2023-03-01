@file:Suppress("IncorrectParentDisposable")

package com.sickworm.intellij.jugg.remote

import com.google.gson.Gson
import com.jcraft.jsch.*
import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteClientTest {

    companion object {

        private lateinit var clientInfo: RemoteCompileClientInfo
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
            clientInfo = Gson().fromJson(clientInfoFile.readText(), RemoteCompileClientInfo::class.java)

            project = JuggMockProject(projectInfo.projectRoot)
            val pathManager = JuggPathManager(project, projectInfo.projectRoot)
            JuggLogger.register(project, pathManager.logDir)

            isNeedTest = true
        }
    }

    @Test
    fun testCompile() {
        if (!isNeedTest) return

        val remoteClient = RemoteClient(project, project)
        remoteClient.login(clientInfo)
        val remoteCompileResult = remoteClient.compileAndFetchResult()
        assertTrue(remoteCompileResult.isSuccess)
    }

    @Test
    fun testFetchClasspath() {
        if (!isNeedTest) return

        val modules = GradleSettingsDummyReader(assetsAndroidDir).readProjectDirs()
            .map { ModuleBuildPathInfo(projectInfo.projectRoot, it) }

        val remoteClient = RemoteClient(project, project)
        remoteClient.login(clientInfo)

        val fetchClasspathResult = remoteClient.fetchClasspathResult(modules)
        assertTrue(fetchClasspathResult)
    }

    @Test
    fun testCancel() {
        if (!isNeedTest) return

        val remoteClient = RemoteClient(project, project)
        remoteClient.terminalOutputListener = object : RemoteClient.TerminalOutputListener {
            override fun onOutput(line: String) {
                println(line)
                if (line.contains("TaskRequests:")) {
                    remoteClient.cancelAction()
                }
            }

            override fun onOutputErr(line: String) {
                System.err.println(line)
            }
        }
        remoteClient.login(clientInfo)
        val remoteCompileResult = remoteClient.compileAndFetchResult()
        assertFalse(remoteCompileResult.isSuccess)
    }
}