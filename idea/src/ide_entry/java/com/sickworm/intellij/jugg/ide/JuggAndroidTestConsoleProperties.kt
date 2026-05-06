package com.sickworm.intellij.jugg.ide

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.testframework.JavaTestLocator
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project

/**
 * Console properties for Jugg androidTest SM runner integration.
 */
class JuggAndroidTestConsoleProperties(
    project: Project,
    runProfile: RunProfile,
    executor: Executor,
    private val originalSpec: com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec,
) : SMTRunnerConsoleProperties(project, runProfile, TEST_FRAMEWORK_NAME, executor) {

    override fun getTestLocator(): SMTestLocator = testLocator()

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction? {
        return JuggAndroidTestRerunFailedTestsAction(consoleView, this, originalSpec)
    }

    companion object {
        const val TEST_FRAMEWORK_NAME = "JuggAndroidTest"

        fun testLocator(): SMTestLocator = JavaTestLocator.INSTANCE
    }
}
