package com.sickworm.intellij.jugg.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JuggAndroidTestRunSpecFactoryTest {

    @Test
    fun `build spec keeps class method runner and extra args`() {
        val options = JuggAndroidTestRunConfigurationOptions().apply {
            testClass = "com.example.FooTest"
            testMethod = "testBar"
            instrumentationRunner = "com.example.CustomRunner"
            extraArgs = "clearPackageData=true, size = medium"
        }

        val spec = JuggAndroidTestRunSpecFactory.fromOptions(options)

        assertEquals("com.example.FooTest", spec.testClass)
        assertEquals("testBar", spec.testMethod)
        assertEquals("com.example.CustomRunner", spec.runnerOverride)
        assertEquals(listOf("clearPackageData" to "true", "size" to "medium"), spec.extraArgs)
    }

    @Test
    fun `build spec normalizes blank option fields to null and ignores malformed args`() {
        val options = JuggAndroidTestRunConfigurationOptions().apply {
            testClass = ""
            testMethod = " "
            instrumentationRunner = ""
            extraArgs = "valid=yes,broken,noValue="
        }

        val spec = JuggAndroidTestRunSpecFactory.fromOptions(options)

        assertNull(spec.testClass)
        assertNull(spec.testMethod)
        assertNull(spec.runnerOverride)
        assertEquals(listOf("valid" to "yes"), spec.extraArgs)
    }
}
