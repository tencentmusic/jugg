package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import kotlinx.metadata.jvm.JvmMetadataVersion
import java.io.File

/**
 * IKmModuleMergerForCompilation loads, merges, and saves `.kotlin_module` metadata for incremental Kotlin builds.
 */
interface IKmModuleMergerForCompilation {

    fun loadAndMerge()

    fun save(targetVersion: JvmMetadataVersion? = null)

    companion object {

        fun create(
            kotlinVersion: String?,
            kotlinClassPath: File,
            logger: Logger,
        ) : IKmModuleMergerForCompilation {
            if (kotlinVersion == null) {
                logger.debug("kotlin version is null, disable .kotlin_module merge.")
                logger.warn("handle .kotlin_module failed, add new extension function may not be resolved")
                return KmModuleMergerCopy(kotlinClassPath)
            }

            val versions = getKotlinVersion(kotlinVersion)
            if (versions == null) {
                logger.debug("kotlin version is not valid, disable .kotlin_module merge. kotlinVersion: $kotlinVersion")
                logger.warn("handle .kotlin_module failed, add new extension function may not be resolved")
                return KmModuleMergerCopy(kotlinClassPath)
            }

            logger.debug("kotlin version: $versions")
            val major = versions[0]
            val minor = versions[1]
            return if ((major > 2) || (major == 2 && minor >= 2)) {
                KmModuleMergerForCompilation22(kotlinClassPath)
            } else {
                KmModuleMergerForCompilation(kotlinClassPath)
            }
        }

        private fun getKotlinVersion(kotlinVersion: String): List<Int>? {
            try {
                val pattern = """([\d.]+)""".toRegex()
                val version = pattern.find(kotlinVersion)?.value
                if (version.isNullOrEmpty()) {
                    return null
                }
                val versions = version.split('.').filter { it.isNotEmpty() }
                if (versions.size < 3) {
                    return null
                }
                return versions.map { it.toInt() }
            } catch (e: Exception) {
                return null
            }
        }
    }
}
