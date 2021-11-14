package com.android.tools.deployer

import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.deployer.model.DexClass
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileOutput

data class JuggDeployData(
    val apks: List<ApkInfo>,
    val newClasses: List<DeployItem>,
    val modifiedClasses: List<DeployItem>,
    val overlays: List<DeployItem>,
) {
    val isEmpty get() = newClasses.isEmpty() && modifiedClasses.isEmpty() && overlays.isEmpty()

    override fun toString(): String {
        val builder = StringBuilder()
        if (isEmpty) {
            builder.append("[nothing to deploy]")
            return builder.toString()
        }
        if (newClasses.isNotEmpty()) {
            builder.append("\nnew classes:\n")
            builder.append(newClasses.toLogString())
        }
        if (modifiedClasses.isNotEmpty()) {
            builder.append("\nmodified classes:\n")
            builder.append(modifiedClasses.toLogString())
        }
        if (overlays.isNotEmpty()) {
            builder.append("\noverlay files:\n")
            builder.append(overlays.toLogString())
        }
        return builder.toString()
    }
    
}

class DeployItem(
    val path: String,
    val type: CompileOutput.Type,
    val checksum: Long, // crc
    val content: ByteArray,
) {

    fun toIncompleteDexClass(): DexClass {
        return DexClass(path, checksum, content, null)
    }

    fun toIncompleteOverlay(apk: Apk): Pair<ApkEntry, ByteString> {
        val apkEntry = ApkEntry(path, checksum, apk)
        val byteString = ByteString.copyFrom(content)
        return apkEntry to byteString
    }
}

fun Collection<DeployItem>.toLogString(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.path}, checksum: ${it.checksum}" }
}