package com.sickworm.intellij.jugg.ide

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.mock.MockProject
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class JuggAndroidTestRunConfigurationDeviceSelectionTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            TestGlobal.init()
        }
    }

    @Test
    fun `android test run configuration enables device selection keys`() {
        val config = createAndroidTestConfig()

        config.getUserData(Key.create<Any>("trigger"))

        assertDeviceSelectionEnabled(config)
    }

    @Test
    fun `app run configuration enables device selection keys`() {
        val config = createAppRunConfig()

        config.getUserData(Key.create<Any>("trigger"))

        assertDeviceSelectionEnabled(config)
    }

    private fun assertDeviceSelectionEnabled(config: RunConfigurationBase<*>) {
        val keys = loadDeviceSelectionKeys()
        assertTrue("Expected at least one Android Studio device selection key", keys.isNotEmpty())
        keys.forEach { key ->
            assertEquals(true, config.getUserData(key))
        }
    }

    private fun loadDeviceSelectionKeys(): List<Key<Boolean>> {
        return listOf(
            KeyRef("com.android.tools.idea.execution.common.DeployableToDevice", "KEY"),
            KeyRef("com.android.tools.idea.run.deployment.DeviceAndSnapshotComboBoxAction", "DEPLOYS_TO_LOCAL_DEVICE"),
        ).mapNotNull { it.loadKey() }
    }

    private data class KeyRef(val className: String, val fieldName: String) {
        fun loadKey(): Key<Boolean>? {
            return try {
                @Suppress("UNCHECKED_CAST")
                Class.forName(className).getField(fieldName).get(null) as? Key<Boolean>
            } catch (e: Throwable) {
                null
            }
        }
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
