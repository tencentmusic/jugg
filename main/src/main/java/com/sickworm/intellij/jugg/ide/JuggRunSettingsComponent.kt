package com.sickworm.intellij.jugg.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.gradle.compile.ReportConfirmDialog
import com.sickworm.intellij.jugg.gradle.compile.ReportProgressDialog
import com.sickworm.intellij.jugg.server.JuggReporter
import com.sickworm.intellij.jugg.server.RunConfigurationTemplate
import com.sickworm.intellij.jugg.server.toRunConfigurationTemplate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.awt.Component
import java.awt.Dimension
import java.awt.GridLayout
import java.lang.ref.WeakReference
import java.util.function.Consumer
import javax.swing.*

/**
 * Run configuration settings UI
 */
class JuggRunSettingsComponent : JComponent() {

    private val topButtonsContainer: JPanel = JPanel().also {
        it.border = JBUI.Borders.empty(0, 4)
        it.layout = BoxLayout(it, BoxLayout.X_AXIS)
    }

    private var selectTemplateButton: DropDownLink<String> = createSelectTemplateButton()
    private val moreOptionsButton: DropDownLink<String> = createMoreOptionsButton()
    private val templateUpdateListener = {
        SwingUtilities.invokeLater {
            updateTopButtons()
        }
    }

    private val compileCommandLabel = JLabel("Compile command:")
    val compileCommandTextField = JTextField()
    private val outputApkNameLabel = JLabel("Output APK name:")
    val outputApkNameTextField = JTextField()
    val enableRemoteCompileCheckBox = JCheckBox("Enable iFt remote compile")
    val enableSyncAllProjectsCheckBox = JCheckBox("Enable multiple projects mode (sync and fetch all projects in iFT root dir)")

    private val reportIssueActionLink = ActionLink("Report issues")

    private val tipsLabel = JLabel("Notice: Do not modify the default value if you don't know that it is").also {
        it.foreground = JBColor.GRAY
    }

    private val userLabel = JLabel("SSH user:")
    val userTextField = JTextField()
    private val passwordLabel = JLabel("SSH password:")
    val passwordTextField = JBPasswordField()
    private val ipLabel = JLabel("SSH host:")
    val ipTextField = JTextField()
    private val portLabel = JLabel("SSH port:")
    val portTextField = JTextField()
    private val localToRemoteIftConfigNameLabel = JLabel("Local to remote IFT config name:")
    val localToRemoteIftConfigNameTextField = JTextField()
    private val localToRemoteSyncPathLabel = JLabel("Local to remote sync path:")
    val localToRemoteSyncPathTextField = JTextField()
    private val remoteSyncPathLabel = JLabel("Remote sync path (optional):")
    val remoteSyncPathTextField = JBTextField().also {
        it.emptyText.text = "default \$HOME/{Local to remote IFT config name}, e.g. /root/remote"
    }
    private val remoteToLocalIftConfigNameLabel = JLabel("Remote to local IFT config name:")
    private val remoteToLocalSyncPathLabel = JLabel("Remote to local sync path:")
    val remoteToLocalSyncPathTextField = JTextField()
    val remoteToLocalIftConfigNameTextField = JTextField()
    private val httpProxyIpLabel = JLabel("HTTP proxy host:")
    val httpProxyIpTextField = JTextField()
    private val httpProxyPortLabel = JLabel("HTTP proxy port:")
    val httpProxyPortTextField = JTextField()

