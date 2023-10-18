package com.sickworm.intellij.jugg.ide

import com.intellij.ui.JBColor
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.*


class JuggRunSettingsComponent : JComponent() {
    private val compileCommandLabel = JLabel("Compile command:")
    val compileCommandTextField = JTextField()
    private val outputApkNameLabel = JLabel("Output APK name:")
    val outputApkNameTextField = JTextField()
    val enableRemoteCompileCheckBox = JCheckBox("Enable iFt remote compile")

    private val tipsLabel = JLabel("Notice: Do not modify the default value if you don't know that it is").also {
        it.foreground = JBColor.GRAY
    }

    private val userLabel = JLabel("SSH user:")
    val userTextField = JTextField()
    private val passwordLabel = JLabel("SSH password:")
    val passwordTextField = JPasswordField()
    private val ipLabel = JLabel("SSH IP:")
    val ipTextField = JTextField()
    private val portLabel = JLabel("SSH port:")
    val portTextField = JTextField()
    private val localToRemoteIftConfigNameLabel = JLabel("Local to remote IFT config name:")
    val localToRemoteIftConfigNameTextField = JTextField()
    private val localToRemoteSyncPathLabel = JLabel("Local to remote sync path:")
    val localToRemoteSyncPathTextField = JTextField()
    private val remoteToLocalIftConfigNameLabel = JLabel("Remote to local IFT config name:")
    private val remoteToLocalSyncPathLabel = JLabel("Remote to local sync path:")
    val remoteToLocalSyncPathTextField = JTextField()
    val remoteToLocalIftConfigNameTextField = JTextField()
    private val httpProxyIpLabel = JLabel("HTTP proxy IP:")
    val httpProxyIpTextField = JTextField()
    private val httpProxyPortLabel = JLabel("HTTP proxy port:")
    val httpProxyPortTextField = JTextField()

    private val remoteComponentList = listOf<Pair<JComponent, JComponent?>>(
        Pair(tipsLabel, null),
        Pair(userLabel, userTextField),
        Pair(passwordLabel, passwordTextField),
        Pair(ipLabel, ipTextField),
        Pair(portLabel, portTextField),
        Pair(localToRemoteIftConfigNameLabel, localToRemoteIftConfigNameTextField),
        Pair(localToRemoteSyncPathLabel, localToRemoteSyncPathTextField),
        Pair(remoteToLocalIftConfigNameLabel, remoteToLocalIftConfigNameTextField),
        Pair(remoteToLocalSyncPathLabel, remoteToLocalSyncPathTextField),
        Pair(httpProxyIpLabel, httpProxyIpTextField),
        Pair(httpProxyPortLabel, httpProxyPortTextField),
    )

    init {
        layout = GridLayout(0, 1, 5, 5)

        addPair(compileCommandLabel, compileCommandTextField, leftWidth = 120)
        addPair(outputApkNameLabel, outputApkNameTextField, leftWidth = 120)

        add(enableRemoteCompileCheckBox)

        enableRemoteCompileCheckBox.addActionListener {
            val isSelected = enableRemoteCompileCheckBox.isSelected
            updateRemoteUi(isSelected)
        }
        updateRemoteUi(enableRemoteCompileCheckBox.isSelected)
    }

    fun updateUi(settings: JuggRunConfigurationOptions) {
        compileCommandTextField.text = settings.compileCommand
        outputApkNameTextField.text = settings.outputApkName
        enableRemoteCompileCheckBox.isSelected = settings.isRemoteCompile
        updateRemoteUi(settings.isRemoteCompile)
        userTextField.text = settings.remoteSshUser
        passwordTextField.text = settings.remoteSshPassword
        ipTextField.text = settings.remoteSshIp
        portTextField.text = settings.remoteSshPort.toString()
        httpProxyIpTextField.text = settings.httpProxyIp ?: ""
        httpProxyPortTextField.text = settings.httpProxyPort.toString()
        localToRemoteIftConfigNameTextField.text = settings.localToRemoteIftConfigName
        localToRemoteSyncPathTextField.text = settings.localToRemoteSyncPath
        remoteToLocalIftConfigNameTextField.text = settings.remoteToLocalIftConfigName
        remoteToLocalSyncPathTextField.text = settings.remoteToLocalSyncPath
    }

    private fun addPair(left: JComponent, right: JComponent?, leftWidth: Int) {
        val jPanel = JPanel()
        jPanel.run {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(left)
            val realLeftWidth = if (right == null) Int.MAX_VALUE else leftWidth
            left.preferredSize = Dimension(realLeftWidth, left.preferredSize.height)

            right?.let {
                add(right)
                maximumSize = Dimension(Int.MAX_VALUE, right.preferredSize.height)
            }
        }
        add(jPanel)
    }

    private fun updateRemoteUi(isSelected: Boolean) {
        remoteComponentList.forEach {
            if (isSelected) {
                it.first.parent?.let { parent ->
                    add(parent)
                } ?: run {
                    addPair(it.first, it.second, leftWidth = 220)
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

