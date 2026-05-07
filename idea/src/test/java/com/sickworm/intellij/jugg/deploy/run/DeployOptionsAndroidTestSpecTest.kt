package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class DeployOptionsAndroidTestSpecTest {

    @Test
    fun `deploy options default to ordinary app run when androidTest spec is absent`() {
        val options = DeployOptions(
            device = mock(IDevice::class.java),
            isLastDevice = true,
        )

        assertNull(options.androidTestRunSpec)
    }

    @Test
    fun `deploy options can carry androidTest run spec`() {
        val spec = AndroidTestRunSpec("com.example.FooTest", "testBar")
        val options = DeployOptions(
            device = mock(IDevice::class.java),
            isLastDevice = true,
            androidTestRunSpec = spec,
        )

        assertEquals(spec, options.androidTestRunSpec)
    }

    @Test
    fun `deploy options mark when running on multiple devices`() {
        val options = DeployOptions(
            device = mock(IDevice::class.java),
            isLastDevice = false,
            isMultipleDevices = true,
        )

        assertEquals(true, options.isMultipleDevices)
        assertEquals(false, options.isLastDevice)
    }
}
