package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.project.Project
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent

interface IJuggRunSettingsComponent {
    val component: JComponent
    fun updateUi(settings: JuggRunConfigurationOptions, configName: String)
    fun initUpload(project: Project)
    fun updateJuggRunConfigurationOptions(options: JuggRunConfigurationOptions?)
}

class JuggRunSettingsComponentWrapper : JComponent() {

    var impl: IJuggRunSettingsComponent? = null
        private set

    fun setImpl(impl: IJuggRunSettingsComponent) {
        removeAll()
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        impl.component.alignmentX = LEFT_ALIGNMENT
        impl.component.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(impl.component)
        this.impl = impl
    }
}
