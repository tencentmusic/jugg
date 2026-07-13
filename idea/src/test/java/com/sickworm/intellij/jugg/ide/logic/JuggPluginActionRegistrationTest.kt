package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.project.DumbAware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class JuggPluginActionRegistrationTest {

    @Test
    fun pluginXml_shouldRegisterCheckJuggUpdateAction() {
        val action = pluginAction("jugg.CheckJuggUpdateAction")

        assertNotNull(action)
        assertEquals("com.sickworm.intellij.jugg.ide.ui.CheckJuggUpdateAction", action.getAttribute("class"))
        assertEquals("Check Jugg Update", action.getAttribute("text"))
        assertEquals("Check Jugg Update", action.getAttribute("description"))
        Class.forName(action.getAttribute("class"))
    }

    @Test
    fun pluginXml_shouldRegisterJuggControlPanel() {
        val toolWindow = pluginExtension("toolWindow", "id", "Jugg Running Pannel")
        assertEquals("com.sickworm.intellij.jugg.ide.ui.JuggToolWindowFactory", toolWindow.getAttribute("factoryClass"))
        assertEquals("right", toolWindow.getAttribute("anchor"))
        assertEquals("/res/icons/run_configuration.svg", toolWindow.getAttribute("icon"))

        val action = pluginAction("jugg.OpenControlPanelAction")
        assertEquals("com.sickworm.intellij.jugg.ide.ui.OpenJuggControlPanelAction", action.getAttribute("class"))
        assertTrue(DumbAware::class.java.isAssignableFrom(Class.forName(toolWindow.getAttribute("factoryClass"))))
        assertTrue(DumbAware::class.java.isAssignableFrom(Class.forName(action.getAttribute("class"))))
    }

    private fun pluginAction(id: String): org.w3c.dom.Element {
        return pluginExtension("action", "id", id)
    }

    private fun pluginExtension(
        tagName: String,
        attributeName: String,
        attributeValue: String,
    ): org.w3c.dom.Element {
        val resource = javaClass.classLoader.getResource("META-INF/plugin.xml")
        assertNotNull("plugin.xml should be available as a test resource", resource)

        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(resource!!.openStream())
        val extensions = document.getElementsByTagName(tagName)
        for (index in 0 until extensions.length) {
            val extension = extensions.item(index) as org.w3c.dom.Element
            if (extension.getAttribute(attributeName) == attributeValue) {
                return extension
            }
        }
        throw AssertionError("$tagName with $attributeName=$attributeValue is not registered")
    }
}
