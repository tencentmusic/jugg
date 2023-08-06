package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeployDataGeneratorTest {

    @Test
    fun testOverlayContents() {
        val generator = DeployDataGenerator(logger)
        generator.init(projectInfo.apkInfos)
        val overlayDeployItem = DeployItem("test_overlay", CompileOutput.Type.Overlay, 0, byteArrayOf())
        val data = generator.buildDeployData(listOf(overlayDeployItem), false)
        assertEquals(475, data.overlays.size)
        assertTrue(data.isFullOverlays)
        logger.debug(data.toString())
    }
}