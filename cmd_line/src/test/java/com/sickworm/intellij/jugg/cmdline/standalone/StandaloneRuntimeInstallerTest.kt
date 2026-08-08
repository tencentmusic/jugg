package com.sickworm.intellij.jugg.cmdline.standalone

import com.google.gson.Gson
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneRuntimeInstallerTest {

    @Test
    fun `install commits manifest last and preserves previous runtime`() {
        val root = Files.createTempDirectory("jugg-standalone-install").toFile()
        val firstBundle = bundle(root.resolve("first"), "build-1", "first")
        val secondBundle = bundle(root.resolve("second"), "build-2", "second")
        val installer = StandaloneRuntimeInstaller(root.resolve("home"), root.resolve("bin"))

        installer.installValidated(firstBundle)
        installer.installValidated(secondBundle)

        assertEquals("build-2", installer.readActiveManifest()?.releaseBuildId)
        assertEquals("build-1", installer.readPreviousManifest()?.releaseBuildId)
        assertEquals("second", installer.storageDir.resolve(secondBundle.manifest.jarFileNames.single()).readText())
        assertTrue(root.resolve("bin/jugg-standalone").canExecute())
        assertTrue(root.resolve("bin/jugg-standalone.cmd").isFile)
    }

    @Test
    fun `invalid bundle does not replace active manifest`() {
        val root = Files.createTempDirectory("jugg-standalone-invalid").toFile()
        val installer = StandaloneRuntimeInstaller(root.resolve("home"), root.resolve("bin"))
        val active = bundle(root.resolve("active"), "build-1", "active")
        installer.installValidated(active)
        val invalid = bundle(root.resolve("invalid"), "build-2", "invalid").also {
            it.manifest.jarSha256[it.manifest.jarFileNames.single()] = "0".repeat(64)
            it.manifestFile.writeText(Gson().toJson(it.manifest))
        }

        assertFailsWith<IllegalStateException> { installer.installValidated(invalid) }

        assertEquals("build-1", installer.readActiveManifest()?.releaseBuildId)
        assertFalse(installer.storageDir.resolve(invalid.manifest.jarFileNames.single()).exists())
    }

    @Test
    fun `rollback switches to previous manifest without loading runtime jars`() {
        val root = Files.createTempDirectory("jugg-standalone-rollback").toFile()
        val installer = StandaloneRuntimeInstaller(root.resolve("home"), root.resolve("bin"))
        installer.installValidated(bundle(root.resolve("first"), "build-1", "first"))
        installer.installValidated(bundle(root.resolve("second"), "build-2", "second"))

        installer.rollback()

        assertEquals("build-1", installer.readActiveManifest()?.releaseBuildId)
    }

    @Test
    fun `activation failure rolls back once and ready records last known good`() {
        val root = Files.createTempDirectory("jugg-standalone-activation").toFile()
        val installer = StandaloneRuntimeInstaller(root.resolve("home"), root.resolve("bin"))
        installer.installValidated(bundle(root.resolve("first"), "build-1", "first"))
        installer.installValidated(bundle(root.resolve("second"), "build-2", "second"))
        val activation = StandaloneActivationManager(root.resolve("home"), installer)

        assertTrue(activation.onStartFailed("build-2"))
        assertEquals("build-1", installer.readActiveManifest()?.releaseBuildId)
        assertFalse(activation.onStartFailed("build-2"))
        activation.onReady("build-1")

        assertEquals("build-1", activation.readState()?.lastKnownGoodBuildId)
        assertEquals("build-2", activation.readState()?.failedBuildId)
    }

    @Test
    fun `automatic install does not change channel or downgrade build identity`() {
        val root = Files.createTempDirectory("jugg-standalone-takeover").toFile()
        val installer = StandaloneRuntimeInstaller(root.resolve("home"), root.resolve("bin"))
        val current = bundle(root.resolve("current"), "build-2", "current")
        installer.installValidated(current)
        val older = bundle(root.resolve("older"), "build-1", "older")
        val beta = bundle(root.resolve("beta"), "build-3", "beta").let {
            StandaloneBundle(it.rootDir, it.manifestFile, it.manifest.copy(releaseChannel = "beta"))
        }

        assertFailsWith<IllegalStateException> { installer.installValidated(older) }
        assertFailsWith<IllegalStateException> { installer.installValidated(beta) }
        installer.installValidated(older, allowDowngrade = true)

        assertEquals("build-1", installer.readActiveManifest()?.releaseBuildId)
    }

    private fun bundle(dir: File, buildId: String, content: String): StandaloneBundle {
        val jarsDir = dir.resolve("jars").apply { mkdirs() }
        dir.resolve("cli").apply { mkdirs(); resolve("jugg.py").writeText("print('jugg')") }
        val bootstrapDir = dir.resolve("bootstrap").apply { mkdirs() }
        val bootstrap = "bootstrap".toByteArray()
        val bootstrapHash = MessageDigest.getInstance("SHA-256").digest(bootstrap).joinToString("") { "%02x".format(it) }
        bootstrapDir.resolve("standalone-bootstrap.jar").writeBytes(bootstrap)
        val bytes = content.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val jarName = "runtime-$hash.jar"
        jarsDir.resolve(jarName).writeBytes(bytes)
        val manifest = StandaloneRuntimeManifest(
            schemaVersion = 1,
            runtimeApiVersion = 1,
            bootstrapApiVersion = 1,
            targetVersion = "4.0",
            releaseBuildId = buildId,
            releaseChannel = "stable",
            toolingReleaseBuildId = buildId,
            managedBy = "external",
            jarFileNames = listOf(jarName),
            jarSha256 = mutableMapOf(jarName to hash),
            bootstrapFileNames = listOf("standalone-bootstrap.jar"),
            bootstrapSha256 = mapOf("standalone-bootstrap.jar" to bootstrapHash),
        )
        val manifestFile = dir.resolve("standalone_bundle_manifest.json").apply { writeText(Gson().toJson(manifest)) }
        return StandaloneBundle(dir, manifestFile, manifest)
    }
}
