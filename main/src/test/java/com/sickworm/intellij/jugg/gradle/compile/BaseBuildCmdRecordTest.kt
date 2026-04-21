package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.BuildTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaseBuildCmdRecordTest {

    @Test
    fun `serialize and deserialize roundtrip for APP target`() {
        val original = BaseBuildCmdRecord("./gradlew :app:assembleDebug", BuildTarget.APP)
        val json = original.toJson()
        val parsed = BaseBuildCmdRecord.fromJson(json)
        assertEquals(original.compileCommand, parsed.compileCommand)
        assertEquals(original.buildTarget, parsed.buildTarget)
    }

    @Test
    fun `serialize and deserialize roundtrip for ANDROID_TEST target`() {
        val original = BaseBuildCmdRecord("./gradlew :app:assembleDebug :app:assembleDebugAndroidTest", BuildTarget.ANDROID_TEST)
        val json = original.toJson()
        val parsed = BaseBuildCmdRecord.fromJson(json)
        assertEquals(original.compileCommand, parsed.compileCommand)
        assertEquals(BuildTarget.ANDROID_TEST, parsed.buildTarget)
    }

    @Test
    fun `legacy single-line text is parsed as APP target`() {
        val legacyText = "./gradlew :app:assembleDebug"
        val parsed = BaseBuildCmdRecord.fromJson(legacyText)
        assertEquals(legacyText, parsed.compileCommand)
        assertEquals(BuildTarget.APP, parsed.buildTarget)
    }

    @Test
    fun `legacy single-line text with spaces is parsed as APP target`() {
        val legacyText = "./gradlew :app:assembleDevelopmentDebug"
        val parsed = BaseBuildCmdRecord.fromJson(legacyText)
        assertEquals(legacyText, parsed.compileCommand)
        assertEquals(BuildTarget.APP, parsed.buildTarget)
    }
}
