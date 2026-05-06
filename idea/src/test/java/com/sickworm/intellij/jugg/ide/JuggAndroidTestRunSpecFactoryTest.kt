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

    @Test
    fun `manual template defaults to all in module`() {
        val options = JuggAndroidTestRunConfigurationOptions()

        val spec = JuggAndroidTestRunSpecFactory.fromOptions(options)

        assertEquals(AndroidTestScope.ALL_IN_MODULE, options.testScope)
        assertNull(spec.testClass)
        assertNull(spec.testMethod)
        assertEquals(emptyList<Pair<String, String>>(), spec.extraArgs)
    }

    @Test
    fun `all in module regex maps to tests regex argument`() {
        val options = JuggAndroidTestRunConfigurationOptions().apply {
            testScope = AndroidTestScope.ALL_IN_MODULE
            regex = "Foo.*#bar"
            packageName = ""
            testClass = ""
            testMethod = ""
        }

        val spec = JuggAndroidTestRunSpecFactory.fromOptions(options)

        assertNull(spec.testClass)
        assertNull(spec.testMethod)
        assertEquals(listOf("tests_regex" to "Foo.*#bar"), spec.extraArgs)
    }

    @Test
    fun `all in package maps package argument and ignores class fields`() {
        val options = JuggAndroidTestRunConfigurationOptions().apply {
            testScope = AndroidTestScope.ALL_IN_PACKAGE
            packageName = " com.example.tests "
            testClass = ""
            testMethod = ""
        }

        val spec = JuggAndroidTestRunSpecFactory.fromOptions(options)

        assertNull(spec.testClass)
        assertNull(spec.testMethod)
        assertEquals(listOf("package" to "com.example.tests"), spec.extraArgs)
    }

    @Test
    fun `class scope maps only test class`() {
        val options = JuggAndroidTestRunConfigurationOptions().apply {
            testScope = AndroidTestScope.CLASS
            testClass = "com.example.FooTest"
            testMethod = "ignored"
            packageName = ""
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
        JuggAndroidTestRunSpecFactory.fromOptions(JuggAndroidTestRunConfigurationOptions().apply {
            testScope = AndroidTestScope.ALL_IN_MODULE
            packageName = ""
            testClass = ""
            testMethod = ""
        })

        assertThrows(RuntimeConfigurationError::class.java) {
            JuggAndroidTestRunSpecFactory.fromOptions(JuggAndroidTestRunConfigurationOptions().apply {
                testScope = AndroidTestScope.ALL_IN_PACKAGE
                packageName = ""
            })
        }
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
    fun `unknown stored scope falls back to all in module`() {
        val options = JuggAndroidTestRunConfigurationOptions().apply {
            testScopeId = "REMOVED_SCOPE"
            regex = "Foo.*"
        }

        val spec = JuggAndroidTestRunSpecFactory.fromOptions(options)

        assertEquals(AndroidTestScope.ALL_IN_MODULE, options.testScope)
        assertEquals(listOf("tests_regex" to "Foo.*"), spec.extraArgs)
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
