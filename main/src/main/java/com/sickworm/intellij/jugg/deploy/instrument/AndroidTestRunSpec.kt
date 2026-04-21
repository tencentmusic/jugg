package com.sickworm.intellij.jugg.deploy.instrument

/**
 * AndroidTestRunSpec holds the test targeting parameters for one instrumentation run.
 *
 * @param testClass  Fully-qualified test class name; null means "run all tests in the package".
 * @param testMethod Test method name; only effective when [testClass] is non-null.
 * @param extraArgs  Additional `-e key value` pairs forwarded to am instrument verbatim.
 * @param runnerOverride When non-null, overrides the runner read from the test APK manifest.
 */
data class AndroidTestRunSpec(
    val testClass: String?,
    val testMethod: String?,
    val extraArgs: List<Pair<String, String>> = emptyList(),
    val runnerOverride: String? = null,
)
