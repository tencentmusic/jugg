package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

class JuggMoreOptionsItem(
    val name: String,
    private val onGet: (JuggMoreOptionsItem.() -> Boolean)? = null,
    private val onSet: JuggMoreOptionsItem.(Boolean) -> Unit = { },
    val isSplitLine: Boolean = false,
) {

    private val isToggle: Boolean = onGet != null

    private var isSelected: Boolean
        get() = onGet?.invoke(this) ?: false
        set(value) {
            onSet(value)
        }

    @Suppress("MissingActionUpdateThread")
    fun toAction(): AnAction {
        // avoid popup is regarded as MultiChoiceGroup by com.intellij.openapi.actionSystem.impl.Utils.isMultiChoiceGroup
        // we cannot only use Separator and ToggleAction :)
        if (isToggle) {
            return object : ToggleAction(name) {

                override fun isSelected(e: AnActionEvent): Boolean {
                    return isSelected
                }

                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    isSelected = state
                }

                override fun isDumbAware(): Boolean {
                    return true
                }
            }
        } else {
            return object : AnAction(name) {

                override fun actionPerformed(e: AnActionEvent) {
                    onSet(isSelected)
                }

                override fun isDumbAware(): Boolean {
                    return true
                }
            }
        }
    }

}