package com.sickworm.intellij.jugg.deploy

import java.io.File

interface IDeviceAdb {

    val displayName: String?

    val api: Int

    fun execAdbShellCmd(cmd: String): String

    fun push(from: File, to: String): Boolean

    fun getDefaultLaunchActivity(apkFile: File): String
}