package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.JuggRunSettingsComponentWrapper
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JPanel

class JuggRunSettingsComponentTest {

    @Test
    fun `single column rows should stay left aligned`() {
        val component = JuggRunSettingsComponent()
        val androidTestCheckBox = readPrivateField<JCheckBox>(component, "enableAndroidTestCheckBox")
        val rowPanel = invokeCreatePairPanel(component, androidTestCheckBox, null)

        assertEquals(Component.LEFT_ALIGNMENT, rowPanel.alignmentX, 0.0f)
    }

    @Test
    fun `settings editor layers should advertise left alignment`() {
        val component = JuggRunSettingsComponent()
        val wrapper = JuggRunSettingsComponentWrapper()
        wrapper.setImpl(component)

        assertEquals(Component.LEFT_ALIGNMENT, wrapper.alignmentX, 0.0f)
        assertEquals(Component.LEFT_ALIGNMENT, component.alignmentX, 0.0f)
    }

    @Test
    fun `settings component should fill wide editor instead of drifting to center`() {
        val component = JuggRunSettingsComponent()
        component.updateUi(JuggRunConfigurationOptions().apply {
            isRemoteCompile = true
            syncMode = SyncMode.RSYNC_SIMPLE.modeName
        }, "jugg:test")
        val wrapper = JuggRunSettingsComponentWrapper()
        wrapper.setImpl(component)

        layoutInWideParent(wrapper, width = 1600, height = 900)

        assertEquals(0, component.x)
        assertEquals(wrapper.width, component.width)
    }

    private fun <T> readPrivateField(target: Any, fieldName: String): T {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(target) as T
    }

    private fun invokeCreatePairPanel(component: JuggRunSettingsComponent, left: JCheckBox, right: Any?): javax.swing.JPanel {
        val method = component.javaClass.getDeclaredMethod(
            "createPairPanel",
            javax.swing.JComponent::class.java,
            javax.swing.JComponent::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(component, left, right, 0, false, true, 4, 0) as javax.swing.JPanel
    }

    private fun layoutInWideParent(component: Component, width: Int, height: Int) {
        val parent = JPanel()
        parent.layout = BoxLayout(parent, BoxLayout.Y_AXIS)
        parent.add(component)
        parent.setSize(width, height)
        layoutRecursively(parent)
    }

    private fun layoutRecursively(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach { layoutRecursively(it) }
        }
    }
}
