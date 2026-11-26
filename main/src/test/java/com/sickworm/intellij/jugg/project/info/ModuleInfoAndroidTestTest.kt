package com.sickworm.intellij.jugg.project.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoAndroidTestTest {

    private fun moduleInfo(instrumentationTargetPackage: String? = null) =
        ModuleInfo.virtualModule.copy(
            instrumentationTargetPackage = instrumentationTargetPackage,
        )

    @Test
    fun `isAndroidTestModule is false when instrumentationTargetPackage is null`() {
        assertFalse(moduleInfo(null).isAndroidTestModule)
    }

    @Test
    fun `isAndroidTestModule is true when instrumentationTargetPackage is set`() {
        assertTrue(moduleInfo("com.example.app").isAndroidTestModule)
    }

    @Test
    fun `instrumentationTargetPackage stores the app package name`() {
        val module = moduleInfo("com.example.app")
        assertEquals("com.example.app", module.instrumentationTargetPackage)
    }
}
