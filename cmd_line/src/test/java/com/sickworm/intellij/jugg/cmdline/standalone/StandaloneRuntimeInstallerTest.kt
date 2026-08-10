package com.sickworm.intellij.jugg.cmdline.standalone

import com.google.gson.Gson
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneRuntimeInstallerTest {

    @Test
    fun `install commits the selected runtime manifest`() {
        val root = Files.createTempDirectory("jugg-standalone-install").toFile()
        val firstBundle = bundle(root.resolve("first"), "build-1", "first")
        val secondBundle = bundle(root.resolve("second"), "build-2", "second")
        val installer = StandaloneRuntimeInstaller(root.resolve("home"), root.resolve("bin"))

        installer.installValidated(firstBundle)
        installer.installValidated(secondBundle)

        assertEquals("build-2", installer.readActiveManifest()?.releaseBuildId)
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
    fun `install stops the running standalone daemon`() {
        val root = Files.createTempDirectory("jugg-standalone-stop").toFile()
        val juggRoot = root.resolve("home")
        val daemon = startFakeStandaloneDaemon(root.resolve("daemon"), juggRoot)
        try {
            StandaloneRuntimeInstaller(juggRoot, root.resolve("bin"))
                .installValidated(bundle(root.resolve("bundle"), "build-1", "runtime"))

            assertTrue(daemon.waitFor(5, TimeUnit.SECONDS))
        } finally {
            daemon.destroyForcibly()
        }
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

    private fun startFakeStandaloneDaemon(dir: File, juggRoot: File): Process {
        val source = dir.resolve("src/com/sickworm/intellij/jugg/bootstrap/StandaloneBootstrap.java")
        val classes = dir.resolve("classes").apply { mkdirs() }
        val readyFile = dir.resolve("ready")
        source.parentFile.mkdirs()
        source.writeText("""
            package com.sickworm.intellij.jugg.bootstrap;
            public final class StandaloneBootstrap {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready");
                    Thread.sleep(Long.MAX_VALUE);
                }
            }
        """.trimIndent())
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", classes.path, source.path))
        val java = File(System.getProperty("java.home"), "bin/java")
        val process = ProcessBuilder(java.path, "-Djugg.root.dir=${juggRoot.path}", "-cp", classes.path,
            "com.sickworm.intellij.jugg.bootstrap.StandaloneBootstrap", readyFile.path).start()
        repeat(100) {
            if (readyFile.isFile) return process
            Thread.sleep(20)
        }
        process.destroyForcibly()
        error("Fake standalone daemon did not start")
    }
}
