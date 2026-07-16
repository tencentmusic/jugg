package com.sickworm.intellij.jugg.project.runtime

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class JuggGlobalPathManagerTest {

    @Test
    fun resourceFile_shouldStoreCopiedResourcesUnderResourcesDir() {
        val rootDir = File("/tmp/jugg-home/.jugg")

        assertEquals(
            File(rootDir, "resources/tools/darwin/aapt2"),
            JuggGlobalPathManager.resourceFile("/tools/darwin/aapt2", rootDir),
        )
    }
}
