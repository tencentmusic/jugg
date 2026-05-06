package com.sickworm.intellij.jugg.ide

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.TestFrameworkRunningModel
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.module.Module
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.TestFilter

/**
 * Reruns failed Jugg androidTest leaf nodes by converting SM runner nodes back to instrumentation filters.
 */
class JuggAndroidTestRerunFailedTestsAction(
    consoleView: ConsoleView,
    consoleProperties: TestConsoleProperties,
    private val originalSpec: AndroidTestRunSpec,
) : AbstractRerunFailedTestsAction(consoleView) {

    init {
        init(consoleProperties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? JuggAndroidTestRunConfiguration ?: return null
        val filters = collectTestFilters(getFailedTests(configuration.project).map { SmProxyRerunFailedTestNode(it) })
        if (filters.isEmpty()) return null
        val rerunSpec = createRerunSpec(originalSpec, filters)
        return object : MyRunProfile(configuration) {
            override fun getModules(): Array<Module> = Module.EMPTY_ARRAY

            override fun getState(executor: com.intellij.execution.Executor, env: ExecutionEnvironment): JuggAndroidTestRunProfileState {
                return JuggAndroidTestRunProfileState(
                    configuration.project,
                    configuration.state!!,
                    explicitSpec = rerunSpec,
                    runProfile = this,
                    stateExecutor = executor,
                )
            }
        }
    }

    companion object {
        fun collectTestFilters(nodes: List<RerunFailedTestNode>): List<TestFilter> {
            return nodes.asSequence()
                .filter { it.leaf && it.failed && !it.ignored }
                .mapNotNull { parseLocation(it.locationHint) ?: parseName(it.name) }
                .distinct()
                .toList()
        }

        fun createRerunSpec(original: AndroidTestRunSpec, filters: List<TestFilter>): AndroidTestRunSpec {
            return original.copy(testClass = null, testMethod = null, testFilters = filters)
        }

        private fun parseLocation(locationHint: String?): TestFilter? {
            val raw = locationHint ?: return null
            val path = raw.removePrefix("java:test://")
            if (path == raw) return null
            val className = path.substringBefore("/").takeIf { it.isNotBlank() } ?: return null
            val methodName = path.substringAfter("/", "").takeIf { it.isNotBlank() }
            return TestFilter(className, methodName)
        }

        private fun parseName(name: String): TestFilter? {
            val className = name.substringBeforeLast('.', missingDelimiterValue = "").takeIf { it.isNotBlank() } ?: return null
            val methodName = name.substringAfterLast('.').takeIf { it.isNotBlank() }
            return TestFilter(className, methodName)
        }
    }
}

interface RerunFailedTestNode {
    val locationHint: String?
    val name: String
    val failed: Boolean
    val ignored: Boolean
    val leaf: Boolean
}

data class SimpleRerunFailedTestNode(
    override val locationHint: String?,
    override val name: String,
    override val failed: Boolean,
    override val ignored: Boolean,
    override val leaf: Boolean,
) : RerunFailedTestNode

private class SmProxyRerunFailedTestNode(private val proxy: AbstractTestProxy) : RerunFailedTestNode {
    override val locationHint: String? get() = proxy.locationUrl
    override val name: String get() = proxy.name
    override val failed: Boolean get() = proxy.isDefect
    override val ignored: Boolean get() = proxy.isIgnored
    override val leaf: Boolean get() = proxy.isLeaf
}
