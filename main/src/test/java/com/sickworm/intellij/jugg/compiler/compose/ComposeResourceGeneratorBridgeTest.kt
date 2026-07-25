package com.sickworm.intellij.jugg.compiler.compose

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.project.data.ComposeResourceInfo
import com.sickworm.intellij.jugg.project.data.ComposeResourceDirectory
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.file
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerOutputParser
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposeResourceGeneratorBridgeTest {

    private val assetsRoot = File(System.getProperty("user.dir"), "src/test/assets/compose/1.7.3")

    @Test
    fun `preserves generated Kotlin diagnostics on Compose inputs`() {
        val module = ModuleInfo.virtualModule.copy(name = "kmp")
        val generated = CompileFile(
            CompileFile.Type.Kotlin,
            File("/tmp/generated/Res.kt"),
            File("/tmp/generated"),
            module,
        )
        val parser = KotlinCompilerOutputParser(listOf(generated), TestGlobal.logger)
        parser.printStream.println("error: original generated Kotlin diagnostic")
        parser.flush()
        val generatedResult = CompileResult(
            CompileTask(listOf(generated), File("/tmp/classes"), CompileStatusHolder.DEFAULT),
            parser.getResult(isCompileSuccess = false),
            emptyList(),
        )
        val resource = CompileFile(
            CompileFile.Type.ComposeResource,
            File("/project/src/commonMain/composeResources/values/strings.xml"),
            File("/project/src/commonMain/composeResources"),
            module,
        )
        val resourceTask = CompileTask(listOf(resource), File("/tmp/output"), CompileStatusHolder.DEFAULT)

        val mapped = ComposeResourceCompiler.mapGeneratedCompileFailure(resourceTask, generatedResult)

        assertEquals(generatedResult.details.single().getFailure().errors, mapped.details.single().getFailure().errors)
        assertEquals(resource, mapped.details.single().file)
    }

    @Test
    fun `classifies common sources from IDE source set identity`() {
        val root = File("/project/kmp")
        val owner = ModuleInfo.virtualModule.copy(
            name = "kmp",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = root,
        )
        val sharedMain = ModuleInfo.virtualModule.copy(
            name = "kmp.sharedMain",
            moduleType = ModuleInfo.Type.Unknown,
            moduleRootDir = root,
        )
        val androidMain = ModuleInfo.virtualModule.copy(
            name = "kmp.androidMain",
            moduleType = ModuleInfo.Type.Unknown,
            moduleRootDir = root,
        )
        val info = ComposeResourceInfo(
            generatorClasspath = emptyList(),
            packageName = "com.example.resources",
            publicResClass = true,
            resourceDirectories = listOf(
                ComposeResourceDirectory("sharedMain", File(root, "custom/shared")),
                ComposeResourceDirectory("androidMain", File(root, "custom/android")),
            ),
            assetRelativePath = "composeResources/com.example.resources",
        )

        assertEquals(
            setOf("sharedMain"),
            ComposeResourceCompiler.resolveCommonSourceSetNames(owner, info, listOf(owner, sharedMain, androidMain)),
        )
    }

    @Test
    fun `generates Res accessors and collectors equal to Compose 1_7_3 golden files`() {
        val parent = disposable()
        val outputDir = Files.createTempDirectory("compose-generated").toFile()
        try {
            val generated = ComposeResourceGeneratorBridge(parent).generate(
                composeInfo(),
                resourcesBySourceSet(),
                setOf("commonMain"),
                outputDir,
            )

            assertEquals(goldenFiles(), generated.allFiles.toRelativeFiles(outputDir))
            assertTrue(generated.commonFiles.all { it.toRelativePath(outputDir).startsWith("common") })
            assertTrue(generated.platformFiles.all { it.toRelativePath(outputDir).startsWith("android") })
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `generates sources with Compose 1_10_3 generator API`() {
        val parent = disposable()
        val outputDir = Files.createTempDirectory("compose-1.10.3-output").toFile()
        try {
            val generated = ComposeResourceGeneratorBridge(parent).generate(
                composeInfo(listOf(composePluginJar("1.10.3"), kotlinStdlib("2.3.20"))).copy(
                    generateResourceContentHash = true,
                ),
                resourcesBySourceSet(),
                setOf("commonMain"),
                outputDir,
            )

            assertTrue(generated.allFiles.any { it.name == "Res.kt" })
            assertTrue(generated.allFiles.any { it.readText().contains("ResourceContentHash") })
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `generates sources with Compose 1_6_0 generator API`() {
        val parent = disposable()
        val outputDir = Files.createTempDirectory("compose-1.6.0-output").toFile()
        val commonResources = resourcesBySourceSet().getValue("commonMain")
            .filterKeys { it in setOf(ComposeResourceType.STRING, ComposeResourceType.DRAWABLE, ComposeResourceType.FONT) }
        try {
            val generated = ComposeResourceGeneratorBridge(parent).generate(
                composeInfo(listOf(composePluginJar("1.6.0"), kotlinStdlib("1.9.22"))),
                mapOf("commonMain" to commonResources),
                setOf("commonMain"),
                outputDir,
            )

            assertTrue(generated.commonFiles.any { it.name == "Res.kt" })
            assertTrue(generated.commonFiles.any { it.name == "String0.kt" })
            assertTrue(generated.platformFiles.isEmpty())
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `rejects classpath without Compose generator classes`() {
        val parent = disposable()
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                ComposeResourceGeneratorBridge(parent).generate(
                    composeInfo(listOf(kotlinStdlib())),
                    resourcesBySourceSet(),
                    setOf("commonMain"),
                    Files.createTempDirectory("compose-output").toFile(),
                )
            }
            assertEquals(UNSUPPORTED_MESSAGE, error.message)
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `closes cached classloaders on dispose`() {
        val parent = disposable()
        val bridge = ComposeResourceGeneratorBridge(parent)
        bridge.generate(
            composeInfo(),
            resourcesBySourceSet(),
            setOf("commonMain"),
            Files.createTempDirectory("compose-output").toFile(),
        )
        val cacheField = bridge.javaClass.declaredFields.single {
            Map::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        val loader = (cacheField.get(bridge) as Map<*, *>).values.single() as URLClassLoader
        assertTrue(loader.findResource(UNUSED_PLUGIN_RESOURCE) != null)

        Disposer.dispose(parent)

        assertNull(loader.findResource(UNUSED_PLUGIN_RESOURCE))
        assertTrue((cacheField.get(bridge) as Map<*, *>).isEmpty())
    }

    private fun composeInfo(
        generatorClasspath: List<File> = listOf(pluginJar(), kotlinStdlib()),
    ) = ComposeResourceInfo(
        generatorClasspath = generatorClasspath,
        packageName = PACKAGE_NAME,
        publicResClass = true,
        resourceDirectories = emptyList(),
        assetRelativePath = "composeResources/$PACKAGE_NAME",
    )

    private fun resourcesBySourceSet(): Map<String, Map<ComposeResourceType, Map<String, List<ComposeResourceItem>>>> {
        val scanner = ComposeResourceScanner()
        return listOf("commonMain", "androidMain").associateWith { sourceSet ->
            val root = File(assetsRoot, "prepared/$sourceSet/composeResources")
            scanner.scan(root, root, "composeResources/$PACKAGE_NAME")
        }
    }

    private fun goldenFiles(): Map<String, String> = File(assetsRoot, "generated")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.toRelativePath(File(assetsRoot, "generated")) to it.readText() }

    private fun List<File>.toRelativeFiles(root: File): Map<String, String> =
        associate { it.toRelativePath(root) to it.readText() }

    private fun File.toRelativePath(root: File): String = relativeTo(root).path.replace(File.separatorChar, '/')

    private fun pluginJar(): File = composePluginJar("1.7.3")

    private fun composePluginJar(version: String): File = fixtureClasspathFile("compose-gradle-plugin-$version.jar")

    private fun kotlinStdlib(version: String = "2.1.0"): File = fixtureClasspathFile("kotlin-stdlib-$version.jar")

    private fun fixtureClasspathFile(name: String): File {
        val cache = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1")
        return cache.walkTopDown().firstOrNull { it.isFile && it.name == name }
            ?: error("Compose fixture classpath entry is missing: $name")
    }

    private fun disposable() = object : Disposable {
        override fun dispose() = Unit
    }

    private companion object {
        const val PACKAGE_NAME = "com.sickworm.jugg.demo.kmp.generated.resources"
        const val UNSUPPORTED_MESSAGE =
            "Unsupported Compose resource generator API."
        const val UNUSED_PLUGIN_RESOURCE = "org/jetbrains/compose/resources/AssembleTargetResourcesTask.class"
    }
}
