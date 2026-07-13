@file:OptIn(ExperimentalStdlibApi::class)

package com.sickworm.intellij.jugg.ide.logic

import com.google.gson.GsonBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.deploy.run.SuggestRunConfiguration
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.ide.IJuggRunSettingsComponent
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.ide.ui.JuggControlPanel
import com.sickworm.intellij.jugg.ide.ui.RemoteCompileApplierDialog
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.dependency.htmlWarning
import com.sickworm.intellij.jugg.server.protocols.RunConfigurationTemplate
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.*
import kotlin.math.max

/**
 * Run configuration settings UI
 */
class JuggRunSettingsComponent : JComponent(), IJuggRunSettingsComponent {

    override val component: JComponent = this

    private val topButtonsContainer: JPanel = JPanel().also {
        it.alignmentX = LEFT_ALIGNMENT
        it.border = JBUI.Borders.empty(0, 4)
        it.layout = BoxLayout(it, BoxLayout.X_AXIS)
    }

    private val openControlPanelLink = ActionLink("More options").also {
        it.border = JBUI.Borders.empty(0, 4)
    }

    private val tipsContainer = JPanel().also {
        it.alignmentX = LEFT_ALIGNMENT
        it.border = JBUI.Borders.empty(0, 4)
        it.layout = BoxLayout(it, BoxLayout.X_AXIS)
    }

    private val compileCommandLabel = JLabel("Compile command:")
    private val compileCommandTextField = JTextField()
    private val outputApkNameLabel = JLabel("Output APK name/path:")
    private val outputApkNameTextField = JTextField()
    private val enableRemoteCompileCheckBox = JCheckBox("Enable remote compile")
    private val enableSyncAllProjectsCheckBox = JCheckBox("Enable multiple projects mode (sync and fetch all projects in [Local to remote sync path])")
    private val enableAndroidTestCheckBox = JCheckBox("Enable incremental Android Test (builds app + test APKs via Gradle)")

    private val reportIssueActionLink = ActionLink("Report issues")

    private val syncModeLabel = JLabel("Sync mode: ")
    private val syncModeComboBox = ComboBox(SyncMode.entries.map { it.modeName }.toTypedArray())
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
    private val environmentVariablesLabel = JLabel("Environment variables:")
    private val environmentVariablesTextField = JBTextField().also {
        it.emptyText.text = "e.g. JAVA_HOME=/root/openjdk17; VAR=value"
    }
    private val remoteSyncExcludePatternsLabel = JLabel("Additional exclude patterns:")
    private val remoteSyncExcludePatternsTextField = JBTextField().also {
        it.emptyText.text = "e.g. .git; /.git/; local-temp/; **/*.dat (rsync patterns)"
        it.toolTipText = "<html>Additional rsync exclude patterns separated by semicolon.<br/>" +
                "Patterns are applied as entered relative to the actual transfer root.<br/>" +
                "Leading / anchors a pattern to the transfer root.<br/>" +
                "This is not gitignore, and no need *.class because it already added in defaults.<br/>" +
                "Examples: .git; /.git/; local-temp/**; **/*.dat<br/>" +
                "Parent paths are not supported.</html>"
    }

    private val applyServerActionLink = ActionLink("Apply server")
    private val copyRemoteConfigActionLink = ActionLink("Copy config")

