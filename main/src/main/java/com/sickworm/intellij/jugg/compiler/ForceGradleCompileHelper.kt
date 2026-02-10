package com.sickworm.intellij.jugg.compiler

abstract class ForceGradleCompileHelper {

    companion object {
        var isForceGradleCompileNextTime = false
        var isCleanAndReinstallNextTime = false
    }

    abstract fun executeGradleCompile(autoConfirm: Boolean = false, useCleanAndReinstall: Boolean = false)
}
