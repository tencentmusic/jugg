package com.sickworm.intellij.jugg.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.lang.reflect.Method
import javax.swing.AbstractButton
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField

class JuggAndroidTestSettingsEditorTest {

    @Test
    fun `editor contains class and method scope radio choices`() {
        val editor = JuggAndroidTestSettingsEditor()

        val buttons = editor.editorComponent().allChildren().filterIsInstance<AbstractButton>()

        assertEquals(
            listOf("Class", "Method"),
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
    fun `module and instrumentation rows are hidden`() {
        val editor = JuggAndroidTestSettingsEditor()

        val labels = editor.editorComponent().allChildren().filterIsInstance<JLabel>().map { it.text }

        assertFalse(labels.contains("Module:"))
        assertFalse(labels.contains("Instrumentation class:"))
    }

    @Test
    fun `scope rows keep titles on the same left aligned line`() {
        val editor = JuggAndroidTestSettingsEditor()
        val component = editor.editorComponent()

        component.radioButton("Method").doClick()

        assertHorizontalRow(component.containerWithLabel("Test:"))
        assertHorizontalRow(component.containerWithLabel("Class:"))
        assertHorizontalRow(component.containerWithLabel("Method:"))
        assertHorizontalRow(component.containerWithLabel("Extra args:"))
    }

    @Test
    fun `module and source path rows do not claim fake values`() {
        val editor = JuggAndroidTestSettingsEditor()

        assertTrue(editor.editorComponent().fieldAfterLabel("Module:").text.isBlank())
        assertTrue(editor.editorComponent().fieldAfterLabel("Source path:").text.isBlank())
    }

    @Test
    fun `editor uses larger spacing and extra args hint`() {
        val editor = JuggAndroidTestSettingsEditor()
        val root = editor.editorComponent()

        assertEquals("Expected larger top padding", 12, root.insets.top)
        assertEquals("Expected larger left padding", 12, root.insets.left)
        assertEquals("Expected larger bottom padding", 12, root.insets.bottom)
        assertEquals("Expected larger right padding", 12, root.insets.right)

        val rootRows = root.components.filterIsInstance<JComponent>()
        val rowInsets = rootRows
            .filter { it.layout is BoxLayout }
            .map { it.border?.getBorderInsets(it)?.bottom ?: 0 }
        assertTrue("Expected row spacing to increase", rowInsets.any { it >= 8 })

        val extraField = root.fieldAfterLabel("Extra args:")
        val extraFieldFromPrivate = readPrivateField<JTextField>(editor, "extraArgsField")
        val extraHint = extraFieldFromPrivate.toolTipText
        assertEquals("k1=v1,k2=v2", extraHint)
        assertTrue(extraField === extraFieldFromPrivate)
    }

    @Test
    fun `apply persists selected scope fields and extra args`() {
        val config = androidTestConfig()
        val editor = JuggAndroidTestSettingsEditor()
        val component = editor.editorComponent()

        component.radioButton("Method").doClick()
        component.fieldAfterLabel("Class:").text = "com.example.FooTest"
        component.fieldAfterLabel("Method:").text = "testBar"
        component.fieldAfterLabel("Extra args:").text = "clearPackageData=true"

        editor.applyTo(config)

        assertEquals(AndroidTestScope.METHOD, config.state?.testScope)
        assertEquals("com.example.FooTest", config.state?.testClass)
        assertEquals("testBar", config.state?.testMethod)
        assertEquals("clearPackageData=true", config.state?.extraArgs)
    }

    @Test
    fun `reset shows selected scope fields and stored values`() {
        val config = androidTestConfig()
        config.state?.testScope = AndroidTestScope.CLASS
        config.state?.testClass = "com.example.FooTest"
        config.state?.sourcePath = "library1/src/androidTest/kotlin/com/example/FooTest.kt"
        config.state?.instrumentationRunner = "com.example.Runner"
        config.state?.extraArgs = "size=medium"
        val editor = JuggAndroidTestSettingsEditor()

        editor.resetFrom(config)
        val component = editor.component as Container

        assertTrue(component.radioButton("Class").isSelected)
        assertEquals(
            "library1/src/androidTest/kotlin/com/example/FooTest.kt",
            component.fieldAfterLabel("Source path:").text,
        )
        assertEquals("com.example.FooTest", component.fieldByExactText("com.example.FooTest").text)
        assertEquals("size=medium", component.fieldAfterLabel("Extra args:").text)
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

    private fun Container.containerWithLabel(label: String): Container {
        val found = allChildren().firstOrNull { it is JLabel && it.text == label }
            ?: error("Missing label: $label")
        return found.parent ?: error("Missing parent container for label: $label")
    }

    private fun assertHorizontalRow(container: Container) {
        val layout = container.layout
        assertNotNull(layout)
        assertTrue("Expected a BoxLayout row", layout is BoxLayout)
        assertEquals("Expected left-aligned row", Component.LEFT_ALIGNMENT, container.alignmentX, 0.0f)
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

    private fun <T> readPrivateField(target: Any, fieldName: String): T {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(target) as T
    }
}
