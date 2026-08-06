package com.sickworm.intellij.jugg.cmdline

import org.junit.Test
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CmdLineDistributionArchitectureTest {

    @Test
    fun `distribution has one owner for each Android runtime class`() {
        val jars = findRepoFile("cmd_line/build/install/cmd_line/lib").listFiles()
            .orEmpty().filter { it.extension == "jar" }
        val expectedOwners = mapOf(
            "com/android/ddmlib/IDevice.class" to "ddmlib-",
            "com/android/ddmlib/IShellEnabledDevice.class" to "ddmlib-",
            "com/android/tools/deployer/model/Apk.class" to "standalone_deployer-",
            "com/android/tools/deployer/model/ApkEntry.class" to "standalone_deployer-",
            "com/android/tools/deployer/model/DexClass.class" to "standalone_deployer-",
            "com/android/tools/idea/protobuf/ByteString.class" to "studio-proto.jar",
        )

        expectedOwners.forEach { (className, expectedOwner) ->
            val owners = jars.filter { jar -> JarFile(jar).use { it.getJarEntry(className) != null } }
            assertEquals(1, owners.size, "$className owners: ${owners.map(File::getName)}")
            assertTrue(owners.single().name.startsWith(expectedOwner), "$className owner: ${owners.single().name}")
        }

        val baseApi = jars.single { it.name == "base_api.jar" }
        JarFile(baseApi).use { jar ->
            assertFalse(jar.entries().asSequence().any { it.name.startsWith("com/android/") })
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
}
