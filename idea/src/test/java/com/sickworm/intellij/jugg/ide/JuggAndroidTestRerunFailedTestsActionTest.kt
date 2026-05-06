package com.sickworm.intellij.jugg.ide

import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.TestFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class JuggAndroidTestRerunFailedTestsActionTest {

    @Test
    fun `failed leaf locations are converted to test filters`() {
        val tests = listOf(
            SimpleRerunFailedTestNode("java:test://com.example.FooTest/testBar", "testBar", failed = true, ignored = false, leaf = true),
            SimpleRerunFailedTestNode("java:test://com.example.OtherTest/testBaz", "testBaz", failed = true, ignored = false, leaf = true),
        )

        val filters = JuggAndroidTestRerunFailedTestsAction.collectTestFilters(tests)

        assertEquals(
            listOf(
                TestFilter("com.example.FooTest", "testBar"),
                TestFilter("com.example.OtherTest", "testBaz"),
            ),
            filters,
        )
    }

    @Test
    fun `ignored and non leaf tests are not rerun`() {
        val tests = listOf(
            SimpleRerunFailedTestNode("java:test://com.example.FooTest/testBar", "testBar", failed = true, ignored = true, leaf = true),
            SimpleRerunFailedTestNode("java:suite://com.example.FooTest", "com.example.FooTest", failed = true, ignored = false, leaf = false),
            SimpleRerunFailedTestNode("java:test://com.example.OtherTest/testBaz", "testBaz", failed = true, ignored = false, leaf = true),
        )

        val filters = JuggAndroidTestRerunFailedTestsAction.collectTestFilters(tests)

        assertEquals(listOf(TestFilter("com.example.OtherTest", "testBaz")), filters)
    }

    @Test
    fun `rerun spec preserves runner and extra args`() {
        val original = AndroidTestRunSpec(
            testClass = "com.example.FooTest",
            testMethod = "all",
            extraArgs = listOf("clearPackageData" to "true"),
            runnerOverride = "com.example.CustomRunner",
        )
        val filters = listOf(TestFilter("com.example.FooTest", "testBar"))

        val rerunSpec = JuggAndroidTestRerunFailedTestsAction.createRerunSpec(original, filters)

        assertEquals(filters, rerunSpec.testFilters)
        assertEquals(listOf("clearPackageData" to "true"), rerunSpec.extraArgs)
        assertEquals("com.example.CustomRunner", rerunSpec.runnerOverride)
    }
}
