package com.sickworm.intellij.jugg.project

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.compiler.OnContextUpdate
import java.io.File

data class BaseCompileContext(
    override val logger: Logger,
    override var tempCompileDir: File,
    override val androidHome: File,
    override var classPathDir: File,
    override var modules: Map<String, ModuleInfo> = emptyMap(),
    override var apks: List<ApkInfo> = emptyList(),
): ICompileContext {

    override val androidJar: File get() = getSuggestedPlatform(modules)
    override val androidBuildTools: File get() = getSuggestedBuildTools(modules)

    private val listeners = mutableListOf<OnContextUpdate>()

    override fun listenUpdate(listener: OnContextUpdate) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun update(apks: List<ApkInfo>? = null, modules: Map<String, ModuleInfo>? = null) {
        apks?.let {
            this.apks = ArrayList(it)
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
        val versions = modules.values.map { it.compileVersion }
        val version = getSuggestedVersion(versions)?: throw JuggException.androidJarNotFound("versions $versions")
        val path = File(androidHome, "platforms/android-$version/android.jar")
        if (!path.exists()) throw JuggException.androidJarNotFound("path $path")
        return path
    }

    private fun getSuggestedBuildTools(modules: Map<String, ModuleInfo>): File {
        val versions = modules.values.map { it.buildToolsVersion }
        val version = getSuggestedVersion(versions)?: throw JuggException.buildToolsNotFound("versions $versions")
        val path = File(androidHome, "build-tools/$version")
        if (!path.exists()) throw JuggException.androidJarNotFound("path $path")
        return path
    }

    private fun getSuggestedVersion(versions: List<String?>): String? {
        var suggestedVersion: String? = versions.firstOrNull()
        versions.forEach {
            if (it == null) return@forEach
            if (it.isLargerThan(suggestedVersion)) {
                suggestedVersion = it
            }
        }
        return suggestedVersion
    }

    private fun String.isLargerThan(version: String?): Boolean {
        if (version == null) return false
        val myVersions = this.split(".")
        val otherVersions = version.split(".")
        val size = kotlin.math.min(myVersions.size, otherVersions.size)
        for (i in 0 until size) {
            val compareResult = myVersions[i].compareTo(otherVersions[i])
            if (compareResult == 1) return false
            if (compareResult == -1) return true
        }
        return myVersions.size < otherVersions.size
    }
}