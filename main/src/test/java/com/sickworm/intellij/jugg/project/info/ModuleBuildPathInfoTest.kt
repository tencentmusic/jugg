package com.sickworm.intellij.jugg.project.info

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleBuildPathInfoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `build directory relative path should be required`() {
        val parameter = ModuleBuildPathInfo::class.constructors.single().parameters.single {
            it.name == "buildDirRelativePath"
        }

        assertFalse(parameter.isOptional)
    }

    @Test
    fun `custom build directory should drive all derived paths`() {
        val projectRootDir = File("/tmp/jugg-project")
        val moduleRootDir = File(projectRootDir, "app")
        val info = ModuleBuildPathInfo(
            projectRootDir,
            moduleRootDir,
            "release",
            buildDirRelativePath = "build/app",
        )

        assertEquals(File(projectRootDir, "build/app"), info.buildDir)
        assertEquals(
            File(projectRootDir, "build/app/outputs/mapping/release/usage.txt"),
            info.usageFile,
        )
        assertTrue(
            info.allBuildPathRelative.any {
                it.path == File("build/app/intermediates/data_binding_artifact/release").path
            },
            "custom build output should be synced from the project root",
        )
    }

    @Test
    fun `usageFile should resolve to mapping usage output and be synced`() {
        val projectRootDir = File("/tmp/jugg-project")
        val moduleRootDir = File(projectRootDir, "app")
        val info = ModuleBuildPathInfo(projectRootDir, moduleRootDir, "release", buildDirRelativePath = "")

        assertEquals(
            File(moduleRootDir, "build/outputs/mapping/release/usage.txt").path,
            info.usageFile.path
        )
        assertTrue(
            info.allBuildPathRelative.any { it.path == File("app/build/outputs/mapping/release/usage.txt").path },
            "usage.txt should be included in synced build outputs"
        )
    }

    @Test
    fun `allBuildPathRelative includes data binding artifact output`() {
        val projectRootDir = File("/tmp/jugg-project")
        val moduleRootDir = File(projectRootDir, "app")
        val info = ModuleBuildPathInfo(projectRootDir, moduleRootDir, "release", buildDirRelativePath = "")

        assertTrue(
            info.allBuildPathRelative.any {
                it.path == File("app/build/intermediates/data_binding_artifact/release").path
            },
            "data_binding_artifact should be included in synced build outputs"
        )
    }

    /**
     * AGP 9.0+ renamed compile_and_runtime_not_namespaced_r_class_jar to compile_and_runtime_r_class_jar.
     * Jugg should resolve R.jar correctly for the new namespaced path.
     */
    @Test
    fun `rFilePath resolves AGP 9 compile_and_runtime_r_class_jar path`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")

        // AGP 9.0+: compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar
        val agp9RJar = File(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar"
        )
        agp9RJar.parentFile.mkdirs()
        agp9RJar.createNewFile()

        assertEquals(agp9RJar.canonicalPath, info.rFilePath.canonicalPath,
            "rFilePath should resolve to AGP 9+ compile_and_runtime_r_class_jar path")
    }

    @Test
    fun `rFilePath resolves AGP 8 compile_and_runtime_not_namespaced_r_class_jar path`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")

        // AGP 8.x: compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar
        val agp8RJar = File(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar"
        )
        agp8RJar.parentFile.mkdirs()
        agp8RJar.createNewFile()

        assertEquals(agp8RJar.canonicalPath, info.rFilePath.canonicalPath,
            "rFilePath should resolve to AGP 8 compile_and_runtime_not_namespaced_r_class_jar path")
    }

    @Test
    fun `kotlinClassPath resolves AGP 9 built-in Kotlin output`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val builtInKotlinDir = File(
            moduleRootDir,
            "build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        ).apply { mkdirs() }

        assertEquals(builtInKotlinDir.canonicalPath, info.kotlinClassPath.canonicalPath)
    }

    @Test
    fun `kotlinClassPath resolves legacy Kotlin output`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val legacyKotlinDir = File(moduleRootDir, "build/tmp/kotlin-classes/debug").apply { mkdirs() }

        assertEquals(legacyKotlinDir.canonicalPath, info.kotlinClassPath.canonicalPath)
    }

    @Test
    fun `kotlinClassPath selects built-in output when it is newer`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val legacyKotlinDir = File(moduleRootDir, "build/tmp/kotlin-classes/debug").apply {
            mkdirs()
            setLastModified(1_000L)
        }
        val builtInKotlinDir = File(
            moduleRootDir,
            "build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        ).apply {
            mkdirs()
            setLastModified(2_000L)
        }

        assertTrue(legacyKotlinDir.exists())
        assertEquals(builtInKotlinDir.canonicalPath, info.kotlinClassPath.canonicalPath)
        assertEquals(
            listOf(builtInKotlinDir.canonicalPath),
            info.allClassPath.filter {
                it.path.contains("built_in_kotlinc") || it.path.contains("tmp/kotlin-classes")
            }.map { it.canonicalPath },
        )
    }

    @Test
    fun `kotlinClassPath selects legacy output when it is newer`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val legacyKotlinDir = File(moduleRootDir, "build/tmp/kotlin-classes/debug").apply {
            mkdirs()
            setLastModified(2_000L)
        }
        File(
            moduleRootDir,
            "build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        ).apply {
            mkdirs()
            setLastModified(1_000L)
        }

        assertEquals(legacyKotlinDir.canonicalPath, info.kotlinClassPath.canonicalPath)
    }

    @Test
    fun `kotlinClassPath selects built-in output when timestamps are equal`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        File(moduleRootDir, "build/tmp/kotlin-classes/debug").apply {
            mkdirs()
            setLastModified(1_000L)
        }
        val builtInKotlinDir = File(
            moduleRootDir,
            "build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        ).apply {
            mkdirs()
            setLastModified(1_000L)
        }

        assertEquals(builtInKotlinDir.canonicalPath, info.kotlinClassPath.canonicalPath)
    }

    @Test
    fun `kotlinClassPath falls back to legacy output`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")

        assertEquals(
            File(moduleRootDir, "build/tmp/kotlin-classes/debug").canonicalPath,
            info.kotlinClassPath.canonicalPath,
        )
    }

    @Test
    fun `allBuildPathRelative includes AGP 9 built-in Kotlin output`() {
        val projectRootDir = File("/tmp/jugg-project")
        val moduleRootDir = File(projectRootDir, "app")
        val info = ModuleBuildPathInfo(projectRootDir, moduleRootDir, "release", buildDirRelativePath = "")

        assertTrue(
            info.allBuildPathRelative.any {
                it.path == File(
                    "app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes",
                ).path
            },
        )
    }

    @Test
    fun `rFilePath resolves newest application R jar in AGP 8 directory`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val staleRootRJar = createRJar(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar",
            1000L
        )
        val currentProcessRJar = createRJar(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar",
            2000L
        )

        assertTrue(staleRootRJar.exists())
        assertEquals(currentProcessRJar.canonicalPath, info.rFilePath.canonicalPath)
    }

    @Test
    fun `rFilePath keeps root R jar when it is the newest application R jar`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val currentRootRJar = createRJar(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar",
            3000L
        )
        createRJar(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar",
            2000L
        )

        assertEquals(currentRootRJar.canonicalPath, info.rFilePath.canonicalPath)
    }

    @Test
    fun `rFilePath resolves newest application R jar across AGP 8 and AGP 9 directories`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val staleAgp9RJar = createRJar(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
            1000L
        )
        val currentAgp8RJar = createRJar(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar",
            2000L
        )

        assertTrue(staleAgp9RJar.exists())
        assertEquals(currentAgp8RJar.canonicalPath, info.rFilePath.canonicalPath)
    }

    @Test
    fun `rFilePath keeps matched candidate order when R jars have same modified time`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val agp9RJar = createRJar(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
            1000L
        )
        createRJar(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar",
            1000L
        )

        assertEquals(agp9RJar.canonicalPath, info.rFilePath.canonicalPath)
    }

    @Test
    fun `rFilePathCandidates returns distinct matched R jars`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val rootRJar = createRJar(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar",
            1000L
        )

        assertEquals(listOf(rootRJar.canonicalPath), info.rFilePathCandidates.map { it.canonicalPath })
    }

    @Test
    fun `javaClassPath resolves newest javac output dir when AGP upgrade leaves both paths`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val staleClassesDir = createJavaOutputDir(
            moduleRootDir,
            "build/intermediates/javac/debug/classes",
            1000L,
        )
        val currentCompileJavaDir = createJavaOutputDir(
            moduleRootDir,
            "build/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
            2000L,
        )

        assertTrue(staleClassesDir.exists())
        assertEquals(currentCompileJavaDir.canonicalPath, info.javaClassPath.canonicalPath)
    }

    @Test
    fun `javaClassPath keeps classes dir when it is the newest javac output dir`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val currentClassesDir = createJavaOutputDir(
            moduleRootDir,
            "build/intermediates/javac/debug/classes",
            3000L,
        )
        createJavaOutputDir(
            moduleRootDir,
            "build/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
            2000L,
        )

        assertEquals(currentClassesDir.canonicalPath, info.javaClassPath.canonicalPath)
    }

    @Test
    fun `javaClassPath falls back to classes dir when compileDebugJavaWithJavac is absent`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val classesDir = createJavaOutputDir(
            moduleRootDir,
            "build/intermediates/javac/debug/classes",
            1000L,
        )

        assertEquals(classesDir.canonicalPath, info.javaClassPath.canonicalPath)
    }

    @Test
    fun `allClassPath includes only resolved javaClassPath when multiple javac output dirs exist`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        createJavaOutputDir(
            moduleRootDir,
            "build/intermediates/javac/debug/classes",
            1000L,
        )
        val currentCompileJavaDir = createJavaOutputDir(
            moduleRootDir,
            "build/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
            2000L,
        )

        val javaClasspathEntries = info.allClassPath.filter {
            it.path.contains("intermediates/javac/debug")
        }

        assertEquals(listOf(currentCompileJavaDir.canonicalPath), javaClasspathEntries.map { it.canonicalPath })
    }

    @Test
    fun `javaClassPathCandidates returns distinct existing javac output dirs`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val classesDir = createJavaOutputDir(
            moduleRootDir,
            "build/intermediates/javac/debug/classes",
            1000L,
        )
        val compileJavaDir = createJavaOutputDir(
            moduleRootDir,
            "build/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
            2000L,
        )

        assertEquals(
            listOf(compileJavaDir.canonicalPath, classesDir.canonicalPath),
            info.javaClassPathCandidates.map { it.canonicalPath },
        )
    }

    @Test
    fun `mergedManifest resolves newest manifest when AGP upgrade leaves stale root manifest`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val staleRootManifest = createManifest(
            moduleRootDir,
            "build/intermediates/merged_manifests/debug/AndroidManifest.xml",
            1000L,
        )
        val currentProcessManifest = createManifest(
            moduleRootDir,
            "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
            2000L,
        )

        assertTrue(staleRootManifest.exists())
        assertEquals(currentProcessManifest.canonicalPath, info.mergedManifest.canonicalPath)
    }

    @Test
    fun `mergedManifest keeps root manifest when it is newer than process manifest`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val currentRootManifest = createManifest(
            moduleRootDir,
            "build/intermediates/merged_manifests/debug/AndroidManifest.xml",
            3000L,
        )
        createManifest(
            moduleRootDir,
            "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
            2000L,
        )

        assertEquals(currentRootManifest.canonicalPath, info.mergedManifest.canonicalPath)
    }

    @Test
    fun `mergedManifest keeps configured directory priority when manifests have same modified time`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug", buildDirRelativePath = "")
        val applicationManifest = createManifest(
            moduleRootDir,
            "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
            1000L,
        )
        createManifest(
            moduleRootDir,
            "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
            1000L,
        )

        assertEquals(applicationManifest.canonicalPath, info.mergedManifest.canonicalPath)
    }

    private fun createRJar(moduleRootDir: File, relativePath: String, lastModifiedTime: Long): File {
        val rJar = File(moduleRootDir, relativePath)
        rJar.parentFile.mkdirs()
        rJar.createNewFile()
        assertTrue(rJar.setLastModified(lastModifiedTime), "failed to set lastModifiedTime for ${rJar.path}")
        return rJar
    }

    private fun createJavaOutputDir(moduleRootDir: File, relativePath: String, lastModifiedTime: Long): File {
        val outputDir = File(moduleRootDir, relativePath)
        outputDir.mkdirs()
        val classFile = File(outputDir, "com/example/Dummy.class")
        classFile.parentFile.mkdirs()
        classFile.createNewFile()
        assertTrue(outputDir.setLastModified(lastModifiedTime), "failed to set lastModifiedTime for ${outputDir.path}")
        return outputDir
    }

    private fun createManifest(moduleRootDir: File, relativePath: String, lastModifiedTime: Long): File {
        val manifest = File(moduleRootDir, relativePath)
        manifest.parentFile.mkdirs()
        manifest.writeText("<manifest />")
        assertTrue(manifest.setLastModified(lastModifiedTime), "failed to set lastModifiedTime for ${manifest.path}")
        return manifest
    }
}
