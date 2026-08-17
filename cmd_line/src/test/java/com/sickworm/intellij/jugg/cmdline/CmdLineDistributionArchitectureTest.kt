package com.sickworm.intellij.jugg.cmdline

import org.junit.Test
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.jar.JarFile
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CmdLineDistributionArchitectureTest {

    @Test
    fun `standalone bundle contains the complete content addressed Java 11 runtime`() {
        val distributionDir = findRepoFile("cmd_line/build/distributions")
        val bundle = distributionDir.listFiles().orEmpty().single {
            it.name.startsWith("jugg-standalone-") && it.extension == "zip"
        }
        ZipFile(bundle).use { zip ->
            listOf("install.command", "install.sh", "install.cmd", "standalone_bundle_manifest.json").forEach {
                assertNotNull(zip.getEntry(it), "Missing bundle entry: $it")
            }
            val manifest = zip.getInputStream(zip.getEntry("standalone_bundle_manifest.json")).reader().use {
                JsonParser.parseReader(it).asJsonObject
            }
            assertTrue(manifest.get("releaseBuildId").asString.isNotBlank())
            val declaredJars = manifest.getAsJsonArray("jarFileNames").map { it.asString }
            val actualJars = zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("jars/") && it.endsWith(".jar") }.map { it.removePrefix("jars/") }.toList()
            assertEquals(declaredJars.sorted(), actualJars.sorted())
            assertTrue(declaredJars.isNotEmpty())
            assertTrue(declaredJars.all(CONTENT_ADDRESSED_JAR::matches))
            assertFalse(declaredJars.any { it.startsWith("jna-") })
            actualJars.forEach { jarName ->
                zip.getInputStream(zip.getEntry("jars/$jarName")).use { verifyJava11Classes(it, jarName) }
            }
            val bootstrapFiles = manifest.getAsJsonArray("bootstrapFileNames").map { it.asString }
            assertTrue(bootstrapFiles.contains("standalone-bootstrap.jar"))
            bootstrapFiles.forEach { fileName ->
                val entry = assertNotNull(zip.getEntry("bootstrap/$fileName"), "Missing bootstrap file: $fileName")
                zip.getInputStream(entry).use { verifyJava11Classes(it, fileName) }
            }
            assertNotNull(zip.getEntry("cli/jugg.py"))
            val installSh = zip.readText("install.sh")
            val installCmd = zip.readText("install.cmd")
            val posixCli = zip.readText("cli/jugg")
            val windowsCli = zip.readText("cli/jugg.cmd")
            assertTrue(installSh.contains("JAVA_HOME"))
            assertTrue(installSh.contains("command -v java"))
            assertTrue(installCmd.contains("%JAVA_HOME%\\bin\\java.exe"))
            assertTrue(installCmd.contains("where java"))
            assertTrue(posixCli.contains("sys.version_info < (3, 7)"))
            assertTrue(windowsCli.contains("sys.version_info ^< (3, 7)"))
        }
    }

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

    @Test
    fun `content addressed runtime prepares packaged deployer resources`() {
        val runtimeJars = findRepoFile("cmd_line/build/standalone-bundle/jars").listFiles()
            .orEmpty().filter { it.extension == "jar" }
        val root = Files.createTempDirectory("jugg-standalone-protocol").toFile()
        val source = root.resolve("src/ProtocolDependencyProbe.java")
        val classes = root.resolve("classes").apply { mkdirs() }
        source.parentFile.mkdirs()
        source.writeText("""
            public final class ProtocolDependencyProbe {
                public static void main(String[] args) throws Exception {
                    Class<?> type = Class.forName("com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerResources");
                    Object instance = type.getField("INSTANCE").get(null);
                    type.getMethod("prepare", String.class).invoke(instance, "test-version");
                }
            }
        """.trimIndent())
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", classes.path, source.path))
        val classpath = (listOf(classes) + runtimeJars).joinToString(File.pathSeparator) { it.path }
        val java = File(System.getProperty("java.home"), "bin/java")

        val process = ProcessBuilder(
            java.path,
            "-Djugg.root.dir=${root.resolve("home").path}",
            "-cp",
            classpath,
            "ProtocolDependencyProbe",
        ).redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Protocol dependency probe timed out")
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(0, process.exitValue(), output)
    }

    private fun findRepoFile(path: String): File {
        var current = File("").absoluteFile
        while (true) {
            val candidate = current.resolve(path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: error("Cannot find $path")
        }
    }

    private fun verifyJava11Classes(input: InputStream, jarName: String) {
        java.util.jar.JarInputStream(input).use { jar ->
            while (true) {
                val entry = jar.nextJarEntry ?: break
                if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/versions/")) continue
                val header = ByteArray(8)
                var offset = 0
                while (offset < header.size) {
                    val read = jar.read(header, offset, header.size - offset)
                    if (read < 0) break
                    offset += read
                }
                assertEquals(8, offset, "Invalid class header: $jarName!/${entry.name}")
                val major = (header[6].toInt() and 0xff shl 8) or (header[7].toInt() and 0xff)
                assertTrue(major <= 55, "Java $major class is not Java 11 compatible: $jarName!/${entry.name}")
            }
        }
    }

    private fun ZipFile.readText(path: String): String {
        val entry = assertNotNull(getEntry(path), "Missing bundle entry: $path")
        return getInputStream(entry).reader().use { it.readText() }
    }

    private companion object {
        val CONTENT_ADDRESSED_JAR = Regex(".+-[0-9a-f]{64}\\.jar")
    }
}
