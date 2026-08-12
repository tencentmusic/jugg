package com.sickworm.intellij.jugg.ide.logic

import kotlin.test.Test
import kotlin.test.assertContains

class StandaloneBundleInstallServiceTest {

    @Test
    fun `installer failure includes process output`() {
        val message = StandaloneBundleInstallService.installerFailureMessage(
            exitCode = 1,
            output = "Standalone runtime build-1 would downgrade the active runtime",
        )

        assertContains(message, "Standalone Bundle installer exited with 1")
        assertContains(message, "would downgrade the active runtime")
    }
}
