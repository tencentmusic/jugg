package com.sickworm.intellij.jugg.ide

import com.intellij.execution.Executor
import com.intellij.execution.ExecutionResult
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.IconManager
import com.sickworm.intellij.jugg.loader.JuggInitializer
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

// ──────────────────────────────────────────────────────────────────────────────
// Options bean
// ──────────────────────────────────────────────────────────────────────────────

/**
 * JuggAndroidTestRunConfigurationOptions persists the test-launch parameters.
 *
 * Property order must never change (append only) for backwards-compatible serialization.
 */
class JuggAndroidTestRunConfigurationOptions : RunConfigurationOptions() {
    /** Fully-qualified test class name; null/empty = run all tests in the package. */
    var testClass by string()
    /** Test method name; only effective when [testClass] is set. */
    var testMethod by string()
    /** Fully-qualified instrumentation runner FQN; overrides the manifest value when set. */
    var instrumentationRunner by string()
    /** Extra `-e key value` pairs as "k1=v1,k2=v2". */
    var extraArgs by string()

    // new options must add to the end
}

// ──────────────────────────────────────────────────────────────────────────────
// RunConfiguration
// ──────────────────────────────────────────────────────────────────────────────

/**
 * JuggAndroidTestRunConfiguration is the independent ConfigurationType for running
 * Android instrumentation tests via Jugg. Created from the @Test gutter icon.
 */
class JuggAndroidTestRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<JuggAndroidTestRunConfigurationOptions>(project, factory, name) {

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        JuggAndroidTestSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        JuggAndroidTestRunProfileState(project, state!!)
}

// ──────────────────────────────────────────────────────────────────────────────
// ConfigurationType
// ──────────────────────────────────────────────────────────────────────────────

/**
 * JuggAndroidTestConfigurationType registers the Jugg Android Test configuration
 * alongside the existing JuggConfigurationType.
 */
class JuggAndroidTestConfigurationType : ConfigurationTypeBase(
    "jugg.JuggAndroidTestConfigurationType",
    "Jugg Android Test",
    "Run Android instrumentation tests with Jugg",
    NotNullLazyValue.createValue {
        IconManager.getInstance().getIcon("/res/icons/run_configuration.svg", JuggInitializer::class.java)
    }
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId() = "jugg.JuggAndroidTestConfigurationFactory"
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                JuggAndroidTestRunConfiguration(project, this, "Jugg Android Test")
            override fun getOptionsClass() = JuggAndroidTestRunConfigurationOptions::class.java
        })
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// SettingsEditor (minimal UI panel)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * JuggAndroidTestSettingsEditor provides a minimal form for the test run configuration.
 */
class JuggAndroidTestSettingsEditor : SettingsEditor<JuggAndroidTestRunConfiguration>() {

    private val testClassField = JTextField(40)
    private val testMethodField = JTextField(40)
    private val runnerField = JTextField(40)
    private val extraArgsField = JTextField(40)

    override fun createEditor(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.add(JLabel("Test class (empty = run all)"))
        panel.add(testClassField)
        panel.add(JLabel("Test method (empty = run all in class)"))
        panel.add(testMethodField)
        panel.add(JLabel("Instrumentation runner (leave empty to use manifest value)"))
        panel.add(runnerField)
        panel.add(JLabel("Extra args (k1=v1,k2=v2)"))
        panel.add(extraArgsField)
        return panel
    }

    override fun resetEditorFrom(config: JuggAndroidTestRunConfiguration) {
        val opts = config.state ?: return
        testClassField.text = opts.testClass ?: ""
        testMethodField.text = opts.testMethod ?: ""
        runnerField.text = opts.instrumentationRunner ?: ""
        extraArgsField.text = opts.extraArgs ?: ""
    }

    override fun applyEditorTo(config: JuggAndroidTestRunConfiguration) {
        val opts = config.state ?: return
        opts.testClass = testClassField.text.takeIf { it.isNotBlank() }
        opts.testMethod = testMethodField.text.takeIf { it.isNotBlank() }
        opts.instrumentationRunner = runnerField.text.takeIf { it.isNotBlank() }
        opts.extraArgs = extraArgsField.text.takeIf { it.isNotBlank() }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// RunProfileState (execution entry point)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * JuggAndroidTestRunProfileState is the execution entry point for Jugg instrumentation tests.
 *
 * Phase 1: validates that enableAndroidTest is on and the last build target was ANDROID_TEST,
 * then delegates to JuggManager.runTask with the resolved AndroidTestRunSpec.
 */
class JuggAndroidTestRunProfileState(
    private val project: Project,
    private val options: JuggAndroidTestRunConfigurationOptions,
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        // Validation and actual launch is handled by JuggManager integration (wired in Phase 1 plan).
        // For now this is a placeholder that will be completed when JuggManager.runTask overload
        // accepting AndroidTestRunSpec is added (§7.8 / §8.8).
        return DefaultExecutionResult()
    }
}
