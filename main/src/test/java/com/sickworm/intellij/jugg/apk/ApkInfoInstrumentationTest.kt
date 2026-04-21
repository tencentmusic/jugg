package com.sickworm.intellij.jugg.apk

import com.sickworm.intellij.jugg.deploy.ApkInfoSerializer
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ApkInfoInstrumentationTest {

    /**
     * Verifies that ApkInfo carries the two new optional instrumentation fields.
     */
    @Test
    fun `app apk has null instrumentation fields by default`() {
        val apkInfo = ApkInfo(
            files = listOf(ApkFileUnit("com.example.app", "", true, File("app.apk"))),
            applicationId = "com.example.app"
        )
        assertNull(apkInfo.instrumentationTargetPackage)
        assertNull(apkInfo.instrumentationRunner)
    }

    @Test
    fun `test apk carries instrumentation fields`() {
        val apkInfo = ApkInfo(
            files = listOf(ApkFileUnit("com.example.app.test", "", true, File("app-debug-androidTest.apk"))),
            applicationId = "com.example.app.test",
            instrumentationTargetPackage = "com.example.app",
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
        )
        assertEquals("com.example.app", apkInfo.instrumentationTargetPackage)
        assertEquals("androidx.test.runner.AndroidJUnitRunner", apkInfo.instrumentationRunner)
    }
}

class ApkInfoSerializerCompatTest {

    /** Deserializing old JSON (without the two new instrumentation fields) must produce null for them. */
    @Test
    fun `old json without instrumentation fields deserializes to null`() {
        val oldJson = """[{"files":[{"moduleName":"","debuggable":true,"apkFilePath":"app.apk"}],"applicationId":"com.example.app"}]"""
        val serializer = ApkInfoSerializer()
        val result = serializer.deserialize(File("."), oldJson)
        assertEquals(1, result.size)
        assertNull(result[0].instrumentationTargetPackage)
        assertNull(result[0].instrumentationRunner)
    }

    @Test
    fun `new json with instrumentation fields roundtrips correctly`() {
        val apkInfos = listOf(
            ApkInfo(
                files = listOf(ApkFileUnit("com.example.app.test", "", true, File("test.apk"))),
                applicationId = "com.example.app.test",
                instrumentationTargetPackage = "com.example.app",
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
            )
        )
        val serializer = ApkInfoSerializer()
        val json = serializer.serialize(File("."), apkInfos)
        val result = serializer.deserialize(File("."), json)
        assertEquals("com.example.app", result[0].instrumentationTargetPackage)
        assertEquals("androidx.test.runner.AndroidJUnitRunner", result[0].instrumentationRunner)
    }
}
