package com.sickworm.intellij.jugg.ide.logic

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StandaloneEmbeddedBundleTest {

    @Test
    fun `delta bundle restores jars shared with the plugin`() {
        val root = Files.createTempDirectory("jugg-embedded-bundle").toFile()
        val pluginLibDir = root.resolve("lib").apply { mkdirs() }
        val sharedRuntime = "shared-runtime".toByteArray()
        val sharedBootstrap = "shared-bootstrap".toByteArray()
        val standaloneRuntime = "standalone-runtime".toByteArray()
        pluginLibDir.resolve("plugin-runtime.jar").writeBytes(sharedRuntime)
        pluginLibDir.resolve("gson.jar").writeBytes(sharedBootstrap)
        val bundle = createBundle(root.resolve("full.zip"), sharedRuntime, sharedBootstrap, standaloneRuntime)
        val delta = root.resolve("delta.zip")

        StandaloneEmbeddedBundle.createDeltaBundle(bundle, pluginLibDir, delta)

        ZipFile(delta).use { zip ->
            assertNull(zip.getEntry("jars/${contentAddressedName("shared", sharedRuntime)}"))
            assertNull(zip.getEntry("bootstrap/shared-bootstrap.jar"))
            assertNotNull(zip.getEntry("jars/${contentAddressedName("standalone", standaloneRuntime)}"))
            assertNotNull(zip.getEntry("standalone_bundle_manifest.json"))
        }

        val stageDir = root.resolve("stage")
        extract(delta, stageDir)
        StandaloneEmbeddedBundle.restoreSharedJars(stageDir, pluginLibDir)

        assertContentEquals(sharedRuntime,
            stageDir.resolve("jars/${contentAddressedName("shared", sharedRuntime)}").readBytes())
        assertContentEquals(sharedBootstrap, stageDir.resolve("bootstrap/shared-bootstrap.jar").readBytes())
        assertContentEquals(standaloneRuntime,
            stageDir.resolve("jars/${contentAddressedName("standalone", standaloneRuntime)}").readBytes())
    }

    @Test
    fun `restore fails when a declared jar is unavailable`() {
        val root = Files.createTempDirectory("jugg-embedded-bundle-missing").toFile()
        val sharedRuntime = "shared-runtime".toByteArray()
        val sharedBootstrap = "shared-bootstrap".toByteArray()
        val standaloneRuntime = "standalone-runtime".toByteArray()
        val bundle = createBundle(root.resolve("full.zip"), sharedRuntime, sharedBootstrap, standaloneRuntime)
        val emptyLibDir = root.resolve("lib").apply { mkdirs() }
        val delta = root.resolve("delta.zip")
        StandaloneEmbeddedBundle.createDeltaBundle(bundle, root.resolve("source-lib").apply {
            mkdirs()
            resolve("runtime.jar").writeBytes(sharedRuntime)
            resolve("bootstrap.jar").writeBytes(sharedBootstrap)
        }, delta)
        val stageDir = root.resolve("stage")
        extract(delta, stageDir)

        assertFailsWith<IllegalStateException> {
            StandaloneEmbeddedBundle.restoreSharedJars(stageDir, emptyLibDir)
        }
    }

    private fun createBundle(
        target: File,
        sharedRuntime: ByteArray,
        sharedBootstrap: ByteArray,
        standaloneRuntime: ByteArray,
    ): File {
        val sharedName = contentAddressedName("shared", sharedRuntime)
        val standaloneName = contentAddressedName("standalone", standaloneRuntime)
        val manifest = mapOf(
            "schemaVersion" to 1,
            "runtimeApiVersion" to 1,
            "bootstrapApiVersion" to 1,
            "targetVersion" to "4.0",
            "releaseBuildId" to "build-1",
            "releaseChannel" to "stable",
            "toolingReleaseBuildId" to "build-1",
            "managedBy" to "external",
            "jarFileNames" to listOf(sharedName, standaloneName),
            "jarSha256" to mapOf(sharedName to sharedRuntime.sha256(), standaloneName to standaloneRuntime.sha256()),
            "bootstrapFileNames" to listOf("shared-bootstrap.jar"),
            "bootstrapSha256" to mapOf("shared-bootstrap.jar" to sharedBootstrap.sha256()),
        )
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.writeEntry("standalone_bundle_manifest.json", Gson().toJson(manifest).toByteArray())
            zip.writeEntry("jars/$sharedName", sharedRuntime)
            zip.writeEntry("jars/$standaloneName", standaloneRuntime)
            zip.writeEntry("bootstrap/shared-bootstrap.jar", sharedBootstrap)
            zip.writeEntry("install.sh", "#!/bin/sh\n".toByteArray())
        }
        return target
    }

    private fun extract(bundle: File, targetDir: File) {
        ZipInputStream(bundle.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = targetDir.resolve(entry.name)
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile.mkdirs()
                    target.outputStream().use(zip::copyTo)
                }
            }
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    private fun contentAddressedName(prefix: String, content: ByteArray) = "$prefix-${content.sha256()}.jar"

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }
}
