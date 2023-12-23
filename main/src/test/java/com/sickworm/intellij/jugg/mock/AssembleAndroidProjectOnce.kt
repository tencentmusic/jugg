package com.sickworm.intellij.jugg.mock

object AssembleAndroidProjectOnce {

    private var hasAssemble = false

    fun ensure() {
        if (!hasAssemble) {
            GradleBuildHelper.clean()
            GradleBuildHelper.appAssembleDebug()
        }
        hasAssemble = true
    }
}