package com.sickworm.intellij.jugg.ide.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun pluginAction(id: String): org.w3c.dom.Element {
        val resource = javaClass.classLoader.getResource("META-INF/plugin.xml")
        assertNotNull("plugin.xml should be available as a test resource", resource)

        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(resource!!.openStream())
        val actions = document.getElementsByTagName("action")
        for (index in 0 until actions.length) {
            val action = actions.item(index) as org.w3c.dom.Element
            if (action.getAttribute("id") == id) {
                return action
            }
        }
        throw AssertionError("Action $id is not registered")
    }
}
