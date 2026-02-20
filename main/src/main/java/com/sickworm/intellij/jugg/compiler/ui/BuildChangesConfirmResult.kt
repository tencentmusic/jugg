package com.sickworm.intellij.jugg.compiler.ui

/**
 * BuildChangesConfirmResult captures user choice when build-file changes are detected.
 */
enum class BuildChangesConfirmResult {
    FIND_CHANGE, IGNORE_CHANGE, CANCEL, FALLBACK
}
