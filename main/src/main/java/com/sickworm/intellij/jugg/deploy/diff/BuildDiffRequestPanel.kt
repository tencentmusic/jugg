package com.sickworm.intellij.jugg.deploy.diff

import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.DiffTool
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffTool
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.DiffUserDataKeysEx.ScrollToPolicy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * @see [com.intellij.diff.impl.DiffRequestPanelImpl]
 */
class BuildDiffRequestPanel(project: Project) : DiffRequestPanel {

    private val myPanel: JPanel = object : JPanel(BorderLayout()) {
        override fun addNotify() {
            super.addNotify()
            myProcessor.updateRequest()
        }
    }

    private val userDataHolder = UserDataHolderBase().apply {
        putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        putUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE, true)
        putUserData(DiffUserDataKeys.DO_NOT_CHANGE_WINDOW_TITLE, true)
        putUserData(DiffUserDataKeysEx.MERGE_EDITOR_FLAG, true)
        // see com.intellij.diff.tools.util.base.TextDiffSettingsHolder.getSettings
//        "ChangesView" -> {TextDiffSettingsHolder$PlaceSettings@71831} PlaceSettings(HIGHLIGHT_POLICY=BY_WORD, IGNORE_POLICY=DEFAULT, SHOW_WHITESPACES=false, SHOW_LINE_NUMBERS=false, SHOW_INDENT_LINES=false, USE_SOFT_WRAPS=false, HIGHLIGHTING_LEVEL=INSPECTIONS, READ_ONLY_LOCK=true, BREADCRUMBS_PLACEMENT=HIDDEN, EXPAND_BY_DEFAULT=false)
//        "CommitDialog" -> {TextDiffSettingsHolder$PlaceSettings@71833} PlaceSettings(HIGHLIGHT_POLICY=BY_WORD, IGNORE_POLICY=DEFAULT, SHOW_WHITESPACES=false, SHOW_LINE_NUMBERS=true, SHOW_INDENT_LINES=false, USE_SOFT_WRAPS=false, HIGHLIGHTING_LEVEL=INSPECTIONS, READ_ONLY_LOCK=true, BREADCRUMBS_PLACEMENT=HIDDEN, EXPAND_BY_DEFAULT=false)
//        "Default" -> {TextDiffSettingsHolder$PlaceSettings@71672} PlaceSettings(HIGHLIGHT_POLICY=BY_WORD, IGNORE_POLICY=DEFAULT, SHOW_WHITESPACES=false, SHOW_LINE_NUMBERS=true, SHOW_INDENT_LINES=false, USE_SOFT_WRAPS=false, HIGHLIGHTING_LEVEL=INSPECTIONS, READ_ONLY_LOCK=true, BREADCRUMBS_PLACEMENT=HIDDEN, EXPAND_BY_DEFAULT=false)
//        "Merge" -> {TextDiffSettingsHolder$PlaceSettings@71836} PlaceSettings(HIGHLIGHT_POLICY=BY_WORD, IGNORE_POLICY=DEFAULT, SHOW_WHITESPACES=false, SHOW_LINE_NUMBERS=true, SHOW_INDENT_LINES=false, USE_SOFT_WRAPS=false, HIGHLIGHTING_LEVEL=INSPECTIONS, READ_ONLY_LOCK=true, BREADCRUMBS_PLACEMENT=HIDDEN, EXPAND_BY_DEFAULT=true)
//        "TestsFiledAssertions" -> {TextDiffSettingsHolder$PlaceSettings@71838} PlaceSettings(HIGHLIGHT_POLICY=BY_WORD, IGNORE_POLICY=DEFAULT, SHOW_WHITESPACES=false, SHOW_LINE_NUMBERS=true, SHOW_INDENT_LINES=false, USE_SOFT_WRAPS=false, HIGHLIGHTING_LEVEL=INSPECTIONS, READ_ONLY_LOCK=true, BREADCRUMBS_PLACEMENT=HIDDEN, EXPAND_BY_DEFAULT=true)
//        "VcsFileHistoryView" -> {TextDiffSettingsHolder$PlaceSettings@71840} PlaceSettings(HIGHLIGHT_POLICY=BY_WORD, IGNORE_POLICY=DEFAULT, SHOW_WHITESPACES=false, SHOW_LINE_NUMBERS=true, SHOW_INDENT_LINES=false, USE_SOFT_WRAPS=false, HIGHLIGHTING_LEVEL=INSPECTIONS, READ_ONLY_LOCK=true, BREADCRUMBS_PLACEMENT=HIDDEN, EXPAND_BY_DEFAULT=false)
//        "VcsLogView" -> {TextDiffSettingsHolder$PlaceSettings@71842} PlaceSettings(HIGHLIGHT_POLICY=BY_WORD, IGNORE_POLICY=DEFAULT, SHOW_WHITESPACES=false, SHOW_LINE_NUMBERS=true, SHOW_INDENT_LINES=false, USE_SOFT_WRAPS=false, HIGHLIGHTING_LEVEL=INSPECTIONS, READ_ONLY_LOCK=true, BREADCRUMBS_PLACEMENT=HIDDEN, EXPAND_BY_DEFAULT=false)
//        putUserData(DiffUserDataKeysEx.PLACE, "Default")
        // control by order instead
//        putUserData(DiffUserDataKeysEx.FORCE_DIFF_TOOL, UnifiedDiffTool.INSTANCE)
    }

    private val myProcessor = MyDiffRequestProcessor(project, userDataHolder)

    init {
        myPanel.add(myProcessor.component)
    }

    override fun setRequest(request: DiffRequest?) {
        this.setRequest(request, null as Any?)
    }

    override fun setRequest(request: DiffRequest?, identity: Any?) {
        myProcessor.setRequest(request)
    }

    override fun <T> putContextHints(key: Key<T>, value: T?) {
        myProcessor.putContextUserData(key, value)
    }

    override fun getComponent(): JComponent {
        return myPanel
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return myProcessor.preferredFocusedComponent
    }

    override fun dispose() {
        Disposer.dispose(this.myProcessor)
    }

    private class MyDiffRequestProcessor(project: Project?, holderBase: UserDataHolder
    ) : DiffRequestProcessor(project, holderBase) {

        private var myRequests = mutableListOf<DiffRequest>()
        private var currentIndex = 0

        init {
            settings.isGoToNextFileOnNextDifference = true
        }

        @Synchronized
        fun setRequest(request: DiffRequest?) {
            if (request != null) {
                myRequests.add(request)
            }
            if (myRequests.size == 1) {
                this.applyRequest(myRequests[0], true, null)
            }
        }

        override fun isNavigationEnabled(): Boolean {
            super.isNavigationEnabled()
            return true
        }

        override fun hasNextChange(fromUpdate: Boolean): Boolean {
            return currentIndex < myRequests.size - 1
        }

        override fun hasPrevChange(fromUpdate: Boolean): Boolean {
            return currentIndex > 0
        }

        override fun goToPrevChange(fromDifferences: Boolean) {
            super.goToPrevChange(fromDifferences)
            currentIndex--
            updateRequest()
        }

        override fun goToNextChange(fromDifferences: Boolean) {
            super.goToNextChange(fromDifferences)
            currentIndex++
            updateRequest()
        }

        @RequiresEdt
        @Synchronized
        override fun updateRequest(force: Boolean, scrollToChangePolicy: ScrollToPolicy?) {
            ApplicationManager.getApplication().assertIsDispatchThread()

            val currentRequest = myRequests.getOrNull(currentIndex) ?: return
            applyRequest(currentRequest, force, scrollToChangePolicy)
        }

        override fun getToolOrderFromSettings(availableTools: MutableList<out DiffTool>): MutableList<DiffTool> {
            availableTools.sortBy { if (it.name.contains("unified", true)) 0 else 1 }
            @Suppress("UNCHECKED_CAST")
            return availableTools as MutableList<DiffTool>
        }
    }
}

