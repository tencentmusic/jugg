package com.sickworm.intellij.jugg.ide

enum class ConfirmResult {
    POSITIVE,
    NEGATIVE,
    CANCEL,
    INVALID,
    ;

    val isConfirmed get() = this == POSITIVE
    val isCanceled get() = this == CANCEL
}