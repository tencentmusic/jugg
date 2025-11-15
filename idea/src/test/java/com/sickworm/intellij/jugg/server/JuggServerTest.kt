package com.sickworm.intellij.jugg.server

import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.JuggMockProject
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import com.sickworm.intellij.jugg.project.JuggPathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class JuggServerTest {

    @Test
    fun testReport() {
        val project = JuggMockProject(projectInfo.projectRoot)
        JuggLogger.register(project, buildDir)
        JuggLogger.listenProjectLog(project, logger)
        runBlocking {
            val job1 =
                JuggServer(project, JuggPathManager(File(project.basePath)), CoroutineScope(Dispatchers.IO)).report {
                    isSuccess = true
                }
            val job2 =
                JuggServer(project, JuggPathManager(File(project.basePath)), CoroutineScope(Dispatchers.IO)).report {
                    isSuccess = false
                }
            listOf(job1, job2).joinAll()
        }
    }
}