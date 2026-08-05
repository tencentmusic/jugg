package com.sickworm.intellij.jugg.ide

import com.intellij.execution.configurations.RuntimeConfigurationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JuggAndroidTestRunSpecFactoryTest {

    @Test
    fun `build spec keeps class method runner and extra args`() {
        val options = JuggAndroidTestRunConfigurationOptions().apply {
            testScope = AndroidTestScope.METHOD
            sourcePath = "library1/src/androidTest/kotlin/com/example/FooTest.kt"
            testClass = "com.example.FooTest"
            testMethod = "testBar"
            instrumentationRunner = "com.example.CustomRunner"
            extraArgs = "clearPackageData=true, size = medium"
        }

        val spec = JuggAndroidTestRunSpecFactory.fromOptions(options)

        assertEquals("com.example.FooTest", spec.testClass)
        assertEquals("testBar", spec.testMethod)
        assertEquals("library1/src/androidTest/kotlin/com/example/FooTest.kt", spec.sourcePath)
        assertEquals("com.example.CustomRunner", spec.runnerOverride)
        assertEquals(listOf("clearPackageData" to "true", "size" to "medium"), spec.extraArgs)
    }

    @Test
    fun `build spec normalizes blank optional fields to null and ignores malformed args`() {
        val options = JuggAndroidTestRunConfigurationOptions().apply {
            testClass = "com.example.FooTest"
            testMethod = " "
            instrumentationRunner = ""
            extraArgs = "valid=yes,broken,noValue="
        }

        val spec = JuggAndroidTestRunSpecFactory.fromOptions(options)

        assertEquals("com.example.FooTest", spec.testClass)
        assertNull(spec.testMethod)
        assertNull(spec.runnerOverride)
        assertEquals(listOf("valid" to "yes"), spec.extraArgs)
    }

    @Test
    fun `class scope maps only test class`() {
        val options = JuggAndroidTestRunConfigurationOptions().apply {
            testScope = AndroidTestScope.CLASS
            testClass = "com.example.FooTest"
            testMethod = "ignored"
        }

        val spec = JuggAndroidTestRunSpecFactory.fromOptions(options)

        assertEquals("com.example.FooTest", spec.testClass)
        assertNull(spec.testMethod)
    }

    @Test
    fun `method scope maps class and method`() {
        val options = JuggAndroidTestRunConfigurationOptions().apply {
            testScope = AndroidTestScope.METHOD
            testClass = "com.example.FooTest"
            testMethod = "testBar"
        }

        val spec = JuggAndroidTestRunSpecFactory.fromOptions(options)

        assertEquals("com.example.FooTest", spec.testClass)
        assertEquals("testBar", spec.testMethod)
    }

    @Test
    fun `required fields are validated only for current scope`() {
        assertThrows(RuntimeConfigurationError::class.java) {
            JuggAndroidTestRunSpecFactory.fromOptions(JuggAndroidTestRunConfigurationOptions().apply {
                testScope = AndroidTestScope.CLASS
                testClass = ""
            })
        }
        assertThrows(RuntimeConfigurationError::class.java) {
            JuggAndroidTestRunSpecFactory.fromOptions(JuggAndroidTestRunConfigurationOptions().apply {
                testScope = AndroidTestScope.METHOD
                testClass = ""
                testMethod = "testBar"
            })
        }
        assertThrows(RuntimeConfigurationError::class.java) {
            JuggAndroidTestRunSpecFactory.fromOptions(JuggAndroidTestRunConfigurationOptions().apply {
                testScope = AndroidTestScope.METHOD
                testClass = "com.example.FooTest"
                testMethod = ""
            })
        }
    }

    @Test
    fun `unknown stored scope falls back to class`() {
        val options = JuggAndroidTestRunConfigurationOptions().apply {
            testScopeId = "REMOVED_SCOPE"
            testClass = "com.example.FooTest"
        }

        val spec = JuggAndroidTestRunSpecFactory.fromOptions(options)

        assertEquals(AndroidTestScope.CLASS, options.testScope)
        assertEquals("com.example.FooTest", spec.testClass)
    }

    @Test
    fun `selected app run config name defaults to blank`() {
        val options = JuggAndroidTestRunConfigurationOptions()

        assertNull(options.appRunConfigurationName)
    }

    @Test
    fun `app run config selector keeps selected name or falls back to first available`() {
        val availableNames = listOf("appDebug", "appRelease")

        assertEquals(
            "appRelease",
            JuggAndroidTestAppRunConfigurationSelector.selectName("appRelease", availableNames),
        )
        assertEquals(
            "appDebug",
            JuggAndroidTestAppRunConfigurationSelector.selectName(null, availableNames),
        )
    }

    @Test
    fun `app run config selector returns null when selected config is missing`() {
        assertNull(
            JuggAndroidTestAppRunConfigurationSelector.selectName(
                "deletedConfig",
                listOf("appDebug"),
            )
        )
    }

}
