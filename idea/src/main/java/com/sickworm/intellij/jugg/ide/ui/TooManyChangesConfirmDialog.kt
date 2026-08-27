package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.sickworm.intellij.jugg.compiler.TooManyChangesInfo
import com.sickworm.intellij.jugg.compiler.ui.TooManyChangesConfirmResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Confirms whether a large incremental compile should fall back to Gradle.
 * The default action is immediately available; continuing incremental compile waits for a short countdown.
 */
class TooManyChangesConfirmDialog(
    titleArg: String,
    content: String,
    private val fallbackButtonText: String,
    private val continueButtonText: String,
) : DialogWrapper(true), CoroutineScope by CoroutineScope(Dispatchers.IO) {

    private val mainPanel: JPanel = JPanel(BorderLayout())

    private val fallbackButton = object : AbstractAction(fallbackButtonText) {
        init {
            putValue("DefaultAction", true)
        }
        override fun actionPerformed(e: ActionEvent?) {
            result = TooManyChangesConfirmResult.FALLBACK
            close(CLOSE_EXIT_CODE)
        }
    }

    private val continueButton = object : AbstractAction(continueButtonText) {
        override fun actionPerformed(e: ActionEvent?) {
            result = TooManyChangesConfirmResult.CONTINUE
            close(CLOSE_EXIT_CODE)
        }
    }

    private var result: TooManyChangesConfirmResult = TooManyChangesConfirmResult.CANCEL
    private var confirmCountDown = 2

    init {
        title = titleArg
        mainPanel.add(JBLabel(content), BorderLayout.CENTER)
        isResizable = true
        init()
        launch {
            while (confirmCountDown > 0) {
                delay(1000)
                confirmCountDown--
                SwingUtilities.invokeLater(::updateConfirmText)
            }
        }
    }

    override fun createCenterPanel(): JComponent = mainPanel

    override fun createActions(): Array<Action> {
        updateConfirmText()
        return arrayOf(fallbackButton)
    }

    override fun createLeftSideActions(): Array<Action> {
        return arrayOf(continueButton)
    }

    private fun updateConfirmText() {
        if (confirmCountDown > 0) {
            continueButton.putValue("Name", "$continueButtonText(${confirmCountDown}s)")
            continueButton.isEnabled = false
        } else {
            continueButton.putValue("Name", continueButtonText)
            continueButton.isEnabled = true
        }
    }

    companion object {

        fun showAndGetResult(info: TooManyChangesInfo): TooManyChangesConfirmResult {
            lateinit var dialog: TooManyChangesConfirmDialog
            ApplicationManager.getApplication().invokeAndWait {
                dialog = TooManyChangesConfirmDialog(
                    titleArg = "Too many changes for incremental compile",
                    content = buildContent(info),
                    fallbackButtonText = "Fallback to Gradle",
                    continueButtonText = "Continue Incremental Compile",
                )
                dialog.showAndGet()
            }
            return dialog.result
        }

        private fun buildContent(info: TooManyChangesInfo): String {
            return """
                <html>
                <p>Jugg will use Gradle because compiling this many files incrementally is usually slower.</p>
                <p>
                Kotlin files: ${info.kotlinFileCount}<br>
                Java files: ${info.javaFileCount}<br>
                Modules: ${info.moduleCount}
                </p>
                <p>Continue incremental only if you still want Jugg to handle this large change.</p>
                </html>
            """.trimIndent()
        }
    }
}
