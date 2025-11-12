package com.sickworm.intellij.jugg.gradle

import com.sickworm.intellij.jugg.gradle.compile.CompileProjectCommand
import com.sickworm.intellij.jugg.mock.TestGlobal
import kotlin.test.Test
import kotlin.test.assertEquals

class IsNormalGradleCommandTest {

    @Test
    fun test() {
        TestGlobal.init()
        val testMap = mapOf(
            "./gradlew :app:assembleDebug" to true,
            ".\\gradlew.bat :app:assembleDebug" to true,
            "gradle :app:assembleDebug" to true,
            "gradlew :app:assembleDebug" to true,
            "gradlew    :app:assembleDebug" to true,
            "./gradlew --dry-run --no-daemon" to true,
            "init.sh && ./gradlew :app:assembleDebug" to true,
            "cd C:/project/demo/android && gradlew.bat :app:baseApp:deployDebug" to true,
            "./build.sh" to false,
            "./gradlew :app:assembleDebug && echo ok" to false,
        )

        testMap.forEach { (command, isNormalGradleCommand) ->
            val compileProjectCommand = CompileProjectCommand(command, "readProjectInfo.gradle", "/root/projects/projectABC")
            assertEquals(isNormalGradleCommand, compileProjectCommand.isNormalGradleCommand, "command: $command")
        }
    }
}