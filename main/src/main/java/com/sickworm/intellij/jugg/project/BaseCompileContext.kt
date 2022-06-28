package com.sickworm.intellij.jugg.project

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.compiler.OnContextUpdate
import java.io.File

data class BaseCompileContext(
    override val logger: Logger,
    override var tempCompileDir: File,
    override val androidHome: File,
    override var modules: Map<String, ModuleInfo> = emptyMap(),
    override var apkInfos: List<ApkInfo> = emptyList(),
    override val minApi: Int,
): ICompileContext {

    override val androidJar: File get() = getSuggestedPlatform(modules)
    override val androidBuildTools: File get() = getSuggestedBuildTools(modules)
    override val variant: String = "debug" // TODO more elegant?

    private val listeners = mutableListOf<OnContextUpdate>()

    override fun listenUpdate(listener: OnContextUpdate) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun update(apkInfos: List<ApkInfo>? = null, modules: Map<String, ModuleInfo>? = null) {
        apkInfos?.let {
            this.apkInfos = it
        }
        modules?.let {
            this.modules = HashMap(it)
        }
        dispatch()
    }

    private fun dispatch() {
        synchronized(listeners) {
            listeners.forEach {
                it.invoke()
            }
        }
    }

    private fun getSuggestedPlatform(modules: Map<String, ModuleInfo>): File {
        val versionsInGradle = modules.values.map { it.compileVersion }
        var version = getLatestVersion(versionsInGradle)
        val rootDir = File(androidHome, "platforms")

        if (version != null) {
            val targetDir = File(rootDir, "android-$version")
            if (!targetDir.exists()) {
                logger.warn("Can't not read compile sdk version from gradle, path($targetDir) not exist. " +
                        "Try to find in android home")
                version = null
            }
        }

        if (version == null) {
            val versionsInSdk = rootDir.listFiles()?.mapNotNull {
                if (!it.name.startsWith("android-")) return@mapNotNull null
                it.name.substring("android-".length)
            }
            version = getLatestVersion(versionsInSdk)

            if (version == null) {
                throw JuggException.androidJarNotFound(
                    "versions in gradle: $versionsInGradle, versions in sdk: $versionsInSdk")
            }
        }

        val path = File(androidHome, "platforms/android-$version/android.jar")
        if (!path.exists()) throw JuggException.androidJarNotFound("file not exists $path")
        return path
    }

    private fun getSuggestedBuildTools(modules: Map<String, ModuleInfo>): File {
        val versionsInGradle = modules.values.map { it.buildToolsVersion }
        var version = getLatestVersion(versionsInGradle)

        if (version != null) {
            val targetDir = File(androidHome, "build-tools/$version")
            if (!targetDir.exists()) {
                logger.warn("Can't not read build-tools version from gradle, path($targetDir) not exist" +
                        "Try to find in android home.")
                version = null
            }
        }

        if (version == null) {
            val rootDir = File(androidHome, "build-tools")
            val versionsInSdk = rootDir.listFiles()?.mapNotNull {
                it.name
            }
            version = getLatestVersion(versionsInSdk)

            if (version == null) {
                throw JuggException.buildToolsNotFound(
                    "versions in gradle: $versionsInGradle, versions in sdk: $versionsInSdk")
            }
        }
        val targetDir = File(androidHome, "build-tools/$version")
        if (!targetDir.exists()) throw JuggException.buildToolsNotFound("file not exists $targetDir")
        return targetDir
    }

    private fun getLatestVersion(versions: List<String?>?): String? {
        versions?: return null
        var suggestedVersion: String? = versions.firstOrNull()
        versions.forEach {
            if (it == null) return@forEach
            if (!it.matches("[.0-9]+".toRegex())) return@forEach
            if (it.isLargerThan(suggestedVersion)) {
                suggestedVersion = it
            }
        }
        return suggestedVersion
    }

    private fun String.isLargerThan(version: String?): Boolean {
        if (version == null) return true
        val myVersions = this.split(".")
        val otherVersions = version.split(".")
        val size = kotlin.math.min(myVersions.size, otherVersions.size)
        for (i in 0 until size) {
            val compareResult = myVersions[i].compareTo(otherVersions[i])
            if (compareResult == 1) return true
            if (compareResult == -1) return false
        }
        return myVersions.size < otherVersions.size
    }
}