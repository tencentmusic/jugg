package com.sickworm.intellij.jugg.mock

import com.intellij.execution.Executor
import com.intellij.icons.AllIcons
import javax.swing.Icon

class MockExecutor: Executor() {

    override fun getToolWindowId(): String {
        return "MockExecutor_id"
    }

    override fun getToolWindowIcon(): Icon {
        return AllIcons.Toolwindows.ToolWindowRun
    }

    override fun getIcon(): Icon {
        return AllIcons.Toolwindows.ToolWindowRun
    }

    override fun getDisabledIcon(): Icon {
        return AllIcons.Toolwindows.ToolWindowRun
    }

    override fun getDescription(): String {
        return "MockExecutor_desc"
    }

    override fun getActionName(): String {
        return "MockExecutor_Action"
    }

    override fun getId(): String {
        return "MockExecutor_id"
    }

    override fun getStartActionText(): String {
        @Suppress("DialogTitleCapitalization")
        return "MockExecutor getStartActionText"
    }

    override fun getContextActionId(): String {
        return "MockExecutor getContextActionId"
    }

    override fun getHelpId(): String {
        return "MockExecutor_help_id"
    }

}
