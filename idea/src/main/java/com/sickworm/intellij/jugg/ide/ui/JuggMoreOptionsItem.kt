package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ide.JuggInitializer
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.JuggSettings

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

    companion object {

        fun createOptions(project: Project?, options: JuggRunConfigurationOptions): ActionGroup {
            val group = DefaultActionGroup()
            getOptionList(project, options).forEach {
                if (it.isSplitLine) {
                    group.addSeparator(it.name)
                } else {
                    group.add(it.toAction())
                }
            }
            return group
        }

        private fun getOptionList(project: Project?, options: JuggRunConfigurationOptions): List<JuggMoreOptionsItem> {
            return listOf(
                createSplitLine("Run Options"),

                JuggMoreOptionsItem(
                    name = "Confirm fallback when no file changes",
                    { JuggSettings.isConfirmFallbackWhenNoFileChanges },
                    { JuggSettings.isConfirmFallbackWhenNoFileChanges = it }
                ),

                createSplitLine("Tools"),

                JuggMoreOptionsItem(
                    name = "Copy generated source to local",
                    { false },
                    { JuggInitializer.getManager(project)?.copyGeneratedSourceToLocal() }
                ),

                createSplitLine("Experimental functions"),

                JuggMoreOptionsItem(
                    name = "Enable inject Gradle compilation",
                    onGet = { JuggSettings.isEnableInjectGradleCompile },
                    onSet = {
                        val isConfirmed = CommonConfirmDialog.showAndGetResult(
                            "Confirm Switch Inject Gradle Compilation",
                            "<html>This will reset compiler and fallback to gradle compile next time.<br>Are you sure to continue?</html>"
                        )
                        if (isConfirmed) {
                            JuggSettings.isEnableInjectGradleCompile = it
                            JuggInitializer.getManager(project)?.deleteCompileContext()
                            JuggInitializer.getManager(project)?.enableReadProjectFromGradle()
                            JuggInitializer.getManager(project)?.removeJuggJvmtiAgents()
                        }
                    }
                ),

                if (JuggSettings.isEnableInjectGradleCompile) {
                    JuggMoreOptionsItem(
                        name = "Enable read project info from Gradle",
                        onGet = { JuggSettings.isEnableReadProjectInfoFromGradle },
                        onSet = {
                            JuggSettings.isEnableReadProjectInfoFromGradle = it
                            JuggInitializer.getManager(project)?.enableReadProjectFromGradle()
                        }
                    )
                } else null,

                if (JuggSettings.isEnableInjectGradleCompile) {
                    JuggMoreOptionsItem(
                        name = "Enable compatible deployment mode",
                        onGet = { JuggSettings.isEnableCompatibleDeploymentMode },
                        onSet = {
                            JuggSettings.isEnableCompatibleDeploymentMode = it
                            JuggInitializer.getManager(project)?.removeJuggJvmtiAgents()
                        }
                    )
                } else null,

                createSplitLine("(Test) Mock Events"),

                JuggMoreOptionsItem(
                    name = "Mark as project synced and re-init compiler",
                    onSet = {
                        val isConfirmed = CommonConfirmDialog.showAndGetResult(
                            "Confirm Mark as Project Synced and Re-init Compiler",
                            "<html>This will reload project info and re-init, but dependencies won't update without sync.<br>Are you sure to continue?</html>"
                        )
                        if (isConfirmed) {
                            JuggInitializer.getManager(project)?.markAsSyncedAndReInitCompiler()
                        }
                    }
                ),

                JuggMoreOptionsItem(
                    name = "Mark as gradle compiled and re-init compiler",
                    onSet = {
                        val isConfirmed = CommonConfirmDialog.showAndGetResult(
                            "Confirm Mark as Gradle Compiled and Re-init Compiler",
                            "<html>This will skip gradle compilation and re-init, but the behavior of Jugg may incorrect.<br>Are you sure to continue?</html>"
                        )
                        if (isConfirmed) {
                            JuggInitializer.getManager(project)?.markAsGradleCompiledAndReInitCompiler(options)
                        }
                    }
                ),
            ).filterNotNull()
        }

        private fun createSplitLine(name: String): JuggMoreOptionsItem {
            return JuggMoreOptionsItem(
                name = name,
                isSplitLine = true
            )
        }
    }
}