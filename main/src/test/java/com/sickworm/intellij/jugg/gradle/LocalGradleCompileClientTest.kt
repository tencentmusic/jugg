@file:Suppress("IncorrectParentDisposable")

package com.sickworm.intellij.jugg.gradle

import com.jcraft.jsch.*
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.ide.GradleCompileSettings
import com.sickworm.intellij.jugg.ide.RemoteGradleCompileClientInfo
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalGradleCompileClientTest {

    companion object {

        private lateinit var gradleCompileSettings: GradleCompileSettings
        private lateinit var project: JuggMockProject

        @JvmStatic
        @BeforeClass
        fun beforeClass() {
            project = JuggMockProject(projectInfo.projectRoot)
            val pathManager = JuggPathManager(project, projectInfo.projectRoot)
            JuggLogger.register(project, pathManager.logDir)

            gradleCompileSettings = GradleCompileSettings(project.name,
                "./gradlew :app:assembleDebug",
                "app-debug.apk",
                false,
                RemoteGradleCompileClientInfo.createEmpty(),
            )
        }
    }

    @Test
    fun testCompile() {
        val localClient = LocalGradleCompileClient(project)
        localClient.login(gradleCompileSettings)
        val remoteCompileResult = localClient.compileAndFetchResult()
        assertTrue(remoteCompileResult.isSuccess)
    }

    @Test
    fun testCancel() {
        val localClient = LocalGradleCompileClient(project)
        localClient.terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {
            override fun onOutput(line: String) {
                println(line)
                if (line.contains(":preBuild")) {
                    localClient.cancelAction()
                }
            }

            override fun onOutputErr(line: String) {
                System.err.println(line)
            }
        }
        localClient.login(gradleCompileSettings)
        val remoteCompileResult = localClient.compileAndFetchResult()
        assertFalse(remoteCompileResult.isSuccess)
        assertTrue(remoteCompileResult.isCanceled)
    }
}