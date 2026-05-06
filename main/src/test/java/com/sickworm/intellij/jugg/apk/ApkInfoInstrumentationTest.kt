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

    @Test
    fun `isOtherTargetingTestApk false for app apk`() {
        val apkInfo = ApkInfo(
            files = listOf(ApkFileUnit("com.example.app", "", true, File("app.apk"))),
            applicationId = "com.example.app"
        )
        assertEquals(false, apkInfo.isOtherTargetingTestApk)
    }

    @Test
    fun `isOtherTargetingTestApk true for app-style test apk targeting different package`() {
        val apkInfo = ApkInfo(
            files = listOf(ApkFileUnit("com.example.app.test", "", true, File("app-debug-androidTest.apk"))),
            applicationId = "com.example.app.test",
            instrumentationTargetPackage = "com.example.app",
        )
        assertEquals(true, apkInfo.isOtherTargetingTestApk)
    }

    @Test
    fun `isOtherTargetingTestApk false for library-style test apk targeting itself`() {
        val apkInfo = ApkInfo(
            files = listOf(ApkFileUnit("com.example.lib.test", "", true, File("lib-debug-androidTest.apk"))),
            applicationId = "com.example.lib.test",
            instrumentationTargetPackage = "com.example.lib.test",
        )
        assertEquals(false, apkInfo.isOtherTargetingTestApk)
    }

    @Test
    fun `isOtherTargetingTestApk false when test apk has null target package`() {
        val apkInfo = ApkInfo(
            files = listOf(ApkFileUnit("com.example.app.test", "", true, File("app-debug-androidTest.apk"))),
            applicationId = "com.example.app.test",
            instrumentationTargetPackage = null,
        )
        assertEquals(false, apkInfo.isOtherTargetingTestApk)
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
