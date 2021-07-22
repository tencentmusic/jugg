package com.sickworm.intellij.aidp.deploy

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class DeployActionGroup: ActionGroup() {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        return arrayOf(DeployAction())
    }
}