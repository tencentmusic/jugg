@file:OptIn(UnstableMetadataApi::class)

package com.sickworm.intellij.jugg.compiler.source

import kotlinx.metadata.jvm.*
import java.io.File

/**
 * Read, write and merge .kotlin_module files in classPaths.
 */
class KmModuleMergerForCompilation(
    private val classPath: File,
) {

    private val metaInfDir get() = File(classPath, "META-INF")
    private val kmModuleFileMap = mutableMapOf<String, KmModuleMerger>()

    fun loadAndMerge() {
        metaInfDir.listFiles()
            ?.filter { it.extension == "kotlin_module" }
            ?.forEach {
                var merger = kmModuleFileMap[it.absolutePath]
                if (merger == null) {
                    merger = KmModuleMerger()
                    kmModuleFileMap[it.absolutePath] = merger
                }
                merger.merge(it)
            }
    }

    fun save(targetVersion: JvmMetadataVersion? = null) {
        kmModuleFileMap.forEach { (filePath, merger) ->
            val destFile = File(filePath)
            if (targetVersion == null) {
                merger.writeTo(destFile)
            } else {
                merger.writeTo(destFile, targetVersion)
            }
        }
    }

    fun getExtensionClasses(): List<String> {
        val result = mutableListOf<String>()
        kmModuleFileMap.values.forEach { merger ->
            result.addAll(merger.getExtensionClasses())
        }
        return result
    }
}

/**
 * Read, write and merge .kotlin_module files.
 */
class KmModuleMerger {

    private val kmModule = KmModule()
    private var version = JvmMetadataVersion.LATEST_STABLE_SUPPORTED

    /**
     * merge kotlin module metadata into [kmModule]
     */
    fun merge(kmModuleFile: File) {
        val bytes = kmModuleFile.readBytes()
        val metadata = try {
            KotlinModuleMetadata.read(bytes)
        } catch (e: Exception) {
            // kotlin_module isEmpty, ignore
            return
        }
        val kmModule = metadata.kmModule
        version = metadata.version
        this.kmModule.merge(kmModule)
    }

    /**
     * write kotlin module metadata to file
     */
    fun writeTo(destFile: File, targetVersion: JvmMetadataVersion) {
        val metadata = KotlinModuleMetadata(kmModule, targetVersion).write()
        destFile.also {
            it.parentFile?.mkdirs()
            it.writeBytes(metadata)
        }
    }

    fun writeTo(destFile: File) {
        // why not optional argument? KmModuleMergerTest will have compilation error:
        // Cannot access class 'kotlinx.metadata.jvm.JvmMetadataVersion'.
        // Check your module classpath for missing or conflicting dependencies
        writeTo(destFile, version)
    }

    fun getExtensionClasses(): List<String> {
        val result = mutableListOf<String>()
        kmModule.packageParts.forEach { (_, value) ->
            value.fileFacades.forEach { facade ->
                val className = "L$facade;"
                result.add(className)
            }
        }
        return result
    }

    override fun toString(): String {
        val builder = StringBuilder()
        builder.appendLine("optionalAnnotationClasses: ${kmModule.optionalAnnotationClasses}")
        builder.appendLine("packageParts:")
        kmModule.packageParts.forEach { (key, value) ->
            builder.appendLine("   key: $key")
            builder.appendLine("   fileFacades: ${value.fileFacades}")
            builder.appendLine("   multiFileClassParts:")
            value.multiFileClassParts.forEach { (t, u) ->
                builder.appendLine("        [$t: $u]")
            }
        }
        return builder.toString()
    }

    private fun KmModule.merge(ktModule: KmModule) {
        // annotations are not writable
        // optionalAnnotationClasses are not writable
        this.packageParts.addIfNotExist(ktModule.packageParts)

        ktModule.packageParts.entries.forEach { (key, value) ->
            val packagePart = this.packageParts[key]
            packagePart?.fileFacades?.addIfNotExist(value.fileFacades)
            packagePart?.multiFileClassParts?.addIfNotExist(value.multiFileClassParts)
        }
    }

    private fun <T> MutableList<T>.addIfNotExist(values: List<T>) {
        values.forEach {
            if (!this.contains(it)) {
                add(it)
            }
        }
    }

    private fun <K, V> MutableMap<K, V>.addIfNotExist(values: Map<K, V>) {
        values.forEach { (key, value) ->
            if (!this.containsKey(key)) {
                this[key] = value
            }
        }
    }
}