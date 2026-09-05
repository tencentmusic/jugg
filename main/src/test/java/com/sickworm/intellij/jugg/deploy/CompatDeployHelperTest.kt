package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import java.io.File

class CompatDeployHelperTest {

    @Test
    fun `all ASUS devices automatically use compat deploy`() {
        val oldRecordJson = JuggSettings.deviceCompatRecordJson
        JuggSettings.deviceCompatRecordJson = ""
        try {
            val helper = CompatDeployHelper(Mockito.mock(Logger::class.java))
            val data = Mockito.mock(JuggDeployData::class.java)

            mapOf<String?, Boolean>(
                null to false,
                "samsung" to false,
                "asus" to true,
                " ASUS " to true,
            ).forEach { (manufacturer, expected) ->
                assertEquals(manufacturer, expected, helper.isEnableCompatDeploy(FakeDevice(manufacturer), data))
            }
        } finally {
            JuggSettings.deviceCompatRecordJson = oldRecordJson
        }
    }

    private class FakeDevice(
        private val manufacturer: String?,
    ) : IDeviceAdb {
        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String = ""
        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = ""
        override fun getProperty(name: String): String? {
            return if (name == "ro.product.manufacturer") manufacturer else null
        }
    }
}
