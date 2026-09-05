package com.sickworm.intellij.jugg.compiler.external

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalBuildTaskRunnerTest {

    @Test
    fun `replaces Android build tasks and preserves Gradle arguments`() {
        val command = ExternalBuildTaskRunner.deriveCommand(
            "./gradlew :app:assembleDebug --offline -Pchannel=demo",
            listOf(":flutter:compileFlutterBuildDebug", ":native:mergeDebugNativeLibs"),
        )

        assertEquals(
            "./gradlew :flutter:compileFlutterBuildDebug :native:mergeDebugNativeLibs --offline -Pchannel=demo",
            command,
        )
    }

    @Test
    fun `rejects compound shell commands`() {
        assertNull(ExternalBuildTaskRunner.deriveCommand(
            "./gradlew :app:assembleDebug && echo done",
            listOf(":flutter:compileFlutterBuildDebug"),
        ))
        assertNull(ExternalBuildTaskRunner.deriveCommand(
            "./gradlew :app:assembleDebug -Pvalue='$(touch injected)'",
            listOf(":flutter:compileFlutterBuildDebug"),
        ))
    }
}
