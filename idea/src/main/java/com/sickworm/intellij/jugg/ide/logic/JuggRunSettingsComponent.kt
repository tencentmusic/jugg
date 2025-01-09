package com.sickworm.intellij.jugg.ide.logic

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.deploy.run.SuggestRunConfiguration
import com.sickworm.intellij.jugg.ide.IJuggRunSettingsComponent
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.ide.ui.ReportConfirmDialog
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.project.dependency.htmlWarning
import com.sickworm.intellij.jugg.server.protocols.RunConfigurationTemplate
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.*
import kotlin.math.max

/**
 * Run configuration settings UI
 */
class JuggRunSettingsComponent : JComponent(), IJuggRunSettingsComponent {

    override val component: JComponent = this

    private val topButtonsContainer: JPanel = JPanel().also {
        it.border = JBUI.Borders.empty(0, 4)
        it.layout = BoxLayout(it, BoxLayout.X_AXIS)
    }

    private var moreOptionsButton: DropDownLink<String> = DropDownLink("More options", emptyList()).also {
        it.border = JBUI.Borders.empty(0, 4)
    }

    private val tipsContainer = JPanel().also {
        it.border = JBUI.Borders.empty(0, 4)
        it.layout = BoxLayout(it, BoxLayout.X_AXIS)
    }

    private val compileCommandLabel = JLabel("Compile command:")
    private val compileCommandTextField = JTextField()
    private val outputApkNameLabel = JLabel("Output APK name/path:")
    private val outputApkNameTextField = JTextField()
    private val enableRemoteCompileCheckBox = JCheckBox("Enable remote compile")
    private val enableSyncAllProjectsCheckBox = JCheckBox("Enable multiple projects mode (sync and fetch all projects in [Local to remote sync path])")

    private val reportIssueActionLink = ActionLink("Report issues")

    private val syncModeLabel = JLabel("Sync mode: ")
    private val syncModeComboBox = ComboBox(SyncMode.values().map { it.modeName }.toTypedArray())
    private val userLabel = JLabel("SSH user:")
    private val userTextField = JTextField()
    private val passwordLabel = JLabel("SSH password/key (optional):")
    private val passwordTextField = JBPasswordField().also {
        it.emptyText.text = "default use empty password and SSH keys in .ssh"
    }
    private val ipLabel = JLabel("SSH host:")
    private val ipTextField = JTextField()
    private val portLabel = JLabel("SSH port:")
    private val portTextField = JTextField()
    private val localToRemoteIftConfigNameLabel = JLabel("Local to remote IFT config name:")
    private val localToRemoteIftConfigNameTextField = JTextField()
    private val localToRemoteSyncPathLabel = JLabel("Local to remote sync path:")
    private val localToRemoteSyncPathTextField = JTextField()
    private val remoteSyncPathLabel = JLabel("Remote root directory (optional):")
    private val remoteSyncPathTextField = JBTextField().also {
        it.emptyText.text = "default \$HOME/remote"
    }
    private val remoteToLocalIftConfigNameLabel = JLabel("Remote to local IFT config name:")
    private val remoteToLocalSyncPathLabel = JLabel("Remote to local sync path:")
    private val remoteToLocalSyncPathTextField = JTextField()
    private val remoteToLocalIftConfigNameTextField = JTextField()
    private val httpProxyIpLabel = JLabel("HTTP proxy host:")
    private val httpProxyIpTextField = JTextField()
    private val httpProxyPortLabel = JLabel("HTTP proxy port:")
    private val httpProxyPortTextField = JTextField()

    private val remoteCompilePanel = JPanel().also {
        it.border = IdeBorderFactory.createTitledBorder("Remote Compile Options")
        it.layout = GridLayout(0, 1, 5, 5)
    }
    private val remoteComponentList = listOf<Pair<JComponent, JComponent?>>(
        Pair(createPairPanel(syncModeLabel, syncModeComboBox, isMaxRight = false, marginHorizontal = 0), null),
        Pair(enableSyncAllProjectsCheckBox, null),
        Pair(userLabel, userTextField),
        Pair(passwordLabel, passwordTextField),
        Pair(ipLabel, ipTextField),
        Pair(portLabel, portTextField),
        Pair(localToRemoteIftConfigNameLabel, localToRemoteIftConfigNameTextField),
        Pair(localToRemoteSyncPathLabel, localToRemoteSyncPathTextField),
        Pair(remoteSyncPathLabel, remoteSyncPathTextField),
        Pair(remoteToLocalIftConfigNameLabel, remoteToLocalIftConfigNameTextField),
        Pair(remoteToLocalSyncPathLabel, remoteToLocalSyncPathTextField),
        Pair(httpProxyIpLabel, httpProxyIpTextField),
        Pair(httpProxyPortLabel, httpProxyPortTextField),
    )

