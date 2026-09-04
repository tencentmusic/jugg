package com.sickworm.intellij.jugg.deploy.run

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

class AdbLogWrapperTest {

    @Test
    fun `records install storage failure from ddmlib output`() {
        val logger = AdbLogWrapper(mock(Logger::class.java))

        logger.info(
            "Installation Failure: 'package install-create -r -t --full --dont-kill -S 273710793' returns error '%s'",
            """
            Unknown failure: Exception occurred while executing 'install-create':
            android.os.ParcelableException: java.io.IOException: Requested internal only, but not enough space
            Caused by: java.io.IOException: Requested internal only, but not enough space
            """.trimIndent(),
        )

        assertEquals("Requested internal only, but not enough space", logger.realErrorMessage)
    }

    @Test
    fun `records device offline from installation failure line`() {
        val logger = AdbLogWrapper(mock(Logger::class.java))

        logger.verbose("Installation Failure: device offline")

        assertEquals("device offline", logger.realErrorMessage)
    }

    @Test
    fun `records non debuggable package from deployer output`() {
        val logger = AdbLogWrapper(mock(Logger::class.java))

        logger.error(null, "Could not get package user id: run-as: package not debuggable: %s", "com.example.test")

        assertEquals(
            "Could not get package user id: run-as: package not debuggable: com.example.test",
            logger.realErrorMessage,
        )
    }
}
