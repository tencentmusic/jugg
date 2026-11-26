package com.sickworm.intellij.jugg.ide.logic

import org.junit.Assert.assertFalse
import org.junit.Test

/** Guards published IDEA bytecode from statically linking Marketplace-forbidden plugin APIs. */
class MarketplaceCompatibilityArchitectureTest {

    @Test
    fun `standalone installer does not link internal plugin manager lookup`() {
        val bytecode = StandaloneBundleInstallService::class.java
            .getResourceAsStream("StandaloneBundleInstallService.class")
            ?.use { it.readBytes().toString(Charsets.ISO_8859_1) }
            ?: error("StandaloneBundleInstallService bytecode is missing")

        assertFalse(bytecode.contains("com/intellij/ide/plugins/PluginManagerCore"))
    }
}
