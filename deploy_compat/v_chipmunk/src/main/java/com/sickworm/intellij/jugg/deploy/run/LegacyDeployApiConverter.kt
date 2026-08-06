package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice as StudioDevice
import com.android.tools.deploy.proto.Deploy as StudioDeploy
import com.android.tools.deployer.DexComparator as StudioDexComparator
import com.android.tools.deployer.model.Apk as StudioApk
import com.android.tools.deployer.model.ApkEntry as StudioApkEntry
import com.android.tools.deployer.model.DexClass as StudioDexClass
import com.android.tools.idea.protobuf.ByteString as StudioByteString
import com.android.utils.ILogger as StudioLogger
import com.google.common.collect.ImmutableList
import com.sickworm.intellij.jugg.deploy.api.AndroidVersion
import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ApkEntry
import com.sickworm.intellij.jugg.deploy.api.ByteString
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.sickworm.intellij.jugg.deploy.api.DexClass
import com.sickworm.intellij.jugg.deploy.api.DexComparator
import com.sickworm.intellij.jugg.deploy.api.FieldReInitState
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.api.IRuntimeDevice
import com.sickworm.intellij.jugg.deploy.api.ILogger
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Bridges one Android Studio runtime device to the owned device contract. */
class LegacyDeviceAdapter(
    val studioDevice: StudioDevice,
) : IRuntimeDevice {
    override val runtimeDevice: Any
        get() = studioDevice
    override val serialNumber: String
        get() = studioDevice.serialNumber
    override val isOnline: Boolean
        get() = studioDevice.isOnline
    override val name: String
        get() = studioDevice.name
    override val version: AndroidVersion
        get() = AndroidVersion(studioDevice.version.apiLevel, studioDevice.version.codename)
    override val abis: List<String>
        get() = studioDevice.abis
    override val clientCount: Int
        get() = studioDevice.clients.size

    override fun getProperty(name: String): String? = studioDevice.getProperty(name)

    override fun equals(other: Any?): Boolean = other is LegacyDeviceAdapter && studioDevice == other.studioDevice

    override fun hashCode(): Int = studioDevice.hashCode()
}

/** Converts deployment values at the legacy Android Studio API boundary. */
class LegacyDeployApiConverter {
    private val devices = WeakHashMap<StudioDevice, WeakReference<LegacyDeviceAdapter>>()

    /** Returns one stable adapter while either the raw device or adapter remains in use. */
    fun toJuggDevice(device: StudioDevice): IDevice = synchronized(devices) {
        devices[device]?.get() ?: LegacyDeviceAdapter(device).also { devices[device] = WeakReference(it) }
    }

    fun toStudioDevice(device: IDevice): StudioDevice {
        return requireNotNull((device as? IRuntimeDevice)?.runtimeDevice as? StudioDevice) {
            "Device does not belong to the current Android Studio runtime"
        }
    }

    fun toStudioLogger(logger: ILogger): StudioLogger {
        if (logger is StudioLogger) return logger
        return object : StudioLogger {
            override fun error(t: Throwable?, msgFormat: String?, vararg args: Any?) = logger.error(t, msgFormat, *args)
            override fun warning(msgFormat: String?, vararg args: Any?) = logger.warning(msgFormat, *args)
            override fun info(msgFormat: String?, vararg args: Any?) = logger.info(msgFormat, *args)
            override fun verbose(msgFormat: String?, vararg args: Any?) = logger.verbose(msgFormat, *args)
        }
    }

    /** Copies APK metadata while keeping its runtime object attached to the owned value. */
    fun toJuggApk(apk: StudioApk): Apk {
        val placeholder = apk.toJuggApk(emptyMap())
        val entries = apk.apkEntries.mapValues { (_, entry) -> ApkEntry(entry.name, entry.checksum, placeholder) }
        return apk.toJuggApk(entries)
    }

    fun toStudioApk(apk: Apk): StudioApk {
        return requireNotNull(apk.runtimeObject as? StudioApk) { "APK does not belong to the current Android Studio runtime" }
    }

    fun toStudioApkEntry(entry: ApkEntry): StudioApkEntry = StudioApkEntry(entry.name, entry.checksum, toStudioApk(entry.apk))

    fun toStudioByteString(content: ByteString): StudioByteString = StudioByteString.copyFrom(content.toByteArray())

    fun toStudioChangedClasses(changes: DexComparator.ChangedClasses): StudioDexComparator.ChangedClasses {
        return StudioDexComparator.ChangedClasses(
            changes.newClasses.map(::toStudioDexClass),
            changes.modifiedClasses.map(::toStudioDexClass),
        )
    }

    fun toStudioArch(arch: Deploy.Arch): StudioDeploy.Arch = StudioDeploy.Arch.valueOf(arch.name)

    private fun toStudioDexClass(dexClass: DexClass): StudioDexClass {
        return StudioDexClass(
            dexClass.name,
            dexClass.checksum,
            dexClass.code,
            dexClass.dex?.let(::toStudioApkEntry),
            ImmutableList.copyOf(dexClass.variableStates.map(::toStudioFieldReInitState)),
        )
    }

    private fun toStudioFieldReInitState(state: FieldReInitState): StudioDeploy.ClassDef.FieldReInitState {
        return StudioDeploy.ClassDef.FieldReInitState.newBuilder()
            .setName(state.name)
            .setType(state.type)
            .setStaticVar(state.staticVar)
            .setState(StudioDeploy.ClassDef.FieldReInitState.VariableState.valueOf(state.state.name))
            .setValue(state.value)
            .build()
    }

    private fun StudioApk.toJuggApk(entries: Map<String, ApkEntry>): Apk {
        return Apk(name, checksum, path, packageName, libraryAbis, targetPackages, emptyList(), entries, this)
    }
}
