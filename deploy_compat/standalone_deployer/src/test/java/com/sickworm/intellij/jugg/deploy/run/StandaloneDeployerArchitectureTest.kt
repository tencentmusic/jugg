package com.sickworm.intellij.jugg.deploy.run

import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneDeployerArchitectureTest {

    @Test
    fun `standalone executor does not expose converter forwarding methods`() {
        val forbiddenMethods = setOf(
            "toJuggDevice",
            "toJuggLogger",
            "toStudioApk",
            "toJuggApkEntry",
            "toJuggByteString",
            "toJuggChangedClasses",
            "toStudioChangedClasses",
        )

        assertTrue(StandaloneApplyChangesExecutor::class.java.declaredMethods.none { it.name in forbiddenMethods })
    }

    @Test
    fun `base api does not provide Android runtime classes`() {
        val androidSources = findRepoFile("platform_compat/base_api/src/main/java").resolve("com/android")
        assertFalse(androidSources.exists())

        val baseApiLibDir = findRepoFile("platform_compat/base_api/build/libs")
        val baseApiJar = baseApiLibDir.listFiles().orEmpty().single { it.extension == "jar" }
        JarFile(baseApiJar).use { jar ->
            assertFalse(jar.entries().asSequence().any { it.name.startsWith("com/android/") })
        }
    }

    @Test
    fun `standalone deployer runtime contains only Java 11 compatible classes`() {
        val moduleDir = findRepoFile("deploy_compat/standalone_deployer")
        val classFiles = moduleDir.resolve("build/classes").walkTopDown()
            .filter { it.isFile && it.extension == "class" }.toList()
        assertTrue(classFiles.isNotEmpty())
        classFiles.forEach { classFile ->
            assertTrue(readClassMajor(classFile.inputStream()) <= JAVA_11_CLASS_MAJOR, classFile.path)
        }
        moduleDir.resolve("libs").listFiles().orEmpty().filter { it.extension == "jar" }.forEach { jarFile ->
            JarFile(jarFile).use { jar ->
                jar.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { entry ->
                    assertTrue(readClassMajor(jar.getInputStream(entry)) <= JAVA_11_CLASS_MAJOR, "${jarFile.name}!/${entry.name}")
                }
            }
        }
    }

    @Test
    fun `standalone deployer does not package Android Studio runtime jar`() {
        val moduleDir = findRepoFile("deploy_compat/standalone_deployer")
        val jars = moduleDir.walkTopDown().filter { it.isFile && it.extension == "jar" && "/build/" !in "/${it.invariantSeparatorsPath}" }
        assertFalse(jars.any { it.name == "sdk-tools.jar" })
    }

    @Test
    fun `source checksum manifest covers the reconstructed Quail closure`() {
        val moduleDir = findRepoFile("deploy_compat/standalone_deployer")
        val sourceRoot = moduleDir.resolve("src/main/java/com/android/tools/deployer")
        val expected = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "java" }
            .map { it.relativeTo(moduleDir.resolve("src/main/java")).invariantSeparatorsPath.removeSuffix(".java") }
            .toSet()
        val recorded = moduleDir.resolve("src/main/resources/deployer/quail/SOURCE_CLASSES.sha256")
            .readLines().filterNot { it.startsWith("#") }.map { it.substringAfterLast("  ") }.toSet()

        assertEquals(expected, recorded)
    }

    private fun readClassMajor(input: java.io.InputStream): Int {
        return DataInputStream(input).use { stream ->
            check(stream.readInt() == CLASS_MAGIC) { "Invalid class file" }
            stream.readUnsignedShort()
            stream.readUnsignedShort()
        }
    }

    private fun findRepoFile(path: String): File {
        var current = File("").absoluteFile
        while (true) {
            val candidate = current.resolve(path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: error("Cannot find $path")
        }
    }

    private companion object {
        const val CLASS_MAGIC = 0xCAFEBABE.toInt()
        const val JAVA_11_CLASS_MAJOR = 55
    }
}
