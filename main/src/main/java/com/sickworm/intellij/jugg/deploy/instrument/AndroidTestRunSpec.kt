package com.sickworm.intellij.jugg.deploy.instrument

/**
 * AndroidTestRunSpec holds the test targeting parameters for one instrumentation run.
 *
 * [testFilters] is used by rerun-failed flows and takes precedence over [testClass]/[testMethod].
 * [extraArgs] are additional `-e key value` pairs forwarded to am instrument verbatim.
 */
data class AndroidTestRunSpec(
    val testClass: String?,
    val testMethod: String?,
    val testFilters: List<TestFilter> = emptyList(),
    val extraArgs: List<Pair<String, String>> = emptyList(),
    val runnerOverride: String? = null,
    val sourcePath: String? = null,
)

/** Describes one instrumentation class or class#method filter. */
data class TestFilter(
    val className: String,
    val methodName: String? = null,
) {
    fun toClassArgument(): String = if (methodName.isNullOrBlank()) className else "$className#$methodName"
}
