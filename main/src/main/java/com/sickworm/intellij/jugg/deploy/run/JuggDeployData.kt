package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.deployer.model.DexClass
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.deploy.data.EffectedClassNode
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import com.sickworm.intellij.jugg.deploy.outerClassName

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
    /** effected class nodes that need to be recompiled. */
    val effectedClassNodes: List<EffectedClassNode>,
    /** modified files that will place into /assets or /res. */
    val overlays: List<DeployItem>,
    /** parsed class nodes and method & field references. */
    val parsedDex: ParsedDex,
    /** is first time push res. */
    val isFullRes: Boolean,
    /** is for warm up. */
    private val isWarmUp: Boolean,
    /** just install the apks only */
    val isInstall: Boolean = false,
    /** is need update AndroidManifest.xml in APK */
    val isNeedUpdateAndroidManifest: Boolean = false,
    /** is restart app after deployment */
    var isNeedRestartApp: Boolean = hotFixModifiedClasses.isNotEmpty(),
) {
    val isEmpty get() = newClasses.isEmpty() &&
            hotFixModifiedClasses.isEmpty() &&
            hotReloadModifiedClasses.isEmpty() &&
            overlays.isEmpty()

    // for now, we always restart activity excepts warm up and restart app
    val isNeedRestartActivity get() = !isWarmUp && !isNeedRestartApp && !isEmpty

    val deployType: DeployType = when {
        isInstall -> DeployType.INSTALL
        isWarmUp -> DeployType.WARM_UP
        isNeedRestartApp -> DeployType.HOT_FIX
        else -> DeployType.HOT_RELOAD
    }

    fun toFallbackToHotFixData(): JuggDeployData {
        return this.copy(
            hotFixModifiedClasses = this.hotFixModifiedClasses + this.hotReloadModifiedClasses,
            hotReloadModifiedClasses = emptyList(),
            isNeedRestartApp = true,
        )
    }

    private fun toString(isFull: Boolean): String {
        val builder = StringBuilder()
        builder.append("JuggDeployData ($deployType): ")
        if (isEmpty) {
            builder.append("[nothing to deploy]")
            return builder.toString()
        }
        builder.append("[\n")
        if (newClasses.isNotEmpty()) {
            val classString = newClasses.toClassLogString(isFull, excludeNodes = hotFixModifiedClasses + hotReloadModifiedClasses)
            if (classString.isNotEmpty()) {
                builder.append("new classes:\n")
                builder.append(classString)
                builder.append("\n")
            }
        }
        if (hotFixModifiedClasses.isNotEmpty()) {
            val classString = hotFixModifiedClasses.toClassLogString(isFull, includeNodes = hotReloadModifiedClasses)
            if (classString.isNotEmpty()) {
                builder.append("hot fix modified classes:\n")
                builder.append(classString)
                builder.append("\n")
            }
        }
        if (hotReloadModifiedClasses.isNotEmpty()) {
            val classString = hotReloadModifiedClasses.toClassLogString(isFull, excludeNodes = hotFixModifiedClasses)
            if (classString.isNotEmpty()) {
                builder.append("hot reload modified classes:\n")
                builder.append(classString)
                builder.append("\n")
            }
        }
        if (overlays.isNotEmpty()) {
            builder.append("overlay files:\n")
            if (isFullRes) {
                builder.append("    (total ${overlays.size} files)\n")
            } else {
                builder.append(overlays.toLogString(isFull))
                builder.append("\n")
            }
        }
        if (isFull) {
            val effectedSourceFileNames: List<String> = effectedClassNodes.map { it.sourceFileName }.distinct()
            if (effectedSourceFileNames.isNotEmpty()) {
                builder.append("effected source files:\n    ")
                builder.append(effectedSourceFileNames.toString())
                builder.append("\n")
            }
        }
        builder.append("]")
        return builder.toString()
    }

    fun toDescString(): String {
        return toString(false)
    }

    override fun toString(): String {
        return toString(true)
    }

    companion object {
        fun forInstall(apks: List<ApkInfo>) = JuggDeployData(apks,
            emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), ParsedDex.EMPTY,
            isFullRes = false,
            isWarmUp = false,
            isInstall = true,
        )

        fun forDryDeploy(apks: List<ApkInfo>) = JuggDeployData(apks,
            emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), ParsedDex.EMPTY,
            isFullRes = false,
            isWarmUp = false,
            isInstall = false,
        )
    }

    enum class DeployType {
        INSTALL,
        HOT_FIX,
        HOT_RELOAD,
        WARM_UP,
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
    val classNodes: List<ClassNode>
) {

    val isMultipleDex: Boolean get() = classNodes.size > 1

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

fun Collection<DeployItem>.toLogString(isFull: Boolean): String {
    return joinToString(separator = "\n    ", prefix = "    ") {
        if (isFull) {
            "${it.name}, checksum: ${it.checksum}"
        } else {
            it.name
        }
    }
}

fun Collection<ClassDeployItem>.toClassLogString(isFull: Boolean,
                                                 excludeNodes: List<ClassDeployItem> = emptyList(),
                                                 includeNodes: List<ClassDeployItem> = emptyList()): String {
    if (isFull) {
        return joinToString(separator = "\n    ", prefix = "    ") { "${it.name}, checksum: ${it.checksum}" }
    } else {
        val classMap = LinkedHashMap<String, MutableList<String>>()
        val excludeSet = excludeNodes.map { it.name.outerClassName }.toSet()
        forEach {
            val outerClassName = it.name.outerClassName
            if (excludeSet.contains(outerClassName)) return@forEach
            classMap.getOrPut(outerClassName) { mutableListOf() }.add(it.name)
        }
        includeNodes.forEach {
            val outerClassName = it.name.outerClassName
            classMap[outerClassName]?.add(it.name)
        }

        if (classMap.isEmpty()) return ""
        return classMap.entries.joinToString(separator = "\n    ", prefix = "    ") { (outerClassName, classNames) ->
            if (classNames.size <= 1) {
                outerClassName
            } else {
                "$outerClassName (with ${classNames.size - 1} inner classes)"
            }
        }
    }
}