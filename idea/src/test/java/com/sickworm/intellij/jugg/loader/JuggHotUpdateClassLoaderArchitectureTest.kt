package com.sickworm.intellij.jugg.loader

import com.sickworm.intellij.jugg.project.runtime.HotUpdateLoadManifest
import com.sickworm.intellij.jugg.ide.logic.IdeaPlatformApi
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.server.HotUpdateBootstrapChildCaller
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files

/** Guards the stable bootstrap API shared by the plugin and hot-update classloaders. */
class JuggHotUpdateClassLoaderArchitectureTest {

    @Test
    fun `platform bridge stays in host classloader`() {
        val missingContractTypes = IPlatformApi::class.java.methods
            .flatMap { method -> listOf(method.returnType) + method.parameterTypes }
            .map(::componentType)
            .filter { it.name.startsWith(JUGG_PACKAGE_PREFIX) }
            .filterNot { JuggLoader.canNotHotUpdateClass.contains(it.name) }
            .map(Class<*>::getName)

        assertTrue("Platform bridge contract types are not host loaded: $missingContractTypes", missingContractTypes.isEmpty())
        assertTrue(JuggLoader.canNotHotUpdateClass.contains(PlatformApi::class.java.name))
        assertTrue(JuggLoader.canNotHotUpdateClass.contains(IPlatformApi::class.java.name))
        assertTrue(JuggLoader.canNotHotUpdateClass.contains(IdeaPlatformApi::class.java.name))
    }

    @Test
    fun `bootstrap API only exposes platform classes`() {
        val leakedTypes = JuggHotUpdateBootstrap::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
            .flatMap { method ->
                (listOf(method.returnType) + method.parameterTypes).map { type -> method.name to type }
            }
            .filterNot { (_, type) -> isPlatformType(type) }
            .map { (methodName, type) -> "$methodName: ${type.name}" }

        assertTrue("Bootstrap API leaks hot-update types: $leakedTypes", leakedTypes.isEmpty())
    }

    @Test
    fun `child runtime can call bootstrap without linking hot update types`() {
        val parentClassLoader = JuggHotUpdateBootstrap::class.java.classLoader
        val childClassesDir = Files.createTempDirectory("jugg-hot-update-child-classes").toFile()
        copyClass(HotUpdateBootstrapChildCaller::class.java, childClassesDir)
        copyClass(HotUpdateLoadManifest::class.java, childClassesDir)
        try {
            JuggPriorityURLClassLoader(
                arrayOf(childClassesDir.toURI().toURL()),
                parentClassLoader,
            ) { className -> className == JuggHotUpdateBootstrap::class.java.name }.use { childClassLoader ->
                val childManifestClass = childClassLoader.loadClass(HotUpdateLoadManifest::class.java.name)
                assertNotSame(HotUpdateLoadManifest::class.java, childManifestClass)
                val callerClass = childClassLoader.loadClass(HotUpdateBootstrapChildCaller::class.java.name)
                assertSame(childClassLoader, callerClass.classLoader)
                callerClass.getMethod("activeJarFileNames").invoke(callerClass.getConstructor().newInstance())
            }
        } finally {
            childClassesDir.deleteRecursively()
        }
    }

    private fun copyClass(type: Class<*>, targetRoot: File) {
        val resourcePath = type.name.replace('.', '/') + ".class"
        val target = targetRoot.resolve(resourcePath)
        target.parentFile.mkdirs()
        type.classLoader.getResourceAsStream(resourcePath).use { input ->
            checkNotNull(input) { "Class resource not found: $resourcePath" }
            target.outputStream().use(input::copyTo)
        }
    }

    private fun isPlatformType(type: Class<*>): Boolean {
        if (type.isPrimitive || type == Void.TYPE) return true
        if (type.isArray) return isPlatformType(type.componentType)
        return type.classLoader == null
    }

    private fun componentType(type: Class<*>): Class<*> {
        var componentType = type
        while (componentType.isArray) {
            componentType = componentType.componentType
        }
        return componentType
    }

    private companion object {
        const val JUGG_PACKAGE_PREFIX = "com.sickworm.intellij.jugg."
    }
}
