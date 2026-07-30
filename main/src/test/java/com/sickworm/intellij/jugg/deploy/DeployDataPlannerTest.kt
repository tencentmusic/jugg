package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import com.sickworm.intellij.jugg.deploy.data.ResourceApkGenerator
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

class DeployDataPlannerTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `compose resource compile state survives deploy data rebuild for retry`() {
        val tracker = DeployFileStateTracker()
        val resourceFile = temporaryFolder.newFile("strings.xml")
        val changedFile = ChangedFile(
            type = CompileFile.Type.ComposeResource,
            file = resourceFile.absoluteFile,
            baseDir = temporaryFolder.root.absoluteFile,
            module = ModuleInfo.virtualModule,
        )
        tracker.addChangedFiles(listOf(changedFile))
        tracker.updateUncompiledFiles(
            successFiles = listOf(
                CompileFile(
                    type = CompileFile.Type.ComposeResource,
                    file = resourceFile.absoluteFile,
                    baseDir = temporaryFolder.root.absoluteFile,
                    module = ModuleInfo.virtualModule,
                ),
            ),
            failedFiles = emptyList(),
        )
        val generator = Mockito.mock(DeployDataGenerator::class.java)
        Mockito.`when`(
            generator.buildDeployData(
                Mockito.anyList(),
                Mockito.eq(false),
                Mockito.eq(false),
                Mockito.eq(false),
                Mockito.eq(false),
                Mockito.anyList(),
            ),
        ).thenReturn(emptyDeployData())
        val planner = DeployDataPlanner(
            pathManager = Mockito.mock(JuggPathManager::class.java),
            deployDataGenerator = generator,
            resourceApkGenerator = Mockito.mock(ResourceApkGenerator::class.java),
            stateTracker = tracker,
            logger = Mockito.mock(Logger::class.java),
        )

        val firstDeploy = planner.buildDeployData(isWarmUp = false, isEnableCompatDeploy = false)
        val retryDeploy = planner.buildDeployData(isWarmUp = false, isEnableCompatDeploy = false)

        assertTrue(firstDeploy.isComposeResourceCompiled)
        assertTrue(retryDeploy.isComposeResourceCompiled)
    }

    private fun emptyDeployData(): JuggDeployData {
        return JuggDeployData(
            apks = emptyList(),
            newClasses = emptyList(),
            hotFixModifiedClasses = emptyList(),
            hotReloadModifiedClasses = emptyList(),
            effectedClassNodes = emptyList(),
            overlays = emptyList(),
            parsedDex = ParsedDex.EMPTY,
            isFullRes = false,
            isWarmUp = false,
        )
    }
}
