package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.loader.JuggHotUpdateManager
import com.sickworm.intellij.jugg.project.JuggGlobalPathManager
import org.junit.Test
import kotlin.test.assertEquals

class JuggGlobalStoragePathTest {

    @Test
    fun hotUpdate_shouldUseGlobalJuggRoot() {
        assertEquals(
            JuggGlobalPathManager.hotUpdateDir.absolutePath,
            JuggHotUpdateManager.hotUpdateDir.absolutePath,
        )
    }

    @Test
    fun deploymentCache_shouldUseGlobalJuggRoot() {
        assertEquals(
            JuggGlobalPathManager.deployCacheDbFile.absolutePath,
            JuggDeploymentService.deploymentCacheDbFile.absolutePath,
        )
    }
}
