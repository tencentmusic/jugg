package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Assert.assertNotNull
import org.junit.Test

class CmdExecutorBinaryCompatibilityTest {

    @Test
    fun `legacy three-argument invoke signature should remain available`() {
        val method = CmdExecutor::class.java.getDeclaredMethod(
            "invoke",
            ISshCommand::class.java,
            List::class.java,
            List::class.java,
        )

        assertNotNull(method)
    }
}
