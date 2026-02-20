package com.sickworm.intellij.jugg.compiler

/**
 * Triggers one-shot Gradle fallback compile and optional clean-reinstall flow.
 */
abstract class ForceGradleCompileHelper {

    companion object {
        var isForceGradleCompileNextTime = false
        var isCleanAndReinstallNextTime = false
    }

    abstract fun executeGradleCompile(autoConfirm: Boolean = false, useCleanAndReinstall: Boolean = false)
}
