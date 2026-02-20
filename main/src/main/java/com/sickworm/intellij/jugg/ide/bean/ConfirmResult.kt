package com.sickworm.intellij.jugg.ide.bean

/**
 * ConfirmResult result enum for user confirmation dialogs.
 * Collaboration: Returned by dialog flows and consumed by control branches through [isConfirmed] and [isCanceled].
 * Data Contract: [POSITIVE] means accepted, [CANCEL] means canceled, and other values represent non-confirmed outcomes.
 */
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
