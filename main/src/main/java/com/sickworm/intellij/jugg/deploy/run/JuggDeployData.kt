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
import kotlin.math.min

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
    /** is need update files in APK and resign, e.g. AndroidManifest.xml lib/arm64-v8a/xxx.so */
    val updateApkFiles: List<DeployItem> = emptyList(),
    /** just push overlay(include classes), don't set up JVMTI agent */
    val isPushOverlayOnly: Boolean = false,
    /** is using compat deploy */
    val isCompatDeploy: Boolean = false,
    /** Device can not update overlays without restart App. Occurs in Android 15(api 35) */
    val isHasRelaunchActivityIssues: Boolean = false,
) {

    val isEmpty get() = newClasses.isEmpty() &&
            hotFixModifiedClasses.isEmpty() &&
            hotReloadModifiedClasses.isEmpty() &&
            overlays.isEmpty()

    /** is restart app after deployment */
    val isNeedRestartApp: Boolean = hotFixModifiedClasses.isNotEmpty()
            || (isPushOverlayOnly && !isEmpty)
            || (isNeedRestartActivity && isHasRelaunchActivityIssues)

    // for now, we always restart activity excepts warm up and restart app
    val isNeedRestartActivity get() = !isWarmUp && !isNeedRestartApp && !isEmpty

    /** is need update files in APK and resign, e.g. AndroidManifest.xml lib/arm64-v8a/xxx.so */
    val isNeedUpdateApk: Boolean = updateApkFiles.isNotEmpty()

    val deployType: DeployType get() = when {
        isInstall -> DeployType.INSTALL
        isWarmUp -> DeployType.WARM_UP
        isCompatDeploy -> DeployType.COMPAT_HOT_FIX
        isNeedRestartApp -> DeployType.HOT_FIX
        else -> DeployType.HOT_RELOAD
    }

    fun toFallbackToHotFixData(): JuggDeployData {
        return this.copy(
            hotFixModifiedClasses = this.hotFixModifiedClasses + this.hotReloadModifiedClasses,
            hotReloadModifiedClasses = emptyList(),
            isPushOverlayOnly = true,
        )
    }

    private fun toString(isFull: Boolean): String {
        val builder = StringBuilder()
        builder.append("JuggDeployData ($deployType): ")
        if (isFull) {
            builder.append("isFullRes: $isFullRes, isWarmUp: $isWarmUp, isInstall: $isInstall, isPushOverlayOnly: $isPushOverlayOnly, isNeedRestartApp: $isNeedRestartApp, isCompatDeploy: $isCompatDeploy, isNeedRestartActivity:$isNeedRestartActivity\n")
            if (updateApkFiles.isNotEmpty()) {
                builder.append("update apks: ${updateApkFiles.map { it.name }}")
            }
        }
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
            if (isFullRes && !isCompatDeploy) {
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

    fun splitData(firstMaxOverlaySize: Int, maxOverlaySize: Int): List<JuggDeployData> {
        if (overlays.size <= maxOverlaySize) {
            // no need to split
            return listOf(this)
        }

        val splitDataList = mutableListOf<JuggDeployData>()
        var start = 0
        var end = 0
        fun remainOverlaySize() = overlays.size - end
        fun currentSplitOverlaySize() = if (start == 0) firstMaxOverlaySize else maxOverlaySize
        while (remainOverlaySize() > 0) {
            end = (start + currentSplitOverlaySize()).coerceAtMost(overlays.size)
            val splitData = forDryDeploy(apks).copy(overlays = overlays.subList(start, end))
            splitDataList.add(splitData)
            start = end
        }

        // drop remain fields to the last one, to keep the original behavior
        splitDataList[splitDataList.size - 1] = this.copy(overlays = splitDataList[splitDataList.size - 1].overlays)

        return splitDataList
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
            isPushOverlayOnly = true,
            isFullRes = false,
            isWarmUp = false,
            isInstall = false,
        )
    }

    enum class DeployType {
        INSTALL,
        HOT_FIX,
        COMPAT_HOT_FIX,
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

    override fun toString(): String {
        return "$type:$name"
    }
}

class ClassDeployItem(
    val deployItem: DeployItem,
    val classNodes: List<ClassNode>
) {

    val isMultipleDex: Boolean get() = classNodes.size > 1
    // see CompileFileExtKt.dependencyNameToDexFileName. Maybe more elegant?
    val isLibraryDex: Boolean get() = deployItem.name.startsWith("#")

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