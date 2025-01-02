package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.project.Project
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
        add(impl.component)
        this.impl = impl
    }
}