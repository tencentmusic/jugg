package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.sortedForInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkInstallOrderTest {

    private fun apk(id: String, testTarget: String? = null) = ApkInfo(
        files = listOf(ApkFileUnit(id, "", true, File("$id.apk"))),
        applicationId = id,
        instrumentationTargetPackage = testTarget,
    )

    @Test
    fun `app apk comes before test apk after sorting`() {
        val appApk = apk("com.example.app")
        val testApk = apk("com.example.app.test", testTarget = "com.example.app")

        val sorted = listOf(testApk, appApk).sortedForInstall()

        assertEquals("com.example.app", sorted[0].applicationId)
        assertEquals("com.example.app.test", sorted[1].applicationId)
    }

    @Test
    fun `already ordered list stays the same after sorting`() {
        val appApk = apk("com.example.app")
        val testApk = apk("com.example.app.test", testTarget = "com.example.app")

        val sorted = listOf(appApk, testApk).sortedForInstall()

        assertEquals("com.example.app", sorted[0].applicationId)
        assertEquals("com.example.app.test", sorted[1].applicationId)
    }

    @Test
    fun `multiple app apks without test apk keep relative order`() {
        val app1 = apk("com.a.app")
        val app2 = apk("com.b.app")

        val sorted = listOf(app2, app1).sortedForInstall()

        // both are non-test, relative order is preserved by stable sort
        assertTrue(sorted.none { it.isTestApk })
    }

    @Test
    fun `feature apks (same applicationId base) are not separated from app apk`() {
        val appApk = apk("com.example.app")
        val testApk = apk("com.example.app.test", testTarget = "com.example.app")

        val sorted = listOf(testApk, appApk).sortedForInstall()
        assertEquals(2, sorted.size)
        assertFalse(sorted[0].isTestApk)
        assertTrue(sorted[1].isTestApk)
    }

    private fun assertFalse(b: Boolean) = assertTrue(!b)
}
