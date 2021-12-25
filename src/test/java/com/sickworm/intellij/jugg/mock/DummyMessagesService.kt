package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.messages.MessagesService
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.TextRange
import com.intellij.util.Function
import com.intellij.util.PairFunction
import java.awt.Component
import javax.swing.Icon
import javax.swing.JCheckBox
import javax.swing.JTextField

class DummyMessagesService: MessagesService {
    override fun showChooseDialog(
        project: Project?,
        parentComponent: Component?,
        message: String?,
        title: String?,
        values: Array<String?>?,
        initialValue: String?,
        icon: Icon?
    ): Int {
        TODO("Not yet implemented")
    }

    override fun showEditableChooseDialog(
        message: String?,
        title: String?,
        icon: Icon?,
        values: Array<String?>?,
        initialValue: String?,
        validator: InputValidator?
    ): String? {
        TODO("Not yet implemented")
    }

    override fun showInputDialog(
        project: Project?,
        parentComponent: Component?,
        message: String?,
        title: String?,
        icon: Icon?,
        initialValue: String?,
        validator: InputValidator?,
        selection: TextRange?,
        comment: String?
    ): String? {
        TODO("Not yet implemented")
    }

    override fun showInputDialogWithCheckBox(
        message: String?,
        title: String?,
        checkboxText: String?,
        checked: Boolean,
        checkboxEnabled: Boolean,
        icon: Icon?,
        initialValue: String?,
        validator: InputValidator?
    ): Pair<String?, Boolean?> {
        TODO("Not yet implemented")
    }

    override fun showMessageDialog(
        project: Project?,
        parentComponent: Component?,
        message: String?,
        title: String?,
        options: Array<String>,
        defaultOptionIndex: Int,
        focusedOptionIndex: Int,
        icon: Icon?,
        doNotAskOption: DialogWrapper.DoNotAskOption?,
        alwaysUseIdeaUI: Boolean
    ): Int {
        return 0
    }

    override fun showMoreInfoMessageDialog(
        project: Project?,
        message: String?,
        title: String?,
        moreInfo: String?,
        options: Array<String?>?,
        defaultOptionIndex: Int,
        focusedOptionIndex: Int,
        icon: Icon?
    ): Int {
        TODO("Not yet implemented")
    }

    override fun showMultilineInputDialog(
        project: Project?,
        message: String?,
        title: String?,
        initialValue: String?,
        icon: Icon?,
        validator: InputValidator?
    ): String? {
        TODO("Not yet implemented")
    }

    override fun showPasswordDialog(
        project: Project?,
        message: String?,
        title: String?,
        icon: Icon?,
        validator: InputValidator?
    ): String? {
        TODO("Not yet implemented")
    }

    override fun showPasswordDialog(
        parentComponent: Component,
        message: String?,
        title: String?,
        icon: Icon?,
        validator: InputValidator?
    ): CharArray? {
        TODO("Not yet implemented")
    }

    override fun showTextAreaDialog(
        textField: JTextField?,
        title: String?,
        dimensionServiceKey: String?,
        parser: Function<in String?, out MutableList<String?>?>?,
        lineJoiner: Function<in MutableList<String?>?, String?>?
    ) {
        TODO("Not yet implemented")
    }

    override fun showTwoStepConfirmationDialog(
        message: String?,
        title: String?,
        options: Array<String?>?,
        checkboxText: String?,
        checked: Boolean,
        defaultOptionIndex: Int,
        focusedOptionIndex: Int,
        icon: Icon?,
        exitFunc: PairFunction<in Int?, in JCheckBox?, Int?>?
    ): Int {
        TODO("Not yet implemented")
    }
}