package com.sickworm.intellij.jugg.ide

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.mock.MockProject
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import org.junit.Assert.assertNotSame
import org.junit.Test

class JuggAndroidTestRunConfigurationDeviceSelectionTest {

    @Test
    fun `android test run configuration enables device selection keys`() {
        val config = createAndroidTestConfig()

        config.getUserData(Key.create<Any>("trigger"))

        assertEnsureSetAllowSelectDeviceExecuted(config)
    }

    @Test
    fun `app run configuration enables device selection keys`() {
        val config = createAppRunConfig()

        config.getUserData(Key.create<Any>("trigger"))

        assertEnsureSetAllowSelectDeviceExecuted(config)
    }

    private fun assertEnsureSetAllowSelectDeviceExecuted(config: RunConfigurationBase<*>) {
        val field = config.javaClass.getDeclaredField("lastSetObj").apply { isAccessible = true }
        val sentinel = Any()
        field.set(config, sentinel)
        config.getUserData(Key.create<Any>("trigger-after-sentinel"))
        assertNotSame(
            "Expected ensureSetAllowSelectDevice to refresh lastSetObj",
            sentinel,
            field.get(config),
        )
    }

    private fun createAndroidTestConfig(): JuggAndroidTestRunConfiguration {
        val project = createProject()
        val type = object : ConfigurationTypeBase(
            "test.JuggAndroidTestConfigurationType",
            "Jugg Android Test",
            "Run Android instrumentation tests with Jugg",
            EmptyIcon,
        ) {
            init {
                addFactory(object : ConfigurationFactory(this) {
                    override fun getOptionsClass() = JuggAndroidTestRunConfigurationOptions::class.java
                    override fun getId() = "test.JuggAndroidTestConfigurationFactory"
                    override fun createTemplateConfiguration(project: Project): RunConfiguration =
                        JuggAndroidTestRunConfiguration(project, this, "Android Test")
                })
            }
        }
        return JuggAndroidTestRunConfiguration(project, type.configurationFactories.first(), "Android Test")
    }

    private fun createAppRunConfig(): JuggRunConfiguration {
        val project = createProject()
        val type = object : ConfigurationTypeBase(
            "test.JuggConfigurationType",
            "Jugg",
            "Run with Jugg",
            EmptyIcon,
        ) {
            init {
                addFactory(object : ConfigurationFactory(this) {
                    override fun getOptionsClass() = JuggRunConfigurationOptions::class.java
                    override fun getId() = "test.JuggConfigurationFactory"
                    override fun createTemplateConfiguration(project: Project): RunConfiguration =
                        JuggRunConfiguration(project, this, "Jugg")
                })
            }
        }
        return JuggRunConfiguration(project, type.configurationFactories.first(), "Jugg")
    }

    private fun createProject(): Project {
        return object : MockProject(null, {}) {}
    }

    private object EmptyIcon : javax.swing.Icon {
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) = Unit
        override fun getIconWidth() = 16
        override fun getIconHeight() = 16
    }
}
