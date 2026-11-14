package com.sickworm.intellij.jugg.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.source.kotlin.KmModuleMerger
import com.sickworm.intellij.jugg.compiler.source.kotlin.KmModuleMergerForCompilation
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.KmModule
import kotlin.metadata.jvm.KmPackageParts
import kotlin.metadata.jvm.KotlinModuleMetadata
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Regression coverage for module indexes used to resolve top-level Kotlin declarations. */
class KmModuleMergerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logger = mock(Logger::class.java)

    @Test
    fun `Kotlin 2_4 baseline survives warm up and consecutive incremental outputs`() {
        val classpath = temporaryFolder.newFolder()
        val version = JvmMetadataVersion(2, 4)
        val baseline = moduleFile(classpath, "app", moduleBytes(version, "TimeExtensionsKt"))
        val warmUpMerger = KmModuleMergerForCompilation(classpath, logger)
        warmUpMerger.loadAndMerge()

        val incremental = moduleFile(classpath, "app_googlePlayDebug", moduleBytes(version))
        warmUpMerger.loadAndMerge()
        warmUpMerger.save()
        assertModule(baseline, version, "TimeExtensionsKt")

        listOf("FirstExtensionsKt", "SecondExtensionsKt").forEachIndexed { index, facade ->
            val merger = KmModuleMergerForCompilation(classpath, logger)
            merger.loadAndMerge()
            incremental.writeBytes(moduleBytes(version, facade))
            merger.loadAndMerge()
            merger.save()

            assertModule(baseline, version, "TimeExtensionsKt")
            assertModule(incremental, version, *listOf("FirstExtensionsKt", "SecondExtensionsKt")
                .take(index + 1).toTypedArray())
        }
    }

    @Test
    fun `older modern metadata retains its version and declarations after merging`() {
        listOf(2, 3).forEach { minor ->
            val classpath = temporaryFolder.newFolder()
            val version = JvmMetadataVersion(2, minor)
            val file = moduleFile(classpath, "app", moduleBytes(version, "OldExtensionsKt"))
            val merger = KmModuleMergerForCompilation(classpath, logger)
            merger.loadAndMerge()
            file.writeBytes(moduleBytes(version, "NewExtensionsKt"))
            merger.loadAndMerge()
            merger.save()

            assertModule(file, version, "OldExtensionsKt", "NewExtensionsKt")
        }
    }

    @Test
    fun `legacy metadata retains declarations after a serialization round trip`() {
        val assets = File("../idea/src/test/assets/kotlin/kotlin_module")
        val merger = KmModuleMerger(logger)
        merger.merge(File(assets, "app_debug_new.kotlin_module"))
        merger.merge(File(assets, "app_debug_old.kotlin_module"))
        val output = temporaryFolder.newFile("merged.kotlin_module")
        merger.writeTo(output)
        val reloaded = KmModuleMerger(logger)
        reloaded.merge(output)

        assertEquals(setOf(
            "Lcom/sickworm/jugg/demo/testcase/ktextension/ExtClass1Kt;",
            "Lcom/sickworm/jugg/demo/testcase/ktextension/ExtClass3Kt;",
        ), reloaded.getExtensionClasses().toSet())
    }

    @Test
    fun `unreadable indexes survive compiler overwrite without blocking valid indexes`() {
        val unsupported = moduleBytes(JvmMetadataVersion(3, 0), "FutureExtensionsKt")
        listOf(byteArrayOf(1, 2, 3), unsupported).forEach { original ->
            val classpath = temporaryFolder.newFolder()
            val version = JvmMetadataVersion(2, 2)
            val unreadable = moduleFile(classpath, "unreadable", original)
            val valid = moduleFile(classpath, "valid", moduleBytes(version, "OldExtensionsKt"))
            val merger = KmModuleMergerForCompilation(classpath, logger)
            merger.loadAndMerge()
            unreadable.writeBytes(moduleBytes(version))
            valid.writeBytes(moduleBytes(version, "NewExtensionsKt"))
            merger.loadAndMerge()
            merger.save()

            assertContentEquals(original, unreadable.readBytes())
            assertModule(valid, version, "OldExtensionsKt", "NewExtensionsKt")
        }
    }

    @Test
    fun `failed read after a valid load restores the original index`() {
        val classpath = temporaryFolder.newFolder()
        val original = moduleBytes(JvmMetadataVersion(2, 2), "TimeExtensionsKt")
        val file = moduleFile(classpath, "app", original)
        val merger = KmModuleMergerForCompilation(classpath, logger)
        merger.loadAndMerge()
        file.writeBytes(byteArrayOf(1, 2, 3))
        merger.loadAndMerge()
        merger.save()

        assertContentEquals(original, file.readBytes())
    }

    @Test
    fun `legacy unreadable index is preserved after compiler overwrite`() {
        val classpath = temporaryFolder.newFolder()
        val original = byteArrayOf(1, 2, 3)
        val file = moduleFile(classpath, "app", original)
        val merger = KmModuleMergerForCompilation(classpath, logger)
        merger.loadAndMerge()
        File("../idea/src/test/assets/kotlin/kotlin_module/app_debug_new.kotlin_module").copyTo(file, overwrite = true)
        merger.loadAndMerge()
        merger.save()

        assertContentEquals(original, file.readBytes())
    }

    @Test
    fun `compiler produced indexes from Kotlin 1_3 through 2_4 retain declarations and versions`() {
        val versions = listOf("1.3.72", "1.4.32", "1.5.31", "1.6.21", "1.7.22", "1.8.21",
            "1.9.23", "2.0.21", "2.1.20", "2.2.21", "2.3.20", "2.4.21")
        versions.forEach { compilerVersion ->
            val fixture = File("src/test/resources/kotlin-module-compat/$compilerVersion")
            val classpath = temporaryFolder.newFolder()
            val file = moduleFile(classpath, "compat", File(fixture, "baseline.kotlin_module").readBytes())
            val version = KotlinModuleMetadata.read(file.readBytes()).version
            val merger = KmModuleMergerForCompilation(classpath, logger)
            merger.loadAndMerge()
            merger.save()
            assertEquals(version, KotlinModuleMetadata.read(file.readBytes()).version, compilerVersion)
            File(fixture, "incremental.kotlin_module").copyTo(file, overwrite = true)
            merger.loadAndMerge()
            merger.loadAndMerge()
            merger.save()

            val merged = KotlinModuleMetadata.read(file.readBytes())
            assertEquals(version, merged.version, compilerVersion)
            val parts = merged.kmModule.packageParts.getValue("example")
            assertEquals(setOf("example/BaselineKt", "example/AddedKt"), parts.fileFacades.toSet(), compilerVersion)
            assertEquals(2, parts.fileFacades.size, compilerVersion)
            assertEquals(mapOf("example/Shared__BaselinePartKt" to "example/Shared",
                "example/Shared__AddedPartKt" to "example/Shared"), parts.multiFileClassParts, compilerVersion)
            val reloaded = KmModuleMergerForCompilation(classpath, logger)
            reloaded.loadAndMerge()
            assertEquals(setOf("Lexample/BaselineKt;", "Lexample/AddedKt;"),
                reloaded.getExtensionClasses().toSet(), compilerVersion)
            // Keep build outputs for independent verification with each original compiler.
            file.copyTo(File("build/kotlin-module-compat/$compilerVersion/compat.kotlin_module"), overwrite = true)
        }
    }

    @Test
    fun `explicit metadata version override preserves the patch component`() {
        val classpath = temporaryFolder.newFolder()
        val file = moduleFile(classpath, "app", moduleBytes(JvmMetadataVersion(2, 4), "ExtensionsKt"))
        val merger = KmModuleMergerForCompilation(classpath, logger)
        merger.loadAndMerge()
        val target = JvmMetadataVersion(1, 1, 16)
        merger.save(target)

        assertModule(file, target, "ExtensionsKt")
    }

    private fun moduleFile(classpath: File, name: String, bytes: ByteArray): File {
        return File(classpath, "META-INF/$name.kotlin_module").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun moduleBytes(version: JvmMetadataVersion, vararg facades: String): ByteArray {
        val module = KmModule()
        if (facades.isNotEmpty()) {
            module.packageParts["example"] = KmPackageParts(
                facades.map { "example/$it" }.toMutableList(), mutableMapOf(),
            )
        }
        return KotlinModuleMetadata(module, version).write()
    }

    private fun assertModule(file: File, version: JvmMetadataVersion, vararg facades: String) {
        val metadata = KotlinModuleMetadata.read(file.readBytes())
        assertEquals(version, metadata.version)
        assertEquals(facades.map { "example/$it" }.toSet(),
            metadata.kmModule.packageParts.values.flatMap { it.fileFacades }.toSet())
    }
}
