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
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
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
        JuggAndroidTestRunProfileState(project, state!!, runProfile = this)
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

    companion object {
        @JvmStatic
        fun getInstance(): JuggAndroidTestConfigurationType {
            return ConfigurationTypeUtil.findConfigurationType(JuggAndroidTestConfigurationType::class.java)
        }
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
// RunSpec Factory
// ──────────────────────────────────────────────────────────────────────────────

object JuggAndroidTestRunSpecFactory {
    fun fromOptions(options: JuggAndroidTestRunConfigurationOptions): AndroidTestRunSpec {
        return AndroidTestRunSpec(
            testClass = options.testClass.normalizeBlank(),
            testMethod = options.testMethod.normalizeBlank(),
            extraArgs = parseExtraArgs(options.extraArgs),
            runnerOverride = options.instrumentationRunner.normalizeBlank(),
        )
    }

    private fun parseExtraArgs(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
            .map {
                val key = it.substringBefore("=").trim()
                val value = it.substringAfter("=").trim()
                key to value
            }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
    }

    private fun String?.normalizeBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
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
    private val explicitSpec: AndroidTestRunSpec? = null,
    private val runProfile: RunProfile? = null,
    private val stateExecutor: Executor? = null,
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val juggManager = JuggInitializer.getManager(project) ?: return DefaultExecutionResult()
        val appRunConfigOptions = findAppRunConfigurationOptions(project) ?: return DefaultExecutionResult()
        val spec = explicitSpec ?: JuggAndroidTestRunSpecFactory.fromOptions(options)
        val actualExecutor = executor ?: stateExecutor
        return juggManager.runTask(appRunConfigOptions, actualExecutor, runProfile, spec)
    }

    private fun findAppRunConfigurationOptions(project: Project): JuggRunConfigurationOptions? {
        return com.intellij.execution.RunManager.getInstance(project)
            .getConfigurationSettingsList(JuggConfigurationType::class.java)
            .firstOrNull()
            ?.configuration
            ?.let { it as? JuggRunConfiguration }
            ?.state
    }
}
