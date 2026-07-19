package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.loader.JuggHotUpdateManager
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import org.junit.Test
import kotlin.test.assertEquals

class JuggGlobalStoragePathTest {

    @Test
    fun hotUpdate_shouldUseConfiguredGlobalRoot() {
        assertEquals(
            JuggGlobalPathManager.hotUpdateDir.absolutePath,
            JuggHotUpdateManager.hotUpdateDir.absolutePath,
        )
    }
}
