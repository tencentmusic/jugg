package com.sickworm.intellij.jugg.deploy.run.applychanges

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidTestPackageDeployPolicyTest {

    @Test
    fun `other targeting test APK non install deploy is skipped with warning`() {
        val decision = AndroidTestPackageDeployPolicy.decide(
            apkInfos = listOf(otherTargetingTestApk()),
            scopedData = emptyDeployData(),
            requestedType = AndroidDeployType.APPLY_CHANGES,
        )

        assertTrue(decision.skip)
        assertEquals(AndroidDeployType.APPLY_CHANGES, decision.effectiveType)
        assertTrue(decision.warningMessage.orEmpty().contains("other-targeting androidTest APK"))
    }

    @Test
    fun `other targeting test APK install deploy is kept as install`() {
        val decision = AndroidTestPackageDeployPolicy.decide(
            apkInfos = listOf(otherTargetingTestApk()),
            scopedData = emptyDeployData(),
            requestedType = AndroidDeployType.INSTALL,
        )

        assertEquals(false, decision.skip)
        assertEquals(AndroidDeployType.INSTALL, decision.effectiveType)
        assertEquals(null, decision.warningMessage)
    }

    @Test
    fun `regular APK non install deploy is kept`() {
        val decision = AndroidTestPackageDeployPolicy.decide(
            apkInfos = listOf(appApk()),
            scopedData = emptyDeployData(),
            requestedType = AndroidDeployType.APPLY_CHANGES,
        )

        assertEquals(false, decision.skip)
        assertEquals(AndroidDeployType.APPLY_CHANGES, decision.effectiveType)
        assertEquals(null, decision.warningMessage)
    }

    private fun appApk() = ApkInfo(
        files = listOf(ApkFileUnit("com.example.app", "", true, File("app.apk"))),
        applicationId = "com.example.app",
    )

    private fun otherTargetingTestApk() = ApkInfo(
        files = listOf(ApkFileUnit("com.example.app.test", "", true, File("test.apk"))),
        applicationId = "com.example.app.test",
        instrumentationTargetPackage = "com.example.app",
    )

    private fun emptyDeployData() = JuggDeployData(
        apks = emptyList(),
        newClasses = emptyList(),
        hotFixModifiedClasses = emptyList(),
        hotReloadModifiedClasses = emptyList(),
        effectedClassNodes = emptyList(),
        overlays = emptyList(),
        parsedDex = ParsedDex.EMPTY,
        isFullRes = false,
        isWarmUp = false,
    )
}
