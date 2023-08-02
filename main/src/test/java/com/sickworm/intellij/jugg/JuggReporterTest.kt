package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.JuggReporter
import com.sickworm.intellij.jugg.mock.JuggMockProject
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

class JuggReporterTest {

    @Test
    fun testReport() {
        val project = JuggMockProject(projectInfo.projectRoot)
        JuggLogger.register(project, buildDir)
        JuggLogger.listenProjectLog(project, logger)
        runBlocking {
            val job1 = JuggReporter(project).report {
                isSuccess = true
            }
            val job2 = JuggReporter(project).report {
                isSuccess = false
            }
            listOf(job1, job2).joinAll()
        }
    }
}