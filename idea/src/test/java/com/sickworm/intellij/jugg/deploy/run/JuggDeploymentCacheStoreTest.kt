package com.sickworm.intellij.jugg.deploy.run

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Verifies the observable persistence behavior of the local deployment cache.
 */
class JuggDeploymentCacheStoreTest {

    @Test
    fun `local deployment cache store persists source snapshot`() {
        val cacheFile = File.createTempFile("jugg-deployment-cache", ".bin")
        cacheFile.deleteOnExit()
        val store = JuggDeploymentCacheStore(cacheFile)
        val entry = JuggDeploymentCacheStore.CacheEntry(
            apkPaths = listOf("/tmp/app.apk"),
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

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
    }
}