    private val remoteCompilePanel = JPanel().also {
        it.alignmentX = LEFT_ALIGNMENT
        it.border = IdeBorderFactory.createTitledBorder("Remote Compile Options")
        it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
    }
    private val remoteComponentList = listOf<Pair<JComponent, JComponent?>>(
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
        Pair(environmentVariablesLabel, environmentVariablesTextField),
        Pair(remoteSyncExcludePatternsLabel, remoteSyncExcludePatternsTextField),
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
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(tipsContainer)
        add(topButtonsContainer)
        updateTopButtons()
        add(Box.createVerticalStrut(5))

        addPair(compileCommandLabel, compileCommandTextField, leftWidth = 180)
        compileCommandTextField.preferredSize = Dimension(400, outputApkNameTextField.preferredSize.height)
        add(Box.createVerticalStrut(5))
        addPair(outputApkNameLabel, outputApkNameTextField, leftWidth = 180)
        outputApkNameTextField.preferredSize = Dimension(400, outputApkNameTextField.preferredSize.height)
        add(Box.createVerticalStrut(5))

        addPair(enableRemoteCompileCheckBox, reportIssueActionLink, leftWidth = 260, isAlignEnd = true)
        add(Box.createVerticalStrut(5))

        addPair(enableAndroidTestCheckBox, null, leftWidth = 0)
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

    private fun updateTopButtons() {
        topButtonsContainer.removeAll()
        topButtonsContainer.add(Box.createHorizontalGlue())
        topButtonsContainer.add(openControlPanelLink)
        topButtonsContainer.maximumSize = Dimension(Int.MAX_VALUE, topButtonsContainer.preferredSize.height)
    }

    override fun updateUi(settings: JuggRunConfigurationOptions, configName: String) {
        updateUi(settings.toRunConfigurationTemplate(), configName)
        enableAndroidTestCheckBox.isSelected = settings.enableAndroidTest
    }

    private fun updateUi(settings: RunConfigurationTemplate, configName: String) {
        compileCommandTextField.text = settings.compileCommand
        outputApkNameTextField.text = settings.outputApkName
        enableRemoteCompileCheckBox.isSelected = settings.isRemoteCompile
        updateRemoteUi(settings.isRemoteCompile, settings.syncMode)
        syncModeComboBox.selectedItem = if (settings.syncMode in SyncMode.entries.map { it.modeName }) {
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
        environmentVariablesTextField.text = settings.environmentVariables ?: ""
        remoteSyncExcludePatternsTextField.text = formatRemoteSyncExcludePatternsForField(settings.remoteSyncExcludePatterns)

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
            openControlPanelLink.addActionListener {
                JuggControlPanel.openSettings(project)
            }
            updateTopButtons()
        }

        if (applyServerActionLink.actionListeners.isEmpty()) {
            applyServerActionLink.addActionListener {
                val logger =  JuggLogger.getInstance(project, "RemoteCompileApplierDialog")
                val settings = RemoteCompileApplierDialog.showAndGetResult(getUsername(project), logger)
                if (settings != null) {
                    enableRemoteCompileCheckBox.isSelected = true
                    updateRemoteUi(true, settings.syncMode)
                    syncModeComboBox.selectedItem = if (settings.syncMode in SyncMode.entries.map { it.modeName }) {
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
                    remoteSyncPathTextField.text = settings.remoteSyncPath
                }
            }
        }

        if (copyRemoteConfigActionLink.actionListeners.isEmpty()) {
            copyRemoteConfigActionLink.addActionListener {
                val component = this
                val data = linkedMapOf(
                    "user" to component.userTextField.text,
                    "password" to component.passwordTextField.password.joinToString(""),
                    "ip" to component.ipTextField.text,
                    "port" to component.portTextField.text,
                )
                data["ssh_login_cmd"] = "ssh ${data["user"]}@${data["ip"]} -p ${data["port"]}"
                val text = GsonBuilder().setPrettyPrinting().create().toJson(data)
                saveTextToClipboard(text)
                JOptionPane.showMessageDialog(this,
                    "Server config has copied to your clipboard.",
                    "Copy successfully", JOptionPane.INFORMATION_MESSAGE)
            }
        }
    }

    override fun updateJuggRunConfigurationOptions(options: JuggRunConfigurationOptions?) {
        val component = this
        options?.also {
            it.compileCommand = component.compileCommandTextField.text
            it.outputApkName = component.outputApkNameTextField.text
            it.isRemoteCompile = component.enableRemoteCompileCheckBox.isSelected
            it.enableAndroidTest = component.enableAndroidTestCheckBox.isSelected
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
            it.environmentVariables = component.environmentVariablesTextField.text
            it.remoteSyncExcludePatterns = component.remoteSyncExcludePatternsTextField.text
        }
    }

    private fun formatRemoteSyncExcludePatternsForField(patterns: String?): String {
        return patterns.orEmpty()
            .split(Regex("[;,\\r\\n]+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("; ")
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
                                marginBetween: Int = 0,
    ): JPanel {
        val jPanel = JPanel()
        jPanel.run {
            border = JBUI.Borders.empty(0, marginHorizontal)
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = LEFT_ALIGNMENT
            left?.let {
                add(left)
                if (right == null) {
                    if (leftWidth > 0) {
                        val h = left.preferredSize.height
                        left.minimumSize = Dimension(leftWidth, h)
                        left.preferredSize = Dimension(leftWidth, h)
                    }
                    add(Box.createHorizontalGlue())
                    val rowHeight = left.preferredSize.height
                    maximumSize = Dimension(Int.MAX_VALUE, rowHeight)
                } else {
                    val realLeftWidth = if (leftWidth > 0) {
                        leftWidth
                    } else {
                        left.preferredSize.width
                    }
                    left.minimumSize = Dimension(realLeftWidth, left.preferredSize.height)
                    left.preferredSize = Dimension(realLeftWidth, left.preferredSize.height)
                }
            }

            if (isAlignEnd && right != null) {
                add(Box.createHorizontalGlue())
            }

            right?.let {
                val rightHeight = max(right.preferredSize.height, left?.preferredSize?.height ?: 0)
                if (isMaxRight) {
                    maximumSize = Dimension(Int.MAX_VALUE, rightHeight)
                } else {
                    right.maximumSize = Dimension(right.preferredSize.width, rightHeight)
                }

                if (marginBetween > 0) {
                    right.border = JBUI.Borders.empty(0, marginBetween, 0, 0)
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

        val syncModePanel = createPairPanel(syncModeLabel, syncModeComboBox, isMaxRight = false, marginHorizontal = 0)
        syncModePanel.preferredSize = Dimension(300, syncModePanel.preferredSize.height)
        val actionLinkPanel = createPairPanel(applyServerActionLink, copyRemoteConfigActionLink, isMaxRight = false, marginBetween = 8, marginHorizontal = 0)
        val topPanel = createPairPanel(syncModePanel, actionLinkPanel, isAlignEnd = true, marginHorizontal = 0)
        val remoteRows = mutableListOf<JComponent>(topPanel)

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
                remoteRows.add(createPairPanel(it.first, it.second, leftWidth = 260))
            }
        }
        remoteRows.forEachIndexed { index, row ->
            remoteCompilePanel.add(row)
            if (index != remoteRows.lastIndex) {
                remoteCompilePanel.add(Box.createVerticalStrut(5))
            }
        }
        remoteCompilePanel.maximumSize = Dimension(Int.MAX_VALUE, remoteCompilePanel.preferredSize.height)

        revalidate()
        repaint()
    }

    private fun saveTextToClipboard(text: String) {
        val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val stringSelection = StringSelection(text)
        clipboard.setContents(stringSelection, null)
    }

    private fun getUsername(project: Project): String {
        val defaultName = System.getProperty("user.name") ?: "jugg_user_unknown"
        val projectDir = project.basePath ?: return defaultName
        return GitManager.createGitManagerAndTrySearchParent(File(projectDir)).userName ?: defaultName
    }
}
