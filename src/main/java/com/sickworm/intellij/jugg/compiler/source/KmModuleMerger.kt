package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.project.JuggInternalException
import kotlinx.metadata.jvm.KmModule
import kotlinx.metadata.jvm.KotlinClassHeader
import kotlinx.metadata.jvm.KotlinModuleMetadata
import java.io.File

class KmModuleMerger {

    private val kmModule = KmModule()

    /**
     * merge kotlin module metadata into [kmModule]
     */
    fun merge(kmModuleFile: File) {
        try {
            if (!kmModuleFile.exists()) {
                throw JuggInternalException.kotlinModuleFailed("file not exists: $kmModuleFile")
            }
            val bytes = kmModuleFile.readBytes()
            val metadata = KotlinModuleMetadata.read(bytes)!!
            val kmModule = metadata.toKmModule()
            this.kmModule.merge(kmModule)
        } catch (e: Exception) {
            throw JuggInternalException.kotlinModuleFailed("exception: $e")
        }
    }

    /**
     * write kotlin module metadata to file
     */
    fun writeTo(destFile: File) {
        val version = KotlinClassHeader.COMPATIBLE_METADATA_VERSION
        val writer = KotlinModuleMetadata.Writer()
        // annotations are not writable
        // optionalAnnotationClasses are not writable
        kmModule.packageParts.entries.forEach {
            writer.visitPackageParts(it.key, it.value.fileFacades, it.value.multiFileClassParts)
        }

        val metadata = writer.write(version)

        destFile.also {
            it.parentFile?.mkdirs()
            it.writeBytes(metadata.bytes)
        }
    }

    override fun toString(): String {
        val builder = StringBuilder()
        builder.appendLine("annotations: ${kmModule.annotations}")
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

    private fun KmModule.clone(): KmModule {
        val module = KmModule()
        // annotations are not writable
        // optionalAnnotationClasses are not writable
        this.packageParts.entries.forEach {
            module.visitPackageParts(it.key, it.value.fileFacades, it.value.multiFileClassParts)
        }
        return module
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