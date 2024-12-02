package com.sickworm.intellij.jugg.deploy

import java.io.File

interface IDeviceAdb {

    val displayName: String?

    val api: Int

    fun execAdbShellCmd(cmd: String): String

    fun push(from: File, to: String): Boolean

    fun getDefaultLaunchActivity(apkFile: File): String

    /**
     * @return ARCH_UNKNOWN / ARCH_32_BIT / ARCH_64_BIT
     * @see [com.android.tools.deploy.proto.Deploy.Arch]
     */
    fun getArch(packageName: String): String

    /**
     * equals: adb shell "getprop | grep $name"
     */
    fun getProperty(name: String): String?
}