package com.sickworm.intellij.jugg.project.dependency

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.gradle.compile.CompileProjectCommand
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadProjectInfoBuildTargetCommandTest {

    @Before
    fun setUp() {
        TestGlobal.init()
    }

    @Test
    fun `localFetch style command injects ANDROID_TEST from deploy history`() {
        val deployHistoryManager = mock<IDeployHistoryManager>()
        whenever(deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.ANDROID_TEST, 1L),
        )
        val buildTarget = deployHistoryManager.getFullBuildInfo()?.buildTarget ?: BuildTarget.APP

        val command = CompileProjectCommand(
            "./gradlew :app:assembleDebug --dry-run",
            "/root/projects/projectABC",
            "readProjectInfo.gradle",
            buildTarget = buildTarget,
        ).baseCommand

        assertEquals(BuildTarget.ANDROID_TEST, buildTarget)
        assertTrue(command.contains("-Pjugg.buildTarget=ANDROID_TEST"), command)
    }

    @Test
    fun `localFetch style command omits buildTarget property when baseline is APP`() {
        val deployHistoryManager = mock<IDeployHistoryManager>()
        whenever(deployHistoryManager.getFullBuildInfo()).thenReturn(null)
        val buildTarget = deployHistoryManager.getFullBuildInfo()?.buildTarget ?: BuildTarget.APP

        val command = CompileProjectCommand(
            "./gradlew :app:assembleDebug --dry-run",
            "/root/projects/projectABC",
            "readProjectInfo.gradle",
            buildTarget = buildTarget,
        ).baseCommand

        assertEquals(BuildTarget.APP, buildTarget)
        assertFalse(command.contains("jugg.buildTarget"), command)
    }
}
