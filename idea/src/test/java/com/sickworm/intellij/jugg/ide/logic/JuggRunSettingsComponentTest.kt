package com.sickworm.intellij.jugg.ide.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Component
import javax.swing.JCheckBox

class JuggRunSettingsComponentTest {

    @Test
    fun `single column rows should stay left aligned`() {
        val component = JuggRunSettingsComponent()
        val androidTestCheckBox = readPrivateField<JCheckBox>(component, "enableAndroidTestCheckBox")
        val rowPanel = invokeCreatePairPanel(component, androidTestCheckBox, null)

        assertEquals(Component.LEFT_ALIGNMENT, rowPanel.alignmentX, 0.0f)
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
}
