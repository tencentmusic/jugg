package com.sickworm.intellij.jugg.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Container
import java.lang.reflect.Method
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.JTextField

class JuggAndroidTestSettingsEditorTest {

    @Test
    fun `editor contains four test scope radio choices`() {
        val editor = JuggAndroidTestSettingsEditor()

        val buttons = editor.editorComponent().allChildren().filterIsInstance<AbstractButton>()

        assertEquals(
            listOf("All in Module", "All in Package", "Class", "Method"),
            buttons.map { it.text },
        )
    }

    @Test
    fun `editor does not add chooser buttons`() {
        val editor = JuggAndroidTestSettingsEditor()

        val buttons = editor.editorComponent().allChildren().filterIsInstance<AbstractButton>()

        assertFalse(buttons.any { it.text == "..." })
    }

    @Test
    fun `instrumentation runner field is editable and extra args label is preserved`() {
        val editor = JuggAndroidTestSettingsEditor()

        val children = editor.editorComponent().allChildren()
        val labels = children.filterIsInstance<JLabel>().map { it.text }
        val runnerLabelIndex = labels.indexOf("Instrumentation class:")
        val extraArgsLabelIndex = labels.indexOf("Extra args (k1=v1,k2=v2)")

        assertTrue(runnerLabelIndex >= 0)
        assertTrue(extraArgsLabelIndex > runnerLabelIndex)
        assertTrue(children.asContainer().fieldAfterLabel("Instrumentation class:").isEditable)
    }

    @Test
    fun `module row does not claim a fake app module`() {
        val editor = JuggAndroidTestSettingsEditor()

        assertTrue(editor.editorComponent().fieldAfterLabel("Module:").text.isBlank())
    }

    @Test
    fun `apply persists selected scope fields and runner options`() {
        val config = androidTestConfig()
        val editor = JuggAndroidTestSettingsEditor()
        val component = editor.editorComponent()

        component.radioButton("Method").doClick()
        component.fieldAfterLabel("Class:").text = "com.example.FooTest"
        component.fieldAfterLabel("Method:").text = "testBar"
        component.fieldAfterLabel("Instrumentation class:").text = "com.example.CustomRunner"
        component.fieldAfterLabel("Extra args (k1=v1,k2=v2)").text = "clearPackageData=true"

        editor.applyTo(config)

        assertEquals(AndroidTestScope.METHOD, config.state?.testScope)
        assertEquals("com.example.FooTest", config.state?.testClass)
        assertEquals("testBar", config.state?.testMethod)
        assertEquals("com.example.CustomRunner", config.state?.instrumentationRunner)
        assertEquals("clearPackageData=true", config.state?.extraArgs)
    }

    @Test
    fun `reset shows selected scope fields and stored values`() {
        val config = androidTestConfig()
        config.state?.testScope = AndroidTestScope.ALL_IN_PACKAGE
        config.state?.packageName = "com.example.tests"
        config.state?.instrumentationRunner = "com.example.Runner"
        config.state?.extraArgs = "size=medium"
        val editor = JuggAndroidTestSettingsEditor()

        editor.resetFrom(config)
        val component = editor.component as Container

        assertTrue(component.radioButton("All in Package").isSelected)
        assertEquals("com.example.tests", component.fieldByExactText("com.example.tests").text)
        assertEquals("com.example.Runner", component.fieldAfterLabel("Instrumentation class:").text)
        assertEquals("size=medium", component.fieldAfterLabel("Extra args (k1=v1,k2=v2)").text)
    }

    private fun androidTestConfig(): JuggAndroidTestRunConfiguration {
        val runManager = org.mockito.Mockito.mock(com.intellij.execution.RunManager::class.java)
        val project = object : com.intellij.mock.MockProject(null, {}) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getService(serviceClass: Class<T>): T? {
                if (serviceClass == com.intellij.execution.RunManager::class.java) return runManager as T
                return super.getService(serviceClass)
            }
        }
        val type = object : com.intellij.execution.configurations.ConfigurationTypeBase(
            "test.JuggAndroidTestConfigurationType",
            "Jugg Android Test",
            "Run Android instrumentation tests with Jugg",
            object : javax.swing.Icon {
                override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) = Unit
                override fun getIconWidth() = 16
                override fun getIconHeight() = 16
            },
        ) {
            init {
                addFactory(object : com.intellij.execution.configurations.ConfigurationFactory(this) {
                    override fun getOptionsClass() = JuggAndroidTestRunConfigurationOptions::class.java
                    override fun getId() = "test.JuggAndroidTestConfigurationFactory"
                    override fun createTemplateConfiguration(project: com.intellij.openapi.project.Project) =
                        JuggAndroidTestRunConfiguration(project, this, "Android Test")
                })
            }
        }
        return JuggAndroidTestRunConfiguration(project, type.configurationFactories.first(), "Android Test")
    }

    private fun JuggAndroidTestSettingsEditor.editorComponent(): Container {
        val method: Method = JuggAndroidTestSettingsEditor::class.java.getDeclaredMethod("createEditor")
        method.isAccessible = true
        return method.invoke(this) as Container
    }

    private fun List<java.awt.Component>.asContainer(): Container {
        return object : Container() {
            override fun getComponents(): Array<java.awt.Component> = this@asContainer.toTypedArray()
        }
    }

    private fun Container.allChildren(): List<java.awt.Component> {
        val direct = components.toList()
        return direct + direct.filterIsInstance<Container>().flatMap { it.allChildren() }
    }

    private fun Container.radioButton(text: String): AbstractButton {
        return allChildren().filterIsInstance<AbstractButton>().first { it.text == text }
    }

    private fun Container.fieldByExactText(text: String): JTextField {
        return allChildren().filterIsInstance<JTextField>().first { it.text == text }
    }

    private fun Container.fieldAfterLabel(label: String): JTextField {
        val children = allChildren()
        val labelIndex = children.indexOfFirst { it is JLabel && it.text == label }
        return children.drop(labelIndex + 1).filterIsInstance<JTextField>().first()
    }
}
