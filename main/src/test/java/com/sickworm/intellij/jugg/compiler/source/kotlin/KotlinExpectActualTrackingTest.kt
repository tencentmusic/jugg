package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/** L1 tests for project-compiler expect/actual tracking and complementary cache updates. */
class KotlinExpectActualTrackingTest {

    @Test
    fun `tracks expect actual relation and writes a bidirectional cache edge`() {
        withSources { common, actual, outputDir ->
            val compiler = projectCompiler()
            val output = ByteArrayOutputStream()
            val tracking = compiler.execWithExpectActualTracking(
                PrintStream(output),
                compilerArgs(common, actual, outputDir),
            )

            assertEquals(output.toString(), ExitCode.OK, tracking.exitCode)
            assertEquals(setOf(actual.canonicalFile), tracking.expectToActual[common.canonicalFile])

            val baselineCache = KotlinComplementaryFilesCache.findCacheRoot(module.buildPathInfo)
                ?: error("Kotlin complementary cache is missing")
            val copiedCache = File(outputDir.parentFile, "cache").also {
                assertTrue(baselineCache.copyRecursively(it))
            }
            compiler.updateComplementaryFiles(
                copiedCache,
                module.projectRootDir,
                outputDir,
                listOf(common, actual),
                tracking,
            )

            assertEquals(setOf(actual.canonicalFile), compiler.readComplementaryFiles(
                copiedCache,
                module.projectRootDir,
                outputDir,
                listOf(common),
            ).canonicalFiles())
            assertEquals(setOf(common.canonicalFile), compiler.readComplementaryFiles(
                copiedCache,
                module.projectRootDir,
                outputDir,
                listOf(actual),
            ).canonicalFiles())
        }
    }

    @Test
    fun `failed compilation does not return a successful tracking result`() {
        withSources { common, _, outputDir ->
            val compiler = projectCompiler()
            val tracking = compiler.execWithExpectActualTracking(
                PrintStream(ByteArrayOutputStream()),
                compilerArgs(common, null, outputDir),
            )

            assertNotEquals(ExitCode.OK, tracking.exitCode)
        }
    }

    @Test
    fun `cache write failure does not fail a successful compilation`() {
        withSources { common, actual, outputDir ->
            val compiler = projectCompiler()
            val tracking = compiler.execWithExpectActualTracking(
                PrintStream(ByteArrayOutputStream()),
                compilerArgs(common, actual, outputDir),
            )
            assertEquals(ExitCode.OK, tracking.exitCode)

            val fakeBuildDir = File(outputDir.parentFile, "corrupt-build")
            val buildPathInfo = ModuleBuildPathInfo(
                module.projectRootDir,
                module.moduleRootDir,
                "debug",
                buildDirRelativePath = fakeBuildDir.relativeTo(module.projectRootDir).path,
            )
            val cacheFile = File(
                fakeBuildDir,
                "kotlin/compileDebugKotlin/cacheable/caches-jvm/jvm/kotlin/complementary-files.tab",
            ).apply { parentFile.mkdirs(); writeText("corrupt") }

            KotlinComplementaryFilesCache(compiler).update(
                buildPathInfo,
                listOf(common, actual),
                tracking,
                logger,
            )

            assertTrue(cacheFile.exists())
        }
    }

    private fun withSources(block: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory(module.moduleRootDir.toPath(), "jugg_tracking_").toFile()
        try {
            val common = File(root, "Common.kt").apply {
                writeText("package tracking\nexpect fun trackedValue(): String")
            }
            val actual = File(root, "Platform.kt").apply {
                writeText("package tracking\nactual fun trackedValue(): String = \"tracked\"")
            }
            block(common, actual, File(root, "output").apply { mkdirs() })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun compilerArgs(common: File, actual: File?, outputDir: File): Array<String> {
        val stdlib = module.kotlinPlugins.orEmpty().first {
            it.name.startsWith("kotlin-stdlib-") && !it.name.contains("common")
        }
        val args = mutableListOf(
            "-d", outputDir.absolutePath,
            "-cp", stdlib.absolutePath,
            "-module-name", "jugg_tracking",
            "-Xmulti-platform",
            "-Xcommon-sources=${common.absolutePath}",
            common.absolutePath,
        )
        actual?.let { args.add(it.absolutePath) }
        return args.toTypedArray()
    }

    private fun projectCompiler(): K2JVMCompilerIsolate {
        return K2JVMCompilerIsolate().apply {
            initIfNeeded(
                module.kotlinPlugins.orEmpty() + module.kotlinExtensions.orEmpty() +
                    module.kspDependencies.orEmpty().map { it.file },
                logger,
            )
        }
    }

    private fun Collection<File>.canonicalFiles() = map(File::getCanonicalFile).toSet()

    companion object {
        private lateinit var module: ModuleInfo

        @JvmStatic
        @BeforeClass
        fun prepareFixture() {
            module = AssembleAndroidProjectOnce.getProjectInfo().modules.getValue("kmpCompose")
        }
    }
}
