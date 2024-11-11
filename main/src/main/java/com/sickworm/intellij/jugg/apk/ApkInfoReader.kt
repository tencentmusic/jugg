package com.sickworm.intellij.jugg.apk

import com.android.tools.deployer.model.Apk

class ApkInfoReader(
    private val apks: List<Apk>,
    private val logger: (String) -> Unit,
) {

    /**
     * see com.android.tools.deploy.proto.Deploy.Arch
     * @return ARCH_UNKNOWN, ARCH_32_BIT, ARCH_64_BIT
     */
    fun getArch(): String {
        var is32Bit = true
        apks.forEach {
            val has64Bit = it.apkEntries.any { (name, _) -> name.startsWith("lib/arm64-v8a") }
            val has32Bit = it.apkEntries.any { (name, _) -> name.startsWith("lib/armeabi-v7a") }
            if (is32Bit) {
                is32Bit = !has64Bit && has32Bit
            }
            logger.invoke("Apk getArch: path: ${it.path} has64Bit=$has64Bit, has32Bit=$has32Bit")
        }
        logger.invoke("Apk getArch: is32Bit=$is32Bit")
        return if (is32Bit) "ARCH_32_BIT" else "ARCH_64_BIT"
    }
}