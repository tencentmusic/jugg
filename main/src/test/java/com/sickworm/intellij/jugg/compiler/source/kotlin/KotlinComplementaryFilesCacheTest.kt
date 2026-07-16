package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** L1 tests for reading Kotlin's real complementary-files incremental cache. */
class KotlinComplementaryFilesCacheTest {

    @Test
    fun `queries expect and actual complementary files in both directions`() {
        val module = kmpModule()
        val common = File(module.moduleRootDir, "src/commonMain/kotlin/com/sickworm/jugg/demo/kmp/PlatformLabel.kt")
        val actual = File(module.moduleRootDir, "src/androidMain/kotlin/com/sickworm/jugg/demo/kmp/PlatformLabel.android.kt")
        val cache = KotlinComplementaryFilesCache(projectCompiler(module))

        assertTrue(cache.read(module.buildPathInfo, listOf(common), logger).canonicalFiles().contains(actual.canonicalFile))
        assertTrue(cache.read(module.buildPathInfo, listOf(actual), logger).canonicalFiles().contains(common.canonicalFile))
    }

    @Test
    fun `returns empty when complementary cache is missing`() {
        val root = Files.createTempDirectory("jugg_missing_complementary_cache_").toFile()
        try {
            val buildPathInfo = ModuleBuildPathInfo(root, File(root, "module"), "debug", buildDirRelativePath = "build")

            assertNull(KotlinComplementaryFilesCache.findCacheRoot(buildPathInfo))
            assertEquals(emptyList<File>(), KotlinComplementaryFilesCache(K2JVMCompilerIsolate()).read(buildPathInfo, emptyList(), logger))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects ambiguous complementary cache candidates`() {
        val root = Files.createTempDirectory("jugg_ambiguous_complementary_cache_").toFile()
        try {
            val buildPathInfo = ModuleBuildPathInfo(root, File(root, "module"), "debug", buildDirRelativePath = "build")
            listOf("compileDebugKotlin", "compileDebugKotlinAndroid").forEach { taskName ->
                File(buildPathInfo.buildDir, "kotlin/$taskName/cacheable/caches-jvm/jvm/kotlin/complementary-files.tab")
                    .apply { parentFile.mkdirs(); writeBytes(byteArrayOf()) }
            }

            assertNull(KotlinComplementaryFilesCache.findCacheRoot(buildPathInfo))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `returns empty when complementary cache is corrupt`() {
        val root = Files.createTempDirectory("jugg_corrupt_complementary_cache_").toFile()
        try {
            val moduleRoot = File(root, "module").apply { mkdirs() }
            val source = File(moduleRoot, "Source.kt").apply { writeText("class Source") }
            val buildPathInfo = ModuleBuildPathInfo(root, moduleRoot, "debug", buildDirRelativePath = "build")
            File(buildPathInfo.buildDir, "kotlin/compileDebugKotlin/cacheable/caches-jvm/jvm/kotlin/complementary-files.tab")
                .apply { parentFile.mkdirs(); writeText("not a Kotlin cache") }

            val result = KotlinComplementaryFilesCache(projectCompiler(kmpModule()))
                .read(buildPathInfo, listOf(source), logger)

            assertEquals(emptyList<File>(), result)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun projectCompiler(module: ModuleInfo): K2JVMCompilerIsolate {
        return K2JVMCompilerIsolate().apply {
            initIfNeeded(
                module.kotlinPlugins.orEmpty() + module.kotlinExtensions.orEmpty() +
                    module.kspDependencies.orEmpty().map { it.file },
                logger,
            )
        }
    }

    private fun List<File>.canonicalFiles() = map(File::getCanonicalFile).toSet()

    companion object {
        private lateinit var module: ModuleInfo

        @JvmStatic
        @BeforeClass
        fun prepareFixture() {
            module = AssembleAndroidProjectOnce.getProjectInfo().modules.getValue("kmpCompose")
        }

        private fun kmpModule() = module
    }
}
