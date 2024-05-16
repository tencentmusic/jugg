package com.sickworm.intellij.jugg.gradle

import com.sickworm.intellij.jugg.gradle.compile.CompileProjectCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class IsNormalGradleCommandTest {

    @Test
    fun test() {
        val testMap = mapOf(
            "./gradlew :app:assembleDebug" to true,
            ".\\gradlew :app:assembleDebug" to true,
            "gradle :app:assembleDebug" to true,
            "gradlew :app:assembleDebug" to true,
            "gradlew    :app:assembleDebug" to true,
            "./build.sh" to false,
            "init.sh && ./gradlew :app:assembleDebug" to false,
            "./gradlew :app:assembleDebug && echo ok" to false,
        )

        testMap.forEach { (command, isNormalGradleCommand) ->
            val compileProjectCommand = CompileProjectCommand(command, "init.gradle", "/root/projects/projectABC")
            assertEquals(isNormalGradleCommand, compileProjectCommand.isNormalGradleCommand, "command: $command")
        }
    }
}