package com.sickworm.intellij.jugg.project.data

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleBuildPathInfoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `usageFile should resolve to mapping usage output and be synced`() {
        val projectRootDir = File("/tmp/jugg-project")
        val moduleRootDir = File(projectRootDir, "app")
        val info = ModuleBuildPathInfo(projectRootDir, moduleRootDir, "release")

        assertEquals(
            File(moduleRootDir, "build/outputs/mapping/release/usage.txt").path,
            info.usageFile.path
        )
        assertTrue(
            info.allBuildPathRelative.any { it.path == File("build/outputs/mapping/release/usage.txt").path },
            "usage.txt should be included in synced build outputs"
        )
    }

    /**
     * AGP 9.0+ renamed compile_and_runtime_not_namespaced_r_class_jar to compile_and_runtime_r_class_jar.
     * Jugg should resolve R.jar correctly for the new namespaced path.
     */
    @Test
    fun `rFilePath resolves AGP 9 compile_and_runtime_r_class_jar path`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")

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
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")

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
    fun `rFilePath resolves newest application R jar in AGP 8 directory`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")
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
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")
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
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")
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
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")
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
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")
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
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")
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
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")
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
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")
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
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")
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
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")
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
}
