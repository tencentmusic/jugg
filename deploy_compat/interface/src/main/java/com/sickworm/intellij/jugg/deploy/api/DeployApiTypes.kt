package com.sickworm.intellij.jugg.deploy.api

import java.io.Serializable
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

/** Minimal device contract shared by IDE and standalone deployment flows. */
interface IDevice {
    val serialNumber: String
    val isOnline: Boolean
    val name: String
        get() = serialNumber
    val version: AndroidVersion
    val abis: List<String>
        get() = emptyList()
    val clientCount: Int
        get() = 0

    fun getProperty(name: String): String?
}

/** Exposes one host runtime device without assigning it to a deployer compatibility version. */
interface IRuntimeDevice : IDevice {
    val runtimeDevice: Any
}

/** Minimal Android version value used by shared deployment decisions. */
data class AndroidVersion(
    val apiLevel: Int,
    val codename: String? = null,
) {
    fun isGreaterOrEqualThan(api: Int): Boolean = apiLevel >= api

    object VersionCodes {
        const val N = 24
        const val O = 26
    }
}

/** APK metadata consumed by Jugg deployment orchestration. */
class Apk(
    val name: String,
    val checksum: String,
    val path: String,
    val packageName: String,
    libraryAbis: List<String>,
    targetPackages: List<String>,
    sdkLibraries: List<String>,
    apkEntries: Map<String, ApkEntry>,
    /** Keeps the host runtime APK attached while the owned metadata remains in process. */
    @Transient val runtimeObject: Any? = null,
) : Serializable {
    val libraryAbis: List<String> = Collections.unmodifiableList(ArrayList(libraryAbis))
    val targetPackages: List<String> = Collections.unmodifiableList(ArrayList(targetPackages))
    val sdkLibraries: List<String> = Collections.unmodifiableList(ArrayList(sdkLibraries))
    val apkEntries: Map<String, ApkEntry> = Collections.unmodifiableMap(LinkedHashMap(
        apkEntries.mapValues { (_, entry) -> ApkEntry(entry.name, entry.checksum, this) },
    ))
}

/** One entry in an APK model. */
class ApkEntry(
    val name: String,
    val checksum: Long,
    val apk: Apk,
) : Serializable {
    val qualifiedPath: String
        get() = "${apk.name}/$name"
}

/** Field state required to reinitialize modified class variables after a dex swap. */
data class FieldReInitState(
    val name: String,
    val type: String,
    val staticVar: Boolean,
    val state: VariableState,
    val value: String,
) {
    enum class VariableState {
        UNKNOWN,
        CONSTANT,
    }
}

/** One class extracted from a dex entry. */
class DexClass @JvmOverloads constructor(
    val name: String,
    val checksum: Long,
    val code: ByteArray,
    val dex: ApkEntry?,
    variableStates: List<FieldReInitState> = emptyList(),
) {
    val variableStates: List<FieldReInitState> = Collections.unmodifiableList(ArrayList(variableStates))
}

/** Immutable byte content used by deployment overlays. */
class ByteString private constructor(
    private val bytes: ByteArray,
) : Iterable<Byte>, Serializable {
    fun toByteArray(): ByteArray = bytes.copyOf()

    fun toStringUtf8(): String = bytes.toString(Charsets.UTF_8)

    override fun iterator(): Iterator<Byte> = bytes.iterator()

    companion object {
        @JvmStatic
        fun copyFrom(bytes: ByteArray): ByteString = ByteString(bytes.copyOf())
    }
}

/** Changed dex classes grouped by creation and modification. */
object DexComparator {
    data class ChangedClasses(
        val newClasses: List<DexClass>,
        val modifiedClasses: List<DexClass>,
    )
}

/** Deployment protocol values shared without exposing Android Studio protobuf classes. */
object Deploy {
    enum class Arch {
        ARCH_UNKNOWN,
        ARCH_32_BIT,
        ARCH_64_BIT,
    }
}

/** Logger contract required by Apply Changes executors. */
interface ILogger {
    fun error(t: Throwable?, msgFormat: String?, vararg args: Any?)
    fun warning(msgFormat: String?, vararg args: Any?)
    fun info(msgFormat: String?, vararg args: Any?)
    fun verbose(msgFormat: String?, vararg args: Any?)
}
