package com.android.tools.deployer

import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.deployer.model.DexClass
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.DexClassNodeWrapper

/**
 * Final data that going to deploy to the device.
 */
data class JuggDeployData(
    /** application apks */
    val apks: List<ApkInfo>,
    /** brand new deploying classes that not exists in previous deployment */
    val newClasses: List<ClassDeployItem>,
    /** exists deploying classes, but not compatible with JVM-TI Apply Changes */
    val hotFixModifiedClasses: List<ClassDeployItem>,
    /** exists deploying classes, and compatible with JVM-TI Apply Changes */
    val hotReloadModifiedClasses: List<ClassDeployItem>,
    /** modified files that will place into /assets or /res. TODO handle other path? */
    val overlays: List<DeployItem>,
) {
    val isEmpty get() = newClasses.isEmpty() &&
            hotFixModifiedClasses.isEmpty() &&
            hotReloadModifiedClasses.isEmpty() &&
            overlays.isEmpty()

    val classes get() = newClasses + hotFixModifiedClasses + hotReloadModifiedClasses

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
        if (hotFixModifiedClasses.isNotEmpty()) {
            builder.append("\nhot fix modified classes:\n")
            builder.append(hotFixModifiedClasses.toLogString())
        }
        if (hotReloadModifiedClasses.isNotEmpty()) {
            builder.append("\nhot reload modified classes:\n")
            builder.append(hotReloadModifiedClasses.toLogString())
        }
        if (overlays.isNotEmpty()) {
            builder.append("\noverlay files:\n")
            builder.append(overlays.toLogString())
        }
        return builder.toString()
    }
    
}

open class DeployItem(
    val name: String,
    val type: CompileOutput.Type,
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

class ClassDeployItem(
    deployItem: DeployItem,
    val dexClassNode: DexClassNodeWrapper
): DeployItem(deployItem.name, deployItem.type, deployItem.checksum, deployItem.content)

fun Collection<DeployItem>.toLogString(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
}