package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.loader.JuggHotUpdateManager
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class JuggGlobalStoragePathTest {

    @Test
    fun hotUpdate_shouldUseDotJuggUnderUserHome() {
        assertEquals(
            File(System.getProperty("user.home"), ".jugg/hot_update").absolutePath,
            JuggHotUpdateManager.hotUpdateDir.absolutePath,
        )
    }

    @Test
    fun deploymentCache_shouldUseDotJuggDeployCache() {
        assertEquals(
            File(System.getProperty("user.home"), ".jugg/deploy_cache/.deploy_cache.db").absolutePath,
            JuggDeploymentService.deploymentCacheDbFile.absolutePath,
        )
    }
}