    private val remoteComponentList = listOf<Pair<JComponent, JComponent?>>(
        Pair(tipsLabel, null),
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

    init {
        layout = GridLayout(0, 1, 5, 5)

        add(topButtonsContainer)
        updateTopButtons()
        JuggSettings.templateListUpdateListener = WeakReference(templateUpdateListener)

        addPair(compileCommandLabel, compileCommandTextField, leftWidth = 140)
        addPair(outputApkNameLabel, outputApkNameTextField, leftWidth = 140)

        addPair(enableRemoteCompileCheckBox, reportIssueActionLink, leftWidth = 260, isAlignEnd = true)

        enableRemoteCompileCheckBox.addActionListener {
            val isSelected = enableRemoteCompileCheckBox.isSelected
            updateRemoteUi(isSelected)
        }
        updateRemoteUi(enableRemoteCompileCheckBox.isSelected)
    }

    private fun createSelectTemplateButton(): DropDownLink<String> {
        return DropDownLink(
            "Choose template",
            JuggSettings.compileTemplateList.map { it.templateName}) { selectedTemplateName ->
            val selectedTemplate = JuggSettings.compileTemplateList.find { it.templateName == selectedTemplateName }
            if (selectedTemplate != null) {
                updateUi(selectedTemplate)
            }
        }.also {
            it.border = JBUI.Borders.empty(0, 4)
        }
    }

    private fun createMoreOptionsButton(): DropDownLink<String> {
        val popupBuilder: (DropDownLink<String>) -> JBPopup = { link ->
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(JuggMoreOptionsItem.options)
                .setRenderer(MyLinkCellRenderer(link))
                .setItemChosenCallback { selectOptions ->
                    selectOptions.isSelected = !selectOptions.isSelected
                }
                .createPopup()
        }

        return DropDownLink("More options", popupBuilder).also {
            it.border = JBUI.Borders.empty(0, 4)
        }
    }

    private fun updateTopButtons() {
        topButtonsContainer.removeAll()
        selectTemplateButton = createSelectTemplateButton()
        topButtonsContainer.add(Box.createHorizontalGlue())
        topButtonsContainer.add(selectTemplateButton)
        topButtonsContainer.add(moreOptionsButton)
    }

    private fun updateUi(settings: RunConfigurationTemplate) {
        updateUi(settings, isTemplate = true)
    }

    fun updateUi(settings: JuggRunConfigurationOptions) {
        updateUi(settings.toRunConfigurationTemplate(), isTemplate = false)
    }

    private fun updateUi(settings: RunConfigurationTemplate, isTemplate: Boolean) {
        compileCommandTextField.text = settings.compileCommand
        outputApkNameTextField.text = settings.outputApkName
        enableRemoteCompileCheckBox.isSelected = settings.isRemoteCompile
        updateRemoteUi(settings.isRemoteCompile)
        enableSyncAllProjectsCheckBox.isSelected = settings.isSyncAllProjects
        userTextField.text = settings.remoteSshUser
        if (isTemplate) {
            passwordTextField.text = ""
            passwordTextField.emptyText.text = settings.remoteSshPassword ?: ""
        } else {
            passwordTextField.text = settings.remoteSshPassword
        }
        ipTextField.text = settings.remoteSshIp
        portTextField.text = settings.remoteSshPort.toString()
        httpProxyIpTextField.text = settings.httpProxyIp ?: ""
        httpProxyPortTextField.text = settings.httpProxyPort.toString()
        localToRemoteIftConfigNameTextField.text = settings.localToRemoteIftConfigName
        localToRemoteSyncPathTextField.text = settings.localToRemoteSyncPath
        remoteSyncPathTextField.text = settings.remoteSyncPath
        remoteToLocalIftConfigNameTextField.text = settings.remoteToLocalIftConfigName
        remoteToLocalSyncPathTextField.text = settings.remoteToLocalSyncPath
    }

    fun initUpload(project: Project) {
        if (reportIssueActionLink.actionListeners.isEmpty()) {
            reportIssueActionLink.addActionListener {
                doUpload(project)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun doUpload(project: Project) {
        val dialog = ReportProgressDialog()
        val isConfirmed = ReportConfirmDialog().showAndGet()
        if (!isConfirmed) {
            return
        }

        val deferred = JuggReporter(project).reportAndUploadLogs()
        deferred.invokeOnCompletion {
            val uploadResult = deferred.getCompleted()
            SwingUtilities.invokeLater {
                dialog.setResult(uploadResult)
            }
        }
        dialog.show()
    }

    private fun addPair(left: JComponent?, right: JComponent?, leftWidth: Int, isAlignEnd: Boolean = false): JPanel {
        val jPanel = JPanel()
        jPanel.run {
            border = JBUI.Borders.empty(0, 4)
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            left?.let {
                add(left)
                val realLeftWidth = if (right == null) Int.MAX_VALUE else leftWidth
                left.preferredSize = Dimension(realLeftWidth, left.preferredSize.height)
            }

            if (isAlignEnd) {
                add(Box.createHorizontalGlue())
            }

            right?.let {
                add(right)
                maximumSize = Dimension(Int.MAX_VALUE, right.preferredSize.height)
            }
        }
        add(jPanel)

        return jPanel
    }

    private fun updateRemoteUi(isSelected: Boolean) {
        remoteComponentList.forEach {
            if (isSelected) {
                it.first.parent?.let { parent ->
                    add(parent)
                } ?: run {
                    addPair(it.first, it.second, leftWidth = 260)
                }
            } else {
                it.first.parent?.let { parent ->
                    remove(parent)
                }
            }
        }
        revalidate()
        repaint()
    }
}

/** Copied from [DropDownLink] and modified */
private class MyLinkCellRenderer(private val link: Component) : DefaultListCellRenderer() {
    private fun coerce(size: Dimension): Dimension {
        size.width = size.width.coerceAtLeast(link.preferredSize.width)
        size.height = size.height.coerceAtLeast(JBUIScale.scale(22))
        return size
    }

    override fun getMinimumSize() = coerce(super.getMinimumSize())
    override fun getPreferredSize() = coerce(super.getPreferredSize())
    override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean): Component {
        super.getListCellRendererComponent(list, value, index, selected, false)
        border = JBUI.Borders.empty(0, 8)
        if (value is JuggMoreOptionsItem) {
            text = value.name
            icon = if (value.isSelected) {
                AllIcons.Actions.SetDefault
            } else {
                null
            }
        }
        return this
    }
}

