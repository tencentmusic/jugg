package com.android.tools.deployer

import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.deployer.model.DexClass
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.run.ApkInfo

data class JuggDeployData(
    val apks: List<ApkInfo>,
    val classes: List<JuggDeployItem>,
    val overlays: List<JuggDeployItem>,
) {
    val isEmpty get() = classes.isEmpty() && overlays.isEmpty()

    override fun toString(): String {
        val classString = classes.toLogString()
        val overlayString = overlays.toLogString()
        return "JuggDeployData:\n$classString\n$overlayString"
    }
}

class JuggDeployItem(
    val name: String,
    val checksum: Long, // crc
    val content: ByteArray,
) {

    fun toIncompleteDexClass(): DexClass {
        return DexClass(name, checksum, content, null)
    }

    fun toIncompleteOverlay(apk: Apk): Pair<ApkEntry, ByteString> {
        val apkEntry = ApkEntry(name, checksum, apk)
        val byteString = ByteString.copyFrom(content)
        return apkEntry to byteString
    }
}

fun Collection<JuggDeployItem>.toLogString(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
}

fun Collection<DexClass>.toLogStringDex(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
}

fun Collection<ApkEntry>.toLogStringEntry(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
}