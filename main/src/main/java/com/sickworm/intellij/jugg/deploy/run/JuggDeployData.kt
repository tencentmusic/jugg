package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.deployer.model.DexClass
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.deploy.data.ParsedDex

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
    /** effected source files names(from source flag in .dex file) that need to be recompiled. */
    val effectedSourceFileNames: List<String>,
    /** modified files that will place into /assets or /res. */
    val overlays: List<DeployItem>,
    /** parsed class nodes and method & field references. */
    val parsedDex: ParsedDex,
    /** is first time push overlays. */
    val isFullOverlays: Boolean,
    /** is for warm up. */
    private val isWarmUp: Boolean,
) {
    val isEmpty get() = newClasses.isEmpty() &&
            hotFixModifiedClasses.isEmpty() &&
            hotReloadModifiedClasses.isEmpty() &&
            overlays.isEmpty()

    val isNeedRestartApp get() = hotFixModifiedClasses.isNotEmpty()

    val isNeedRestartActivity get() = !isWarmUp // for now, we always restart activity excepts warm up action

    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("JuggDeployData: ")
        if (isEmpty) {
            builder.append("[nothing to deploy]")
            return builder.toString()
        }
        builder.append("[\n")
        if (newClasses.isNotEmpty()) {
            builder.append("new classes:\n")
            builder.append(newClasses.toClassLogString())
            builder.append("\n")
        }
        if (hotFixModifiedClasses.isNotEmpty()) {
            builder.append("hot fix modified classes:\n")
            builder.append(hotFixModifiedClasses.toClassLogString())
            builder.append("\n")
        }
        if (hotReloadModifiedClasses.isNotEmpty()) {
            builder.append("hot reload modified classes:\n")
            builder.append(hotReloadModifiedClasses.toClassLogString())
            builder.append("\n")
        }
        if (overlays.isNotEmpty()) {
            builder.append("overlay files:\n")
            if (isFullOverlays) {
                builder.append("    (total ${overlays.size} files)\n")
            } else {
                builder.append(overlays.toLogString())
                builder.append("\n")
            }
        }
        builder.append("]")
        return builder.toString()
    }

    companion object {
        fun forInstall(apks: List<ApkInfo>) = JuggDeployData(apks,
            emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), ParsedDex.EMPTY,
            isFullOverlays = false,
            isWarmUp = false,
        )
    }
}

open class DeployItem(
    val name: String,
    val type: CompileOutput.Type,
    val checksum: Long, // crc
    val content: ByteArray,
) {

    fun toIncompleteOverlay(apk: Apk): Pair<ApkEntry, ByteString> {
        val apkEntry = ApkEntry(name, checksum, apk)
        val byteString = ByteString.copyFrom(content)
        return apkEntry to byteString
    }
}

class ClassDeployItem(
    val deployItem: DeployItem,
    val classNode: ClassNode
) {

    val name: String get() = deployItem.name
    val type: CompileOutput.Type get() = deployItem.type
    val checksum: Long get() = deployItem.checksum
    val content: ByteArray get() = deployItem.content

    fun toIncompleteDexClass(): DexClass {
        return DexClass(name, checksum, content, null)
    }

    override fun toString(): String {
        return name
    }
}

fun Collection<DeployItem>.toLogString(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
}

fun Collection<ClassDeployItem>.toClassLogString(): String {
    return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
}