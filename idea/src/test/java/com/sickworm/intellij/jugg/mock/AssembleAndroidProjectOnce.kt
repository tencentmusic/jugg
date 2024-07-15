package com.sickworm.intellij.jugg.mock

import java.io.File

object AssembleAndroidProjectOnce {

    private var hasAssemble = File("${System.getProperty("user.home")}/.jugg_test_do_not_assemble").exists()

    fun ensure() {
        if (!hasAssemble) {
            GradleBuildHelper.clean()
            GradleBuildHelper.appAssembleDebug()
        }
        hasAssemble = true
    }
}