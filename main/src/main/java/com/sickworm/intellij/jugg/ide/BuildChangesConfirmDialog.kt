package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.sickworm.intellij.jugg.deploy.diff.BuildDiffRequestPanel
import com.sickworm.intellij.jugg.deploy.diff.BuildFileDiffRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.*
import kotlin.Array
import kotlin.Pair
import kotlin.String
import kotlin.arrayOf


class BuildChangesConfirmDialog(
    project: Project,
    titleArg: String,
    content: String,
    private val findChangeButtonText: String,
    private val fallbackButtonText: String,
    private val ignoreButtonText: String,
    changedBuildFiles: List<Pair<File, File?>>,
) : DialogWrapper(true), CoroutineScope by CoroutineScope(Dispatchers.IO) {

    private val mainPanel: JPanel = JPanel(BorderLayout())

    private val findChangeButton = object : AbstractAction(findChangeButtonText) {
        init {
            putValue("DefaultAction", true)
        }
        override fun actionPerformed(e: ActionEvent?) {
            result = Result.FIND_CHANGE
            close(CLOSE_EXIT_CODE)
        }
    }
    private val fallbackButton = object : AbstractAction(fallbackButtonText) {
        override fun actionPerformed(e: ActionEvent?) {
            result = Result.FALLBACK
            close(CLOSE_EXIT_CODE)
        }
    }
    private val ignoreButton = object : AbstractAction(ignoreButtonText) {
        override fun actionPerformed(e: ActionEvent?) {
            result = Result.IGNORE_CHANGE
            close(CLOSE_EXIT_CODE)
        }
    }

    private var result: Result = Result.CANCEL

    private var confirmCountDown = 3

    init {
        title = titleArg

        val jLabel = JBLabel(content)
        val diffPanel = createDiffPanel(project, this, changedBuildFiles)
        mainPanel.add(jLabel, BorderLayout.NORTH)
        mainPanel.add(diffPanel, BorderLayout.CENTER)
        mainPanel.preferredSize = Dimension(800, 500)
        mainPanel.add(JBLabel("""
            <html><p>
            <font color="#EB984E"><b>Caution</b></font>: This may cause unexpected build result, Please check changes carefully.
            <br> <br></p></html>
            """.trimIndent()
        ), BorderLayout.SOUTH)

        isResizable = true
        init()

        launch {
            while (confirmCountDown > 0) {
                delay(1000)
                confirmCountDown--
                updateConfirmTextAsync()
            }
        }
    }

    private fun createDiffPanel(project: Project, dialogWrapper: DialogWrapper, changedBuildFiles: List<Pair<File, File?>>): JComponent {
        val diffRequestPanel = BuildDiffRequestPanel(project)
        Disposer.register(dialogWrapper.disposable, diffRequestPanel)
        changedBuildFiles.forEach {
            val request = BuildFileDiffRequest(project, it.first, it.second)
            diffRequestPanel.setRequest(request)
        }
        return diffRequestPanel.component
    }

    override fun createCenterPanel(): JComponent {
        return mainPanel
    }

    override fun createActions(): Array<Action> {
        val actions = ArrayList<Action>()
        actions.add(fallbackButton)
        actions.add(findChangeButton)
        updateConfirmText()
        return actions.toTypedArray()
    }

    private fun updateConfirmTextAsync() {
        SwingUtilities.invokeLater(::updateConfirmText)
    }

    private fun updateConfirmText() {
        if (confirmCountDown > 0) {
            findChangeButton.putValue("Name", "$findChangeButtonText(${confirmCountDown}s)")
            findChangeButton.isEnabled = false
            ignoreButton.putValue("Name", "$ignoreButtonText(${confirmCountDown}s)")
            ignoreButton.isEnabled = false
        } else {
            findChangeButton.putValue("Name", findChangeButtonText)
            findChangeButton.isEnabled = true
            ignoreButton.putValue("Name", ignoreButtonText)
            ignoreButton.isEnabled = true
        }
    }

    override fun createLeftSideActions(): Array<Action> {
        return arrayOf(ignoreButton)
    }

    companion object {

        fun showAndGetResult(
            project: Project,
            changedBuildFiles: List<Pair<File, File?>>,
            title: String = "Build Files Changed Confirm",
            okButtonText: String = "Find out the Changed Libraries!",
            fallbackButtonText: String = "Fallback to Gradle",
            leftButtonText: String = "Ignore Gradle Changes",
        ): Result {
            val changesFileString = changedBuildFiles.joinToString("\n") {
                "<li><font color=\"#2ECC71\">${it.first.relativeTo(File(project.basePath!!)).path}</font></li>"
            }
            val content = """<html>
                |<p>Changed files:
                |<ul>
                |$changesFileString
                |</ul>
                |</p>
                |</html>
            """.trimMargin()

            lateinit var dialog: BuildChangesConfirmDialog
            ApplicationManager.getApplication().invokeAndWait {
                dialog = BuildChangesConfirmDialog(
                    project, title, content, okButtonText, fallbackButtonText, leftButtonText, changedBuildFiles)
                dialog.showAndGet()
            }
            return dialog.result
        }
    }

    enum class Result {
        FIND_CHANGE, IGNORE_CHANGE, CANCEL, FALLBACK
    }
}
