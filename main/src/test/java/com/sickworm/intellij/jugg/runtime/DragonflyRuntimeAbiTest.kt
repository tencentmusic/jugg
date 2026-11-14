package com.sickworm.intellij.jugg.runtime

import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.Type
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.ClassNode
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.InvokeDynamicInsnNode
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.MethodInsnNode
import org.junit.Test
import java.io.File
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Protects host Compose linkage and private Kotlin isolation in the shipped runtime. */
class DragonflyRuntimeAbiTest {
    private val runtime = javaClass.getResourceAsStream("/deploy/jugg-runtime.jar")!!.use { input ->
        JarInputStream(input).use { jar ->
            val classes = mutableMapOf<String, ByteArray>()
            var entry = jar.nextJarEntry
            while (entry != null) {
                if (entry.name.endsWith(".class")) classes[entry.name] = jar.readBytes()
                entry = jar.nextJarEntry
            }
            classes
        }
    }

    @Test
    fun runtime_shouldPreserveHostComposeMethodDescriptors() {
        val sourceJar = File("../jvmti_agent/libs/dragonfly/dragonfly_0.jar")
        var hostCallCount = 0
        ZipFile(sourceJar).use { source ->
            source.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                val original = source.getInputStream(entry).use { classNode(it.readBytes()) }
                val expected = hostCalls(original)
                if (expected.isNotEmpty()) {
                    val relocatedName = entry.name.replace("top/kokomi/dragonfly/", PRIVATE_PACKAGE)
                    val actual = hostCalls(classNode(runtime.getValue(relocatedName)))
                    assertEquals(expected, actual, "Host Compose ABI changed in ${entry.name}")
                    hostCallCount += expected.size
                }
            }
        }
        assertTrue(hostCallCount > 0, "The runtime must exercise host Compose APIs")
    }

    @Test
    fun runtime_shouldCreateHostCallbacksWithoutExportingBundledKotlin() {
        val extractor = classNode(runtime.getValue("${PRIVATE_PACKAGE}compose/extractor/ComposeExtractorKt.class"))
        val callbacks = extractor.methods.flatMap { it.instructions.toList() }
            .filterIsInstance<InvokeDynamicInsnNode>()
            .map { Type.getReturnType(it.desc).internalName }
        assertTrue("kotlin/jvm/functions/Function3" in callbacks,
            "The mapTree callback must implement the host Kotlin interface")
        assertFalse(runtime.keys.any { it.startsWith("kotlin/") || it.startsWith("kotlinx/") },
            "Bundled Kotlin definitions must remain private")
        assertTrue(runtime.containsKey("${PRIVATE_PACKAGE}runtime/kotlin/jvm/functions/Function3.class"))
    }

    private fun classNode(bytes: ByteArray) = ClassNode().also { ClassReader(bytes).accept(it, 0) }

    private fun hostCalls(node: ClassNode) = node.methods.flatMap { it.instructions.toList() }
        .filterIsInstance<MethodInsnNode>()
        .filter { it.owner.startsWith("androidx/compose/") }
        .map { "${it.owner}.${it.name}${it.desc}" }.toSet()

    companion object {
        private const val PRIVATE_PACKAGE = "com/sickworm/intellij/jugg/internal/dragonfly/"
    }
}
