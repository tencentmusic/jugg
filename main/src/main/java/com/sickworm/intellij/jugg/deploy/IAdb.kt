package com.sickworm.intellij.jugg.deploy

import java.io.File

interface IAdb {

    fun execAdbShellCmd(cmd: String): String

    fun getDefaultLaunchActivity(apkFile: File): String
}