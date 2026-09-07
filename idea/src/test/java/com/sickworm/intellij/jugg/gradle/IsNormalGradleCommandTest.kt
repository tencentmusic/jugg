package com.sickworm.intellij.jugg.gradle

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.isMac
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.CompileProjectCommand
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun windowsProjectPathWithSpaces_shouldQuoteInitScriptPath() {
        val command = CompileProjectCommand(
            "./gradlew :app:customDebugTask",
            "D:/android work studio/InkBirdApp_Android",
            "D:/android work studio/InkBirdApp_Android/.gradle/jugg/readProjectInfo.gradle.kts",
        ).getCommand(isNeedSetChineseLanguage = false, isWindows = true)

        assertEquals(
            true,
            command.contains("-I \"D:/android work studio/InkBirdApp_Android/.gradle/jugg/readProjectInfo.gradle.kts\""),
            command,
        )
    }

    @Test
    fun macProjectPathWithSpaces_shouldExecuteCompileCommand() {
        assumeTrue(isMac)
        val tempDir = Files.createTempDirectory("jugg-command-test").toFile()
        try {
            val projectDir = File(tempDir, "Jugg Space Project").also { it.mkdirs() }
            val initScript = File(projectDir, ".gradle/jugg/readProjectInfo.gradle.kts").also {
                it.parentFile.mkdirs()
                it.writeText("")
            }
            val gradlew = File(projectDir, "gradlew").also {
                it.writeText("#!/bin/sh\npwd > execution.cwd\nprintf '%s\\n' \"\$@\" > execution.args\n")
                assertTrue(it.setExecutable(true))
            }
            val command = CompileProjectCommand(
                "./${gradlew.name} :app:assembleDebug",
                projectDir.absolutePath,
                initScript.absolutePath,
            )

            val result = CmdExecutor(TestGlobal.logger).invoke(command)

            assertEquals(0, result)
            assertEquals(projectDir.absolutePath, File(projectDir, "execution.cwd").readText().trim())
            val arguments = File(projectDir, "execution.args").readLines()
            assertTrue(arguments.windowed(2).contains(listOf("-I", initScript.absolutePath)))
        } finally {
            tempDir.deleteRecursively()
        }
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
