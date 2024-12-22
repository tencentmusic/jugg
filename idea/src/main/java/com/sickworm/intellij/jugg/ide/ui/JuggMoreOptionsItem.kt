package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.deploy.CompatDeployHelper
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdb
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

            val items = mutableListOf<JuggMoreOptionsItem>()

            fun createSplitLine(name: String) {
                val item = JuggMoreOptionsItem(
                    name = name,
                    isSplitLine = true
                )
                items.add(item)
            }

            fun createOption(
                name: String,
                onGet: (JuggMoreOptionsItem.() -> Boolean)? = null,
                onSet: JuggMoreOptionsItem.(Boolean) -> Unit = { },
            ) {
                val item = JuggMoreOptionsItem(name, onGet, onSet)
                items.add(item)
            }

            createSplitLine("Run Options")

            createOption(
                name = "Confirm fallback when no file changes",
                { JuggSettings.isConfirmFallbackWhenNoFileChanges },
                { JuggSettings.isConfirmFallbackWhenNoFileChanges = it }
            )

            createSplitLine("Tools")

            createOption(
                name = "Copy generated source to local",
                onSet = { JuggInitializer.getManager(project)?.copyGeneratedSourceToLocal() }
            )

            createOption(
                name = "Set custom server URL",
                onSet = { JuggInitializer.getManager(project)?.setCustomServerUrl() }
            )

            createSplitLine("Experimental functions")

            createOption(
                name = "Enable inject Gradle compilation",
                onGet = { JuggSettings.isEnableInjectGradleCompile },
                onSet = {
                    val isConfirmed = CommonConfirmDialog.showAndGetResult(
                        "Confirm Switch Inject Gradle Compilation",
                        "<html>This will reset compiler and fallback to gradle compile next time.<br>Are you sure to continue?</html>"
                    )
                    if (isConfirmed) {
                        JuggSettings.isEnableInjectGradleCompile = it
                        JuggInitializer.getManager(project)?.enableInjectGradleCompilation()
                    }
                }
            )

            if (JuggSettings.isEnableInjectGradleCompile) {
                createOption(
                    name = "Enable read project info from Gradle",
                    onGet = { JuggSettings.isEnableReadProjectInfoFromGradle },
                    onSet = {
                        JuggSettings.isEnableReadProjectInfoFromGradle = it
                        JuggInitializer.getManager(project)?.enableReadProjectFromGradle()
                    }
                )

                createOption(
                    name = "Enable compatible deployment mode",
                    onGet = { JuggSettings.isEnableCompatibleDeploymentMode },
                    onSet = {
                        JuggSettings.isEnableCompatibleDeploymentMode = it
                        JuggInitializer.getManager(project)?.enableCompatibleDeploymentMode()
                    }
                )

                val devices = JuggInitializer.getManager(project)?.getDeviceList()
                devices?.forEach {
                    val compatDeployHelper = CompatDeployHelper(JuggInitializer.getManager(project)!!.logger)
                    val adb = IdeaDeviceAdb(it, DefaultLogger(""))
                    createOption(
                        name = "Force use compat deploy for ${adb.displayName}",
                        onGet = { compatDeployHelper.isForceCompatDevice(adb) },
                        onSet = {
                            JuggInitializer.getManager(project)?.setForceCompatDevice(adb)
                        }
                    )
                }
            }

            if (!isWindows) {
                createOption(
                    name = "Enable backup classpath",
                    onGet = { JuggSettings.isEnableBackupClasspath },
                    onSet = {
                        val isConfirmed = CommonConfirmDialog.showAndGetResult(
                            "Confirm Switch Backup Classpath",
                            "<html>This will effects compilation stability. Continue?</html>"
                        )
                        if (isConfirmed) {
                            JuggSettings.isEnableBackupClasspath = it
                            JuggInitializer.getManager(project)?.setEnableBackupClasspath()
                        }
                    }
                )
            }

            createSplitLine("(Test) Mock Events")

            createOption(
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
            )

            createOption(
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
            )

            return items
        }
    }
}