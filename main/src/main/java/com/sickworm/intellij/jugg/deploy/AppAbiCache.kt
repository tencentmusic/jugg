package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.deploy.api.Deploy
import java.io.File

/**
 * Caches the resolved app ABI until the APK file set changes.
 */
class AppAbiCache {

    private val entries = mutableMapOf<Key, Deploy.Arch>()

    fun createKey(deviceSerial: String, packageName: String, apkPaths: List<String>): Key {
        val apkFiles = apkPaths.map { path ->
            val file = File(path).absoluteFile.normalize()
            ApkFileStamp(file.path, file.length(), file.lastModified())
        }.sortedBy { it.path }
        return Key(deviceSerial, packageName, apkFiles)
    }

    @Synchronized
    fun get(key: Key): Deploy.Arch? = entries[key]

    @Synchronized
    fun put(key: Key, arch: Deploy.Arch) {
        entries.keys.removeAll {
            it.deviceSerial == key.deviceSerial && it.packageName == key.packageName && it != key
        }
        entries[key] = arch
    }

    data class Key(
        val deviceSerial: String,
        val packageName: String,
        val apkFiles: List<ApkFileStamp>,
    )

    data class ApkFileStamp(
        val path: String,
        val size: Long,
        val modifiedAt: Long,
    )
}
