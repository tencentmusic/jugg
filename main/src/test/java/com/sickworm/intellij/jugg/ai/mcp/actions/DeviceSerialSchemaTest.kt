package com.sickworm.intellij.jugg.ai.mcp.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeviceSerialSchemaTest {

    @Test
    fun testDeviceTargetToolsExposeSerial() {
        val actions = listOf(
            CompileAndDeployMcpToolAction(),
            ForceGradleCompileMcpToolAction(),
            CleanReinstallApkMcpToolAction(),
            RestartAppMcpToolAction(),
            InstrumentMcpToolAction(),
            GetStatusMcpToolAction(),
            DeviceListMcpToolAction(),
            LayoutDumpMcpToolAction(),
            UiFindMcpToolAction(),
            EvalViewMcpToolAction(),
            TapMcpToolAction(),
            ActivityStackMcpToolAction(),
            WaitLogsMcpToolAction(),
        )

        actions.forEach { action ->
            assertNotNull("${action.toolName} should expose serial", action.definition.inputSchema.properties["serial"])
        }
    }

    @Test
    fun testCompileDoesNotExposeSerial() {
        assertFalse(CompileOnlyMcpToolAction().definition.inputSchema.properties.containsKey("serial"))
    }
}
