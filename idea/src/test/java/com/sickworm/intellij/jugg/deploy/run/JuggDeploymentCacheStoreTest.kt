package com.sickworm.intellij.jugg.deploy.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * Verifies the observable persistence behavior of the local deployment cache.
 */
class JuggDeploymentCacheStoreTest {

    @Test
    fun `local deployment cache store persists source snapshot`() {
        val cacheFile = File.createTempFile("jugg-deployment-cache", ".bin")
        cacheFile.deleteOnExit()
        val apkFile = File.createTempFile("jugg-deployment", ".apk")
        apkFile.deleteOnExit()
        val store = JuggDeploymentCacheStore(cacheFile)
        val entry = JuggDeploymentCacheStore.CacheEntry(
            apkPaths = listOf(apkFile.path),
            overlayId = JuggDeploymentCacheStore.OverlayId(
                sha = "overlay-sha",
                isBaseInstall = false,
                overlayFiles = listOf(JuggDeploymentCacheStore.OverlayFile("base.apk/classes.dex", 42L)),
            ),
        )

        store.store("device", PACKAGE_NAME, entry)
        val restored = JuggDeploymentCacheStore(cacheFile).load("device", PACKAGE_NAME)

        assertEquals(entry, restored)
    }

    @Test
    fun `missing cached APK affects only its device entry`() {
        val cacheFile = File.createTempFile("jugg-deployment-cache", ".bin")
        cacheFile.deleteOnExit()
        val staleApk = File.createTempFile("jugg-stale", ".apk")
        val currentApk = File.createTempFile("jugg-current", ".apk")
        currentApk.deleteOnExit()
        val store = JuggDeploymentCacheStore(cacheFile)
        val overlayId = JuggDeploymentCacheStore.OverlayId("sha", true, emptyList())
        store.store("old-device", PACKAGE_NAME, JuggDeploymentCacheStore.CacheEntry(listOf(staleApk.path), overlayId))
        val currentEntry = JuggDeploymentCacheStore.CacheEntry(listOf(currentApk.path), overlayId)
        store.store("current-device", PACKAGE_NAME, currentEntry)
        staleApk.delete()

        val restored = JuggDeploymentCacheStore(cacheFile)
        assertThrows(FileNotFoundException::class.java) { restored.load("old-device", PACKAGE_NAME) }
        assertEquals(currentEntry, restored.load("current-device", PACKAGE_NAME))
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
    }
}
