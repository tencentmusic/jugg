package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.mock.JuggMockProject
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

class JuggServerTest {

    @Test
    fun testReport() {
        val project = JuggMockProject(projectInfo.projectRoot)
        JuggLogger.register(project, buildDir)
        JuggLogger.listenProjectLog(project, logger)
        runBlocking {
            val job1 = JuggServer(project).report {
                isSuccess = true
            }
            val job2 = JuggServer(project).report {
                isSuccess = false
            }
            listOf(job1, job2).joinAll()
        }
    }
}