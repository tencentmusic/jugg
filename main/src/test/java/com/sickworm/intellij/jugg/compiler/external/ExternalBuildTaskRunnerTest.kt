package com.sickworm.intellij.jugg.compiler.external

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalBuildTaskRunnerTest {

    @Test
    fun `replaces Android build tasks and preserves Gradle arguments`() {
        val command = deriveExternalBuildCommand(
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
        assertNull(deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug && echo done",
            listOf(":flutter:compileFlutterBuildDebug"),
        ))
        assertNull(deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug -Pvalue='$(touch injected)'",
            listOf(":flutter:compileFlutterBuildDebug"),
        ))
    }

    @Test
    fun `preserves exclude task values and quoted Gradle arguments`() {
        val command = deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug -x :app:compileDebugKotlin -Pmessage=\"hello world\" --offline",
            listOf(":flutter:packJniLibsflutterBuildDebug"),
        )

        assertEquals(
            "./gradlew :flutter:packJniLibsflutterBuildDebug -x :app:compileDebugKotlin -Pmessage=\"hello world\" --offline",
            command,
        )
    }

    @Test
    fun `rejects commands that cannot update external artifacts`() {
        assertNull(deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug --dry-run",
            listOf(":flutter:packJniLibsflutterBuildDebug"),
        ))
        assertNull(deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug -x :flutter:compileFlutterBuildDebug",
            listOf(":flutter:packJniLibsflutterBuildDebug"),
        ))
        assertNull(deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug --unknown value",
            listOf(":native:mergeDebugNativeLibs"),
        ))
    }
}
