package com.sickworm.intellij.jugg.remote

import com.google.gson.Gson
import com.jcraft.jsch.*
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.JuggMockProject
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class RemoteClientTest {

    @Test
    fun test() {
        val homeDir = System.getProperty("user.home")
        val clientInfoFile = File("$homeDir/Downloads/remote_compile_client_info.json")
        if (!clientInfoFile.exists()) {
            logger.warn("RemoteClient login failed, client info file not found: ${clientInfoFile.absolutePath}, ignore.")
            return
        }
        val project = JuggMockProject(projectInfo.projectRoot)
        val pathManager = JuggPathManager(project, projectInfo.projectRoot)
        JuggLogger.register(project, pathManager.logDir)
        @Suppress("IncorrectParentDisposable")
        val remoteClient = RemoteClient(project, project)
        val clientInfo = Gson().fromJson(clientInfoFile.readText(), RemoteCompileClientInfo::class.java)
        remoteClient.login(clientInfo)
        val remoteCompileResult = remoteClient.compileAndFetchResult()
        assertTrue(remoteCompileResult.isSuccess)
    }

}