    private val filteredRsyncLabel = listOf(localToRemoteIftConfigNameLabel, remoteToLocalIftConfigNameLabel)
    private val filteredRsyncSimpleLabel = listOf(
        localToRemoteIftConfigNameLabel,
        localToRemoteSyncPathLabel,
        remoteToLocalIftConfigNameLabel,
        remoteToLocalSyncPathLabel,
        enableSyncAllProjectsCheckBox,
    )

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(tipsContainer)
        add(topButtonsContainer)
        updateTopButtons()
        add(Box.createVerticalStrut(5))

        addPair(compileCommandLabel, compileCommandTextField, leftWidth = 180)
        add(Box.createVerticalStrut(5))
        addPair(outputApkNameLabel, outputApkNameTextField, leftWidth = 180)
        add(Box.createVerticalStrut(5))

        addPair(enableRemoteCompileCheckBox, reportIssueActionLink, leftWidth = 260, isAlignEnd = true)
        add(Box.createVerticalStrut(5))

        add(remoteCompilePanel)

        syncModeComboBox.addActionListener {
            updateRemoteUi(enableRemoteCompileCheckBox.isSelected, syncModeComboBox.selectedItem?.toString())
        }

        enableRemoteCompileCheckBox.addActionListener {
            val isSelected = enableRemoteCompileCheckBox.isSelected
            updateRemoteUi(isSelected, syncModeComboBox.selectedItem?.toString())
        }
        updateRemoteUi(enableRemoteCompileCheckBox.isSelected, syncModeComboBox.selectedItem?.toString())
    }

    private fun createMoreOptionsButton(project: Project): DropDownLink<String> {
        val popupBuilder: (DropDownLink<String>) -> JBPopup = { _ ->
            val options = JuggRunConfigurationOptions()
            updateJuggRunConfigurationOptions(options)

            val title = "More Options"
            val group = JuggInitializer.getManager(project)?.getMoreOptions(options) ?: DefaultActionGroup()
            val dataContext = DataManager.getInstance().getDataContext(moreOptionsButton)
            val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                title, group, dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
                null, -1
            )
            popup
        }

        return DropDownLink("More options", popupBuilder).also {
            it.border = JBUI.Borders.empty(0, 4)
        }
    }

    private fun updateTopButtons() {
        topButtonsContainer.removeAll()
        topButtonsContainer.add(Box.createHorizontalGlue())
        topButtonsContainer.add(moreOptionsButton)
    }

    override fun updateUi(settings: JuggRunConfigurationOptions, configName: String) {
        updateUi(settings.toRunConfigurationTemplate(), configName)
    }

    private fun updateUi(settings: RunConfigurationTemplate, configName: String) {
        compileCommandTextField.text = settings.compileCommand
        outputApkNameTextField.text = settings.outputApkName
        enableRemoteCompileCheckBox.isSelected = settings.isRemoteCompile
        updateRemoteUi(settings.isRemoteCompile, settings.syncMode)
        syncModeComboBox.selectedItem = if (settings.syncMode in SyncMode.values().map { it.modeName }) {
            settings.syncMode
        } else {
            SyncMode.IFT.modeName
        }
        enableSyncAllProjectsCheckBox.isSelected = settings.isSyncAllProjects
        userTextField.text = settings.remoteSshUser
        passwordTextField.text = settings.remoteSshPassword
        ipTextField.text = settings.remoteSshIp
        portTextField.text = settings.remoteSshPort.toString()
        httpProxyIpTextField.text = settings.httpProxyIp ?: ""
        httpProxyPortTextField.text = settings.httpProxyPort.toString()
        localToRemoteIftConfigNameTextField.text = settings.localToRemoteIftConfigName
        localToRemoteSyncPathTextField.text = settings.localToRemoteSyncPath
        remoteSyncPathTextField.text = settings.remoteSyncPath
        remoteToLocalIftConfigNameTextField.text = settings.remoteToLocalIftConfigName
        remoteToLocalSyncPathTextField.text = settings.remoteToLocalSyncPath

        tipsContainer.removeAll()
        if (configName == SuggestRunConfiguration.DEFAULT.runConfigName) {
            val tipsLabel = JBLabel()
            val text = "Jugg create this default config because auto detection failed. <br>Suggestion: re-sync or reopen project to detect again.".htmlWarning
            tipsLabel.text = "<html>$text</html>"
            val panel = createPairPanel(tipsLabel, null)
            tipsContainer.add(panel)
        }
    }

    // must run after updateUi for moreOptionsButton updates
    override fun initUpload(project: Project) {
        if (reportIssueActionLink.actionListeners.isEmpty()) {
            reportIssueActionLink.addActionListener {
                doUpload(project)
            }
            moreOptionsButton = createMoreOptionsButton(project)
            updateTopButtons()
        }
    }

    override fun updateJuggRunConfigurationOptions(options: JuggRunConfigurationOptions?) {
        val component = this
        options?.also {
            it.compileCommand = component.compileCommandTextField.text
            it.outputApkName = component.outputApkNameTextField.text
            it.isRemoteCompile = component.enableRemoteCompileCheckBox.isSelected
            it.syncMode = component.syncModeComboBox.selectedItem?.toString()
            it.isSyncAllProjects = component.enableSyncAllProjectsCheckBox.isSelected
            it.remoteSshUser = component.userTextField.text
            it.remoteSshPassword = component.passwordTextField.password.joinToString("")
            it.remoteSshIp = component.ipTextField.text
            it.remoteSshPort = component.portTextField.text.toIntOrNull() ?: 0
            it.localToRemoteIftConfigName = component.localToRemoteIftConfigNameTextField.text
            it.localToRemoteSyncPath = component.localToRemoteSyncPathTextField.text
            it.remoteSyncPath = component.remoteSyncPathTextField.text
            it.remoteToLocalIftConfigName = component.remoteToLocalIftConfigNameTextField.text
            it.remoteToLocalSyncPath = component.remoteToLocalSyncPathTextField.text
            it.httpProxyIp = component.httpProxyIpTextField.text
            it.httpProxyPort = component.httpProxyPortTextField.text.toIntOrNull() ?: 0
        }
    }

    private fun doUpload(project: Project) {
        JuggInitializer.getManager(project)?.reportIssue()
    }

    private fun addPair(left: JComponent?, right: JComponent?, leftWidth: Int, isAlignEnd: Boolean = false): JPanel {
        val jPanel = createPairPanel(left, right, leftWidth, isAlignEnd)
        add(jPanel)
        return jPanel
    }

    private fun createPairPanel(left: JComponent?,
                                right: JComponent?,
                                leftWidth: Int = 0,
                                isAlignEnd: Boolean = false,
                                isMaxRight: Boolean = true,
                                marginHorizontal: Int = 4,
    ): JPanel {
        val jPanel = JPanel()
        jPanel.run {
            border = JBUI.Borders.empty(0, marginHorizontal)
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            left?.let {
                add(left)
                val realLeftWidth = if (right == null) {
                    Int.MAX_VALUE
                } else if (leftWidth > 0) {
                    leftWidth
                } else {
                    left.preferredSize.width
                }
                left.preferredSize = Dimension(realLeftWidth, left.preferredSize.height)
            }

            if (isAlignEnd) {
                add(Box.createHorizontalGlue())
            }

            right?.let {
                val rightHeight = max(right.preferredSize.height, left?.preferredSize?.height ?: 0)
                if (isMaxRight) {
                    maximumSize = Dimension(Int.MAX_VALUE, rightHeight)
                } else {
                    right.maximumSize = Dimension(right.preferredSize.width, rightHeight)
                }
                add(right)
            }
        }
        return jPanel
    }

    private fun updateRemoteUi(isSelected: Boolean, syncMode: String?) {

        remove(remoteCompilePanel)
        if (isSelected) {
            add(remoteCompilePanel)
        }

        remoteCompilePanel.removeAll()
        remoteComponentList.forEach {
            val isFilteredByRsync = when (syncMode) {
                SyncMode.RSYNC_SIMPLE.modeName -> {
                    it.first in filteredRsyncSimpleLabel
                }
                SyncMode.RSYNC.modeName -> {
                    it.first in filteredRsyncLabel
                }
                else -> {
                    false
                }
            }
            if (!isFilteredByRsync) {
                it.first.parent?.let { parent ->
                    remoteCompilePanel.add(parent)
                } ?: run {
                    val pair = createPairPanel(it.first, it.second, leftWidth = 260)
                    remoteCompilePanel.add(pair)
                }
            }
        }

        revalidate()
        repaint()
    }
}
