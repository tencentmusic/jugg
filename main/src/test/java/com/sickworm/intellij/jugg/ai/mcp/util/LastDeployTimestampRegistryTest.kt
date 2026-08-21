package com.sickworm.intellij.jugg.ai.mcp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastDeployTimestampRegistryTest {

    @Test
    fun testDeviceTimestampsAreIsolated() {
        val registry = LastDeployTimestampRegistry()
        registry.setTimestamp("/project", "device-1-time", "device-1")
        registry.setTimestamp("/project", "device-2-time", "device-2")

        assertEquals("device-1-time", registry.getTimestamp("/project", "device-1"))
        assertEquals("device-2-time", registry.getTimestamp("/project", "device-2"))
        assertNull(registry.getTimestamp("/project"))
    }

    @Test
    fun testDeviceLookupFallsBackToLegacyProjectTimestamp() {
        val registry = LastDeployTimestampRegistry()
        registry.setTimestamp("/project", "legacy-time")

        assertEquals("legacy-time", registry.getTimestamp("/project", "device-1"))
    }
}
