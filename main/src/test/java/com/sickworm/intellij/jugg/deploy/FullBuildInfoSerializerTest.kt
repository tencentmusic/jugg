package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.BuildTarget
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FullBuildInfoSerializerTest {

    @Test
    fun `serialize and deserialize full build info`() {
        val original = FullBuildInfo(
            compileCommand = "./gradlew :app:assembleDebug :app:assembleDebugAndroidTest",
            buildTarget = BuildTarget.ANDROID_TEST,
            createdAt = 1234L,
        )

        val json = FullBuildInfoSerializer().serialize(original)
        val parsed = FullBuildInfoSerializer().deserialize(json)

        assertEquals(original, parsed)
    }

    @Test
    fun `deserialize accepts null compile command`() {
        val json = """
            {
              "version": 1,
              "buildTarget": "APP",
              "createdAt": 1234
            }
        """.trimIndent()

        val parsed = FullBuildInfoSerializer().deserialize(json)

        assertNull(parsed.compileCommand)
        assertEquals(BuildTarget.APP, parsed.buildTarget)
        assertEquals(1234L, parsed.createdAt)
    }

    @Test
    fun `deserialize falls back to app target when build target is invalid`() {
        val json = """
            {
              "version": 1,
              "compileCommand": "./gradlew :app:assembleDebug",
              "buildTarget": "UNKNOWN",
              "createdAt": 1234
            }
        """.trimIndent()

        val parsed = FullBuildInfoSerializer().deserialize(json)

        assertEquals(BuildTarget.APP, parsed.buildTarget)
    }
}
