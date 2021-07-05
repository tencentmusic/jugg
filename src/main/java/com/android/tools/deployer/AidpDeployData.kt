package com.android.tools.deployer

import com.android.tools.deployer.model.ApkEntry
import com.android.tools.deployer.model.DexClass

data class AidpDeployData(
    val classes: List<DexClass>,
) {
    val isEmpty get() = classes.isEmpty()

    override fun toString(): String {
        val classString = classes.toLogStringDex()
        return "AidpDeployData: \n$classString"
    }
}

fun Collection<DexClass>.toLogStringDex(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
}

fun Collection<ApkEntry>.toLogStringEntry(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
}