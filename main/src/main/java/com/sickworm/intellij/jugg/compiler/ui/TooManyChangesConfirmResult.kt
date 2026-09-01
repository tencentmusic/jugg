package com.sickworm.intellij.jugg.compiler.ui

/**
 * TooManyChangesConfirmResult captures the one-shot choice when incremental compile
 * would fall back because the current source set is too large.
 */
enum class TooManyChangesConfirmResult {
    FALLBACK,
    CONTINUE,
    CANCEL,
}
