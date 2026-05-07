package com.sickworm.intellij.jugg.ide

import com.intellij.execution.Executor
import com.intellij.execution.ExecutionResult
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.IconManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.Box
import javax.swing.ButtonGroup
import javax.swing.JRadioButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import java.awt.FlowLayout

// ──────────────────────────────────────────────────────────────────────────────
// Options bean
// ──────────────────────────────────────────────────────────────────────────────

enum class AndroidTestScope { ALL_IN_MODULE, ALL_IN_PACKAGE, CLASS, METHOD }

/** Stores Jugg Android instrumentation test launch parameters. */
class JuggAndroidTestRunConfigurationOptions : RunConfigurationOptions() {
    /** Fully-qualified test class name; null/empty = run all tests in the package. */
    var testClass by string()
    /** Test method name; only effective when [testClass] is set. */
    var testMethod by string()
    /** Fully-qualified instrumentation runner FQN; overrides the manifest value when set. */
    var instrumentationRunner by string()
    /** Extra `-e key value` pairs as "k1=v1,k2=v2". */
    var extraArgs by string()

    /** Stored enum name used by the IntelliJ options delegate. */
    internal var testScopeId by string(AndroidTestScope.ALL_IN_MODULE.name)
    var testScope: AndroidTestScope
        get() = AndroidTestScope.values().firstOrNull { it.name == testScopeId } ?: AndroidTestScope.ALL_IN_MODULE
        set(value) { testScopeId = value.name }
    /** Regex passed to AndroidJUnitRunner as tests_regex in All in Module scope. */
    var regex by string()
    /** Test package passed to AndroidJUnitRunner as package in All in Package scope. */
    var packageName by string()
    /** Name of the Jugg app run configuration used before instrumentation. */
    var appRunConfigurationName by string()

}

internal object JuggAndroidTestAppRunConfigurationSelector {
    fun selectName(storedName: String?, availableNames: List<String>): String? {
        val selectedName = storedName.normalizeBlank()
        return if (selectedName == null) availableNames.firstOrNull() else selectedName.takeIf { it in availableNames }
    }

    fun selectedName(project: Project, storedName: String?): String? {
        val names = appSettings(project).map { it.name }
        return selectName(storedName, names)
    }

    fun selectedSettings(project: Project, storedName: String?): RunnerAndConfigurationSettings? {
        val settings = appSettings(project)
        val selectedName = storedName.normalizeBlank()
        return if (selectedName == null) settings.firstOrNull() else settings.firstOrNull { it.name == selectedName }
    }

    fun firstEnabledAndroidTestSettings(project: Project): RunnerAndConfigurationSettings? {
        return appSettings(project).firstOrNull { it.juggOptions()?.enableAndroidTest == true }
    }

    private fun appSettings(project: Project): List<RunnerAndConfigurationSettings> {
        return RunManager.getInstance(project).getConfigurationSettingsList(JuggConfigurationType::class.java)
    }

    fun RunnerAndConfigurationSettings.juggOptions(): JuggRunConfigurationOptions? {
        return (configuration as? JuggRunConfiguration)?.state
    }

    private fun String?.normalizeBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
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

