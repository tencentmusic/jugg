package com.sickworm.intellij.jugg.ide.bean

enum class ConfirmResult {
    POSITIVE,
    NEGATIVE,
    CANCEL,
    INVALID,
    LEFT,
    LINK_ACTION,
    ;

    val isConfirmed get() = this == POSITIVE
    val isCanceled get() = this == CANCEL
}