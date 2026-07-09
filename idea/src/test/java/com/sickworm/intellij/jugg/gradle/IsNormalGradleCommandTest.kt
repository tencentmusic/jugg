package com.sickworm.intellij.jugg.gradle

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.gradle.compile.CompileProjectCommand
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals

class IsNormalGradleCommandTest {

    @Before
    fun setUp() {
        TestGlobal.init()
    }

    @Test
    fun test() {
        val testMap = mapOf(
            "./gradlew :app:assembleDebug" to true,
            "./gradlew :app:assembleDebug " to true,
            ".\\gradlew.bat :app:assembleDebug" to true,
            "gradle :app:assembleDebug" to true,
            "gradlew :app:assembleDebug" to true,
            "gradlew    :app:assembleDebug" to true,
            "./gradlew --dry-run --no-daemon" to true,
            "init.sh && ./gradlew :app:assembleDebug" to true,
            "cd C:/project/demo/android && gradlew.bat :app:baseApp:deployDebug" to true,
            "./build.sh" to false,
            "./gradlew :app:assembleDebug && echo ok" to false,
            "./gradlew :app:assembleDebug --dry-run --no-daemon" to true,
        )

        testMap.forEach { (command, isNormalGradleCommand) ->
            val compileProjectCommand = CompileProjectCommand(command, "readProjectInfo.gradle", "/root/projects/projectABC")
            assertEquals(isNormalGradleCommand, compileProjectCommand.isNormalGradleCommand, "command: $command")
        }
    }
    @Test
    fun androidTestBuildTarget_shouldInjectGradleProperty() {
        val command = CompileProjectCommand(
            "./gradlew :app:customDebugTask",
            "/root/projects/projectABC",
            "readProjectInfo.gradle",
            buildTarget = BuildTarget.ANDROID_TEST,
        ).baseCommand

        assertEquals(true, command.contains("-Pjugg.buildTarget=ANDROID_TEST"), command)
        assertEquals(false, command.contains("assembleDebugAndroidTest"), command)
    }

    @Test
    fun androidTestBuildTarget_shouldQuoteLibraryTestTasksProperty() {
        val command = CompileProjectCommand(
            "./gradlew :app:customDebugTask",
            "/root/projects/projectABC",
            "readProjectInfo.gradle",
            buildTarget = BuildTarget.ANDROID_TEST,
            libraryTestApkGradleTasks = listOf(
                ":library1:assembleDebugAndroidTest",
                ":library2:assemblePaidDebugAndroidTest",
            ),
        ).baseCommand

        assertEquals(
            true,
            command.contains("\"-Pjugg.libraryTestTasks=:library1:assembleDebugAndroidTest;:library2:assemblePaidDebugAndroidTest\""),
            command,
        )
    }

    @Test
    fun compatibleDeploymentEnabled_shouldEnableRuntimeInjectionProperty() {
        val command = CompileProjectCommand(
            "./gradlew :app:customDebugTask",
            "/root/projects/projectABC",
            "readProjectInfo.gradle",
        ).baseCommand

        assertEquals(true, command.contains("-Pjugg.inject.application.enable=true"), command)
    }

    @Test
    fun appBuildTarget_shouldIgnoreLibraryTestTasksProperty() {
        val command = CompileProjectCommand(
            "./gradlew :app:customDebugTask",
            "/root/projects/projectABC",
            "readProjectInfo.gradle",
            buildTarget = BuildTarget.APP,
            libraryTestApkGradleTasks = listOf(":library1:assembleDebugAndroidTest"),
        ).baseCommand

        assertEquals(false, command.contains("jugg.libraryTestTasks"), command)
    }

}
