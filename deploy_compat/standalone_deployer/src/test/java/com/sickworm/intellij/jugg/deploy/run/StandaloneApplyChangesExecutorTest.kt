package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deployer.Version
import com.android.utils.ILogger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneApplyChangesExecutorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `create install session uses matching Quail protocol version`() {
        val executor = StandaloneApplyChangesExecutor()
        val session = executor.createInstallSession(
            temporaryFolder.newFolder("installer").path,
            mock<IDevice>(),
            mock<ILogger>(),
            { true },
            {},
        )

        assertEquals(Version.hash(), session.installerVersion)
        assertTrue(session.rawInstaller.javaClass.name == "com.android.tools.deployer.AdbInstaller")
    }
}
