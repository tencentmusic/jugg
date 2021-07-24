package com.android.tools.deployer

import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.deployer.model.DexClass
import com.android.tools.idea.protobuf.ByteString

data class AidpDeployData(
    val classes: List<AidpDeployItem>,
    val overlays: List<AidpDeployItem>,
) {
    val isEmpty get() = classes.isEmpty() && overlays.isEmpty()

    override fun toString(): String {
        val classString = classes.toLogString()
        val overlayString = overlays.toLogString()
        return "AidpDeployData:\n$classString\n$overlayString"
    }
}

class AidpDeployItem(
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

fun Collection<AidpDeployItem>.toLogString(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
}

fun Collection<DexClass>.toLogStringDex(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
}

fun Collection<ApkEntry>.toLogStringEntry(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
}