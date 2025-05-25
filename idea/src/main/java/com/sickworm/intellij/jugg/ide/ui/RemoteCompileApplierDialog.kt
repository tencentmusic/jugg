package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogWrapper
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.server.JuggRemoteCompileApplier
import com.sickworm.intellij.jugg.server.protocols.InteractionProcessFlow
import com.sickworm.intellij.jugg.server.protocols.InteractionStep
import com.sickworm.intellij.jugg.server.protocols.InteractionStepDesc
import com.sickworm.intellij.jugg.server.protocols.RemoteServerInfo
import kotlinx.coroutines.*
import java.awt.*
import java.awt.image.BufferedImage
import java.lang.ref.WeakReference
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLDocument

class RemoteCompileApplierDialog(username: String, private val logger: Logger) : DialogWrapper(true) {

    private val mainFrame = MainFrame(username, JuggRemoteCompileApplier(logger), logger, this)

    val result: RemoteServerInfo? get() = mainFrame.result

    init {
        title = "Remote Compile Application"
        init()

    }

    override fun createActions(): Array<Action> {
        return emptyArray()
    }

    override fun createCenterPanel(): JComponent {
        return mainFrame
    }

    fun close() {
        close(CLOSE_EXIT_CODE)
    }

    override fun dispose() {
        logger.debug("dispose")
        super.dispose()
        mainFrame.release()
    }

    companion object {

        fun showAndGetResult(username: String, logger: Logger): RemoteServerInfo? {
            var result: RemoteServerInfo? = null
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = RemoteCompileApplierDialog(username, logger)
                dialog.showAndGet()
                result = dialog.result
            }
            return result
        }

    }
}

private class MainFrame(private val username: String,
                        private val applier: JuggRemoteCompileApplier,
                        private val logger: Logger,
                        private val parent: RemoteCompileApplierDialog) : JPanel() {
    private lateinit var interactionProcessFlow: InteractionProcessFlow // lateinit as it's fetched async
    @Volatile
    private lateinit var currentStep: InteractionStep // lateinit as it's fetched async

    private lateinit var stepList: JList<InteractionStepDesc>
    private lateinit var nextButton: JButton
    private lateinit var cardLayout: CardLayout
    private lateinit var contentCards: JPanel
    private val listModel = DefaultListModel<InteractionStepDesc>()
    private lateinit var loadingPanel: JPanel // Keep a reference to remove it later

    // Icons for different states
    private val completedIcon = createCircleIcon(Color.BLUE, true)
    private val currentIcon = createCircleIcon(Color.BLUE, false)
    private val pendingIcon = createCircleIcon(Color.GRAY, false)

    // Coroutine scope for background tasks
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    val result: RemoteServerInfo? get() = if (::currentStep.isInitialized) currentStep.remoteServerInfo else null

    companion object {
        private const val PANEL_WIDTH = 600
        private const val PANEL_HEIGHT = 380
    }

    init {
        // Show a loading state initially
        initLoadingUI() // This will add the loading panel but not make the frame visible yet

        // Fetch initial flow data in the background
        preferredSize = Dimension(PANEL_WIDTH, PANEL_HEIGHT)
        coroutineScope.launch {
            val flow = applier.getInitialProcessFlow(username, isWindows)
            SwingUtilities.invokeLater {
                initMainUI(flow)
            }
        }
    }

    fun release() {
        logger.debug("release")
        if (::interactionProcessFlow.isInitialized) {
            CoroutineScope(Dispatchers.IO).launch {
                applier.getInteractionStep(interactionProcessFlow.quitUrl, interactionProcessFlow.token)
            }
        }
        coroutineScope.cancel()
    }

    private fun initMainUI(flow: InteractionProcessFlow?) {
        try {
            if (flow == null) {
                showErrorDialog("Server Error", "Server is unable to start an application.")
                return
            }

            interactionProcessFlow = flow
            currentStep = interactionProcessFlow.firstStep

            // Remove loading panel and set up main UI
            val mainContentPane = this
            mainContentPane.remove(loadingPanel) // Remove the loading panel
            mainContentPane.add(createMainSplitPane(), BorderLayout.CENTER)

            initComponents() // Initialize components that depend on currentStep
            updateStepList()
            selectCurrentStepInList()

            mainContentPane.revalidate()
            mainContentPane.repaint()
        } catch (e: Exception) {
            logger.debug("Error while loading initial setup flow: ${e.message}")
            // If loading fails, remove loading panel, show error, then make frame visible
            val errorContentPane = this
            if (::loadingPanel.isInitialized && loadingPanel.parent == errorContentPane) {
                errorContentPane.remove(loadingPanel)
            }
            // It's important to show the error dialog on the EDT
            showErrorDialog("Internal Error", "Failed to load initial setup flow: ${e.message}")
        }
    }

    private fun initLoadingUI() {
        loadingPanel = JPanel(GridBagLayout()) // Use GridBagLayout for centering
        loadingPanel.add(JLabel("Loading setup wizard, please wait..."))
        this.add(loadingPanel, BorderLayout.CENTER)
    }

    private fun createMainSplitPane(): JSplitPane {
        val stepsPanel = JPanel(BorderLayout())
        stepsPanel.border = null

        stepList = JList(listModel)
        stepList.preferredSize = Dimension(PANEL_WIDTH / 4, PANEL_HEIGHT)
        stepList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        stepList.isEnabled = false
        stepList.cellRenderer = StepListCellRenderer()
        stepList.border = BorderFactory.createEmptyBorder(12, 8, 8, 8)
        stepsPanel.add(stepList, BorderLayout.CENTER)

        val contentPanel = JPanel(BorderLayout())
        cardLayout = CardLayout()
        contentCards = JPanel(cardLayout)
        contentCards.preferredSize = Dimension(PANEL_WIDTH / 4 * 3, PANEL_HEIGHT)
        contentPanel.add(contentCards, BorderLayout.CENTER)

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, stepsPanel, contentPanel)
        splitPane.dividerLocation = 200
        splitPane.resizeWeight = 0.0
        splitPane.isOneTouchExpandable = false // Do not allow hiding/showing panels
        splitPane.dividerSize = 1 // Set divider to a thin gray line
        splitPane.border = null

        nextButton = JButton("Next >")
        nextButton.minimumSize = Dimension(120, nextButton.preferredSize.height)
        nextButton.addActionListener { handleNextButton() }
        val navigationPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        navigationPanel.add(nextButton)

        val splitPane2 = JSplitPane(JSplitPane.VERTICAL_SPLIT, splitPane, navigationPanel)
        splitPane2.dividerLocation = 330
        splitPane2.resizeWeight = 0.0
        splitPane2.isOneTouchExpandable = false // Do not allow hiding/showing panels
        splitPane2.dividerSize = 1 // Set divider to a thin gray line
        splitPane2.border = null

        return splitPane2
    }

    private fun initComponents() {
        // Initial panel based on the first step from the flow
        contentCards.add(createStepPanel(currentStep), currentStep.stepName)
        cardLayout.show(contentCards, currentStep.stepName)
    }

    private fun updateStepList() {
        if (::interactionProcessFlow.isInitialized) { // Check if flow is initialized
            listModel.clear()
            listModel.addAll(interactionProcessFlow.stepList)
            if (::stepList.isInitialized) stepList.repaint()
        }
    }

    private fun selectCurrentStepInList() {
        if (::interactionProcessFlow.isInitialized && ::currentStep.isInitialized && ::stepList.isInitialized) {
            val currentIndex = interactionProcessFlow.stepList.indexOfFirst { it.stepName == currentStep.stepName }
            if (currentIndex != -1) {
                stepList.repaint()
            }
        }
    }

    private fun handleNextButton() {
        if (!::currentStep.isInitialized || currentStep.nextStepUrl == null) {
            if (::currentStep.isInitialized && currentStep.nextStepUrl == null) {
                // This is the last step
                logger.debug("finish remoteServerInfo: ${currentStep.remoteServerInfo != null}")
                close()
            }
            return
        }

        nextButton.isEnabled = false // Disable button during fetch
        nextButton.text = "Requesting..."

        coroutineScope.launch {
            try {
                val inputGetter = inputGetter.map { it.get()?.text ?: "" }
                val nextInteractionStep = applier.getInteractionStep(currentStep.nextStepUrl!!, interactionProcessFlow.token, inputGetter)
                logger.debug("nextInteractionStep: $nextInteractionStep")

                SwingUtilities.invokeLater {
                    if (nextInteractionStep == null) {
                        showErrorDialog("Server Error", "Failed to run next step. Step data not found or invalid token.")
                        return@invokeLater
                    }
                    if (!nextInteractionStep.isSuccess && nextInteractionStep.isCanRetryWhenFailed) {
                        showErrorDialog(nextInteractionStep.title, nextInteractionStep.htmlText, true)
                        return@invokeLater
                    }

                    currentStep = nextInteractionStep
                    if (contentCards.components.none { it.name == currentStep.stepName }) {
                        contentCards.add(createStepPanel(currentStep), currentStep.stepName)
                        if (currentStep.checkFinishUrl != null) {
                            checkStepFinish(currentStep)
                        } else {
                            updateNextButtonState() // Re-evaluate button state after loading
                        }
                    }
                    cardLayout.show(contentCards, currentStep.stepName)
                    selectCurrentStepInList()
                }
            } catch (e: Exception) {
                logger.debug("Error while fetching next step: ", e)
                showErrorDialog("Server Error", "Failed to run next step. Error: ${e.message}")
            }
        }
    }

    private fun updateNextButtonState() {
        if (::nextButton.isInitialized && ::currentStep.isInitialized) {
            nextButton.text = if (currentStep.nextStepUrl == null) "Finish" else "Next >"
            // Potentially add more logic here, e.g., disable if current step has pending input/options
            nextButton.isEnabled = true
        }
    }

    private var inputGetter: List<WeakReference<JTextField>> = emptyList()

    private fun createStepPanel(step: InteractionStep): JPanel {
        val editorPane = JEditorPane()
        editorPane.maximumSize = Dimension(Int.MAX_VALUE, PANEL_HEIGHT / 3 * 2)
        editorPane.contentType = "text/html"
        editorPane.isEditable = false
        editorPane.text = step.htmlText
        editorPane.border = BorderFactory.createEmptyBorder(0, 16, 0, 16)
        editorPane.addHyperlinkListener { event ->
            if (HyperlinkEvent.EventType.ACTIVATED == event.eventType) {
                Desktop.getDesktop().browse(event.url.toURI())
            }
        }
        val doc = editorPane.document as HTMLDocument
        doc.putProperty("IgnoreCharsetDirective", true)
        editorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)

        val contentPanel = JPanel()
        contentPanel.maximumSize = Dimension(Int.MAX_VALUE, PANEL_HEIGHT / 3 * 2)
        val layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.layout = layout
        contentPanel.add(editorPane)
        contentPanel.background = editorPane.background

        if (step.inputTips.isNotEmpty()) {
            val inputGetter = mutableListOf<WeakReference<JTextField>>()
            step.inputTips.forEach { inputTip ->
                val optionPanel = JPanel()
                val optionLayout = BoxLayout(optionPanel, BoxLayout.X_AXIS)
                optionPanel.layout = optionLayout
                optionPanel.background = editorPane.background
                optionPanel.border = BorderFactory.createEmptyBorder(0, 16, 0, 16)
                optionPanel.alignmentX = Component.RIGHT_ALIGNMENT

                val label = JLabel(inputTip)
                label.border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
                optionPanel.add(label)

                val textField = JTextField()
                textField.maximumSize = Dimension(400, 24)
                optionPanel.add(textField)

                contentPanel.add(optionPanel)
                inputGetter.add(WeakReference(textField))
            }
            this.inputGetter = inputGetter
        }

        return contentPanel
    }

    private fun checkStepFinish(step: InteractionStep) {
        nextButton.isEnabled = false
        nextButton.text = "Waiting to finish..."

        coroutineScope.launch {

            while (currentStep == step) {
                delay(2000)

                if (currentStep != step) {
                    logger.debug("checkStepFinish break for step changed")
                    break
                }
                val result = applier.getInteractionStep(step.checkFinishUrl!!, interactionProcessFlow.token)
                if (currentStep != step) {
                    logger.debug("checkStepFinish break for step changed")
                    break
                }
                logger.debug("${step.stepName} wait finish result: $result")

                if (result == null) {
                    showErrorDialog("Server Error", "Server Error: Failed to get response.")
                    break
                }
                if (!result.isSuccess) {
                    if (result.stepName == step.stepName) {
                        // still waiting
                    } else {
                        // failed
                        showErrorDialog(result.title, result.htmlText, false)
                        break
                    }
                } else {
                    // success
                    SwingUtilities.invokeLater {
                        nextButton.isEnabled = true
                        nextButton.text = "Next >"
                    }
                    break
                }
            }
        }
    }

    private fun showErrorDialog(title: String, message: String, isCanRetry: Boolean = false) {
        val messageHtml = JLabel(message)
        JOptionPane.showMessageDialog(this, messageHtml, title, JOptionPane.ERROR_MESSAGE)
        if (!isCanRetry) {
            close()
        }
    }

    inner class StepListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, // Use JList<*> for wildcard type
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is InteractionStepDesc && ::interactionProcessFlow.isInitialized && ::currentStep.isInitialized) {
                text = value.stepName
                val currentStepIndexInFlow = interactionProcessFlow.stepList.indexOfFirst { it.stepName == currentStep.stepName }
                icon = when {
                    index < currentStepIndexInFlow -> completedIcon
                    index == currentStepIndexInFlow -> currentIcon
                    else -> pendingIcon
                }
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                return this
            }
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            return this
        }
    }

    private fun createCircleIcon(color: Color, filled: Boolean): ImageIcon {
        val diameter = 16
        val image = BufferedImage(diameter, diameter, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.color = color
        if (filled) {
            g2d.fillOval(0, 0, diameter, diameter)
            g2d.color = Color.WHITE
            g2d.stroke = BasicStroke(2f)
            g2d.drawLine(4, 8, 7, 11)
            g2d.drawLine(7, 11, 12, 6)
        } else {
            if (color == Color.BLUE) {
                g2d.fillOval(0, 0, diameter, diameter)
            } else {
                g2d.drawOval(0, 0, diameter - 1, diameter - 1)
            }
        }
        g2d.dispose()
        return ImageIcon(image)
    }

    private fun close() {
        logger.debug("close")
        SwingUtilities.invokeLater {
            parent.close()
        }
    }
}
