package com.sickworm.intellij.jugg.compiler.source.kotlin

import kotlinx.metadata.jvm.JvmMetadataVersion
import java.io.File

/**
 * Fallback for read and write .kotlin_module files. Just keep old metadata.
 */
class KmModuleMergerCopy(private val classPath: File) : IKmModuleMergerForCompilation {

    private val metaInfDir get() = File(classPath, "META-INF")
    private val kmModuleFileMap = mutableMapOf<String, ByteArray>()

    override fun loadAndMerge() {
        metaInfDir.listFiles()
            ?.filter { it.extension == "kotlin_module" }
            ?.forEach {
                val merger = kmModuleFileMap[it.absolutePath]
                if (merger == null) {
                    kmModuleFileMap[it.absolutePath] = it.readBytes()
                }
            }
    }

    override fun save(targetVersion: JvmMetadataVersion?) {
        kmModuleFileMap.forEach { (filePath, bytes) ->
            File(filePath).writeBytes(bytes)
        }
    }
}