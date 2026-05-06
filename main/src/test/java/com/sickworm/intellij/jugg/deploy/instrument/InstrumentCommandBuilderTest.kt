package com.sickworm.intellij.jugg.deploy.instrument

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InstrumentCommandBuilderTest {

    private val testApk = ApkInfo(
        files = listOf(ApkFileUnit("com.example.app.test", "", true, File("test.apk"))),
        applicationId = "com.example.app.test",
        instrumentationTargetPackage = "com.example.app",
        instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
    )

    @Test
    fun `no testClass produces bare instrument command`() {
        val spec = AndroidTestRunSpec(testClass = null, testMethod = null)
        val cmd = InstrumentCommandBuilder.build(spec, testApk)
        assertEquals(
            "am instrument -w -r com.example.app.test/androidx.test.runner.AndroidJUnitRunner",
            cmd
        )
    }

    @Test
    fun `testClass without method adds -e class fqn`() {
        val spec = AndroidTestRunSpec(testClass = "com.example.FooTest", testMethod = null)
        val cmd = InstrumentCommandBuilder.build(spec, testApk)
        assertTrue(cmd.contains("-e class com.example.FooTest"))
        assertFalse(cmd.contains("#"))
    }

    @Test
    fun `testClass with method adds -e class fqn#method`() {
        val spec = AndroidTestRunSpec(testClass = "com.example.FooTest", testMethod = "testBar")
        val cmd = InstrumentCommandBuilder.build(spec, testApk)
        assertTrue(cmd.contains("-e class com.example.FooTest#testBar"))
    }

    @Test
    fun `extraArgs are appended as -e k v pairs in stable order`() {
        val spec = AndroidTestRunSpec(
            testClass = null,
            testMethod = null,
            extraArgs = listOf("key1" to "value1", "key2" to "value2"),
        )
        val cmd = InstrumentCommandBuilder.build(spec, testApk)
        assertTrue(cmd.contains("-e key1 value1 -e key2 value2"))
    }

    @Test
    fun `extraArgs value with spaces is wrapped in single quotes`() {
        val spec = AndroidTestRunSpec(
            testClass = null,
            testMethod = null,
            extraArgs = listOf("filter" to "hello world"),
        )
        val cmd = InstrumentCommandBuilder.build(spec, testApk)
        assertTrue(cmd.contains("-e filter 'hello world'"))
    }

    @Test
    fun `extraArgs value with single quote is skipped`() {
        val spec = AndroidTestRunSpec(
            testClass = null,
            testMethod = null,
            extraArgs = listOf("bad" to "it's bad", "good" to "ok"),
        )
        val cmd = InstrumentCommandBuilder.build(spec, testApk)
        assertFalse("arg with single quote should be skipped", cmd.contains("bad"))
        assertTrue(cmd.contains("-e good ok"))
    }

    @Test
    fun `package argument is appended as native runner package filter`() {
        val spec = AndroidTestRunSpec(
            testClass = null,
            testMethod = null,
            extraArgs = listOf("package" to "com.example.tests"),
        )
        val cmd = InstrumentCommandBuilder.build(spec, testApk)
        assertTrue(cmd.contains("-e package com.example.tests"))
    }

    @Test
    fun `tests regex argument is appended as native runner regex filter`() {
        val spec = AndroidTestRunSpec(
            testClass = null,
            testMethod = null,
            extraArgs = listOf("tests_regex" to "Foo.*#bar"),
        )
        val cmd = InstrumentCommandBuilder.build(spec, testApk)
        assertTrue(cmd.contains("-e tests_regex Foo.*#bar"))
    }

    @Test
    fun `runnerOverride replaces manifest runner`() {
        val spec = AndroidTestRunSpec(
            testClass = null,
            testMethod = null,
            runnerOverride = "com.custom.TestRunner",
        )
        val cmd = InstrumentCommandBuilder.build(spec, testApk)
        assertTrue(cmd.contains("com.example.app.test/com.custom.TestRunner"))
        assertFalse(cmd.contains("AndroidJUnitRunner"))
    }

    @Test
    fun `missing runner falls back to AndroidJUnitRunner`() {
        val apkWithoutRunner = testApk.copy(instrumentationRunner = null)
        val spec = AndroidTestRunSpec(testClass = null, testMethod = null)
        val cmd = InstrumentCommandBuilder.build(spec, apkWithoutRunner)
        assertTrue(cmd.contains("androidx.test.runner.AndroidJUnitRunner"))
    }
}

class InstrumentCommandBuilderTestFilterTest {

    private val testApk = ApkInfo(
        files = listOf(ApkFileUnit("com.example.app.test", "", true, File("test.apk"))),
        applicationId = "com.example.app.test",
        instrumentationTargetPackage = "com.example.app",
        instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
    )

    @Test
    fun `single testFilter generates class method argument`() {
        val spec = AndroidTestRunSpec(
            testClass = null,
            testMethod = null,
            testFilters = listOf(TestFilter("com.example.FooTest", "testBar")),
        )

        val cmd = InstrumentCommandBuilder.build(spec, testApk)

        assertTrue(cmd.contains("-e class com.example.FooTest#testBar"))
    }

    @Test
    fun `multiple testFilters generate comma separated class argument`() {
        val spec = AndroidTestRunSpec(
            testClass = null,
            testMethod = null,
            testFilters = listOf(
                TestFilter("com.example.FooTest", "testBar"),
                TestFilter("com.example.OtherTest", "testBaz"),
            ),
        )

        val cmd = InstrumentCommandBuilder.build(spec, testApk)

        assertTrue(cmd.contains("-e class com.example.FooTest#testBar,com.example.OtherTest#testBaz"))
    }

    @Test
    fun `testFilters take precedence over legacy testClass and testMethod`() {
        val spec = AndroidTestRunSpec(
            testClass = "com.example.LegacyTest",
            testMethod = "legacy",
            testFilters = listOf(TestFilter("com.example.FooTest", "testBar")),
        )

        val cmd = InstrumentCommandBuilder.build(spec, testApk)

        assertTrue(cmd.contains("-e class com.example.FooTest#testBar"))
        assertFalse(cmd.contains("LegacyTest"))
    }
}