    override fun <T : Any?> getUserData(key: Key<T>): T? {
        ensureSetAllowSelectDevice()
        return super.getUserData(key)
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        JuggAndroidTestSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        JuggAndroidTestRunProfileState(project, state!!, runProfile = this)

    override fun checkConfiguration() {
        JuggAndroidTestRunSpecFactory.validateOptions(state ?: return)
    }

    private var lastSetObj: Any? = null

    private fun ensureSetAllowSelectDevice() {
        try {
            if (lastSetObj !== userMap) {
                AsDeployerCompat.setAllowSelectDevice(this)
                lastSetObj = userMap
            }
        } catch (e: Throwable) {
            Logger.getInstance("JuggAndroidTestRunConfiguration").warn("ensureSetAllowSelectDevice", e)
        }
    }
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

    private val allInModuleButton = JRadioButton("All in Module")
    private val allInPackageButton = JRadioButton("All in Package")
    private val classButton = JRadioButton("Class")
    private val methodButton = JRadioButton("Method")
    private val regexField = JTextField(40)
    private val packageField = JTextField(40)
    private val testClassField = JTextField(40)
    private val testMethodField = JTextField(40)
    private val scopeFieldsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val moduleField = JTextField(40).apply { isEditable = false }
    private val runnerField = JTextField(40)
    private val extraArgsField = JTextField(40).apply { toolTipText = "k1=v1,k2=v2" }

    override fun createEditor(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(12)
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        ButtonGroup().apply {
            add(allInModuleButton)
            add(allInPackageButton)
            add(classButton)
            add(methodButton)
        }
        listOf(allInModuleButton, allInPackageButton, classButton, methodButton).forEach { button ->
            button.addActionListener { updateScopeFields() }
            buttonRow.add(button)
        }
        panel.add(createRowPanel("Test:", buttonRow))
        panel.add(scopeFieldsPanel)
        panel.add(createRowPanel("Extra args:", extraArgsField))
        updateScopeFields()
        return panel
    }

    override fun resetEditorFrom(config: JuggAndroidTestRunConfiguration) {
        val opts = config.state ?: return
        if (opts.appRunConfigurationName.isNullOrBlank()) {
            opts.appRunConfigurationName = JuggAndroidTestAppRunConfigurationSelector
                .selectedName(config.project, opts.appRunConfigurationName)
        }
        moduleField.text = opts.appRunConfigurationName.orEmpty()
        selectScope(opts.testScope)
        regexField.text = opts.regex ?: ""
        packageField.text = opts.packageName ?: ""
        testClassField.text = opts.testClass ?: ""
        testMethodField.text = opts.testMethod ?: ""
        updateScopeFields()
        runnerField.text = opts.instrumentationRunner ?: ""
        extraArgsField.text = opts.extraArgs ?: ""
    }

    override fun applyEditorTo(config: JuggAndroidTestRunConfiguration) {
        val opts = config.state ?: return
        opts.testScope = selectedScope()
        opts.regex = regexField.text.takeIf { it.isNotBlank() }
        opts.packageName = packageField.text.takeIf { it.isNotBlank() }
        opts.testClass = testClassField.text.takeIf { it.isNotBlank() }
        opts.testMethod = testMethodField.text.takeIf { it.isNotBlank() }
        opts.instrumentationRunner = runnerField.text.takeIf { it.isNotBlank() }
        opts.extraArgs = extraArgsField.text.takeIf { it.isNotBlank() }
    }

    private fun selectedScope(): AndroidTestScope = when {
        allInPackageButton.isSelected -> AndroidTestScope.ALL_IN_PACKAGE
        classButton.isSelected -> AndroidTestScope.CLASS
        methodButton.isSelected -> AndroidTestScope.METHOD
        else -> AndroidTestScope.ALL_IN_MODULE
    }

    private fun selectScope(scope: AndroidTestScope) {
        when (scope) {
            AndroidTestScope.ALL_IN_MODULE -> allInModuleButton.isSelected = true
            AndroidTestScope.ALL_IN_PACKAGE -> allInPackageButton.isSelected = true
            AndroidTestScope.CLASS -> classButton.isSelected = true
            AndroidTestScope.METHOD -> methodButton.isSelected = true
        }
    }

    private fun updateScopeFields() {
        scopeFieldsPanel.removeAll()
        when (selectedScope()) {
            AndroidTestScope.ALL_IN_MODULE -> addLabeledField(scopeFieldsPanel, "Regex:", regexField)
            AndroidTestScope.ALL_IN_PACKAGE -> addLabeledField(scopeFieldsPanel, "Package:", packageField)
            AndroidTestScope.CLASS -> addLabeledField(scopeFieldsPanel, "Class:", testClassField)
            AndroidTestScope.METHOD -> {
                addLabeledField(scopeFieldsPanel, "Class:", testClassField)
                addLabeledField(scopeFieldsPanel, "Method:", testMethodField)
            }
        }
        scopeFieldsPanel.revalidate()
        scopeFieldsPanel.repaint()
    }

    private fun addLabeledField(panel: JPanel, label: String, field: JTextField) {
        panel.add(createRowPanel(label, field))
    }

    private fun createRowPanel(label: String, content: JComponent): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(8)
            add(JLabel(label))
            add(Box.createHorizontalStrut(8))
            add(content)
        }
    }

}

// ──────────────────────────────────────────────────────────────────────────────
// RunSpec Factory
// ──────────────────────────────────────────────────────────────────────────────

object JuggAndroidTestRunSpecFactory {
    fun fromOptions(options: JuggAndroidTestRunConfigurationOptions): AndroidTestRunSpec {
        validateOptions(options)
        val scopeArgs = when (options.testScope) {
            AndroidTestScope.ALL_IN_MODULE -> {
                options.regex.normalizeBlank()?.let { listOf("tests_regex" to it) } ?: emptyList()
            }
            AndroidTestScope.ALL_IN_PACKAGE -> listOf("package" to options.packageName.normalizeBlank()!!)
            else -> emptyList()
        }
        val testClass = when (options.testScope) {
            AndroidTestScope.CLASS, AndroidTestScope.METHOD -> options.testClass.normalizeBlank()
            else -> null
        }
        val testMethod = if (options.testScope == AndroidTestScope.METHOD) options.testMethod.normalizeBlank() else null
        return AndroidTestRunSpec(
            testClass = testClass,
            testMethod = testMethod,
            extraArgs = scopeArgs + parseExtraArgs(options.extraArgs),
            runnerOverride = options.instrumentationRunner.normalizeBlank(),
        )
    }

    fun validateOptions(options: JuggAndroidTestRunConfigurationOptions) {
        when (options.testScope) {
            AndroidTestScope.ALL_IN_MODULE -> Unit
            AndroidTestScope.ALL_IN_PACKAGE -> requireNotBlank(options.packageName, "Package is required")
            AndroidTestScope.CLASS -> requireNotBlank(options.testClass, "Class is required")
            AndroidTestScope.METHOD -> {
                requireNotBlank(options.testClass, "Class is required")
                requireNotBlank(options.testMethod, "Method is required")
            }
        }
    }

    private fun requireNotBlank(value: String?, message: String) {
        if (value.isNullOrBlank()) throw RuntimeConfigurationError(message)
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
 * Resolves the selected Jugg app run configuration and delegates to JuggManager.runTask
 * with the AndroidTestRunSpec built from this run configuration.
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
        val appRunConfigOptions = findAppRunConfigurationOptions(project) ?: throw RuntimeConfigurationError(
            "Selected Jugg app run configuration is missing"
        )
        val spec = explicitSpec ?: JuggAndroidTestRunSpecFactory.fromOptions(options)
        val actualExecutor = executor ?: stateExecutor
        return juggManager.runTask(appRunConfigOptions, actualExecutor, runProfile, spec)
    }

    private fun findAppRunConfigurationOptions(project: Project): JuggRunConfigurationOptions? {
        return JuggAndroidTestAppRunConfigurationSelector
            .selectedSettings(project, options.appRunConfigurationName)
            ?.let { it.configuration as? JuggRunConfiguration }
            ?.state
    }
}
