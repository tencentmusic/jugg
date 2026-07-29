package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.Variant
import org.gradle.api.Project

private val variantCollectorKey = "com.sickworm.intellij.jugg.collectedVariants"

/** Collects Android variants during configuration for AGP versions without legacy variant APIs. */
fun registerAndroidComponentsVariants(rootProject: Project, project: Project) {
    val androidComponents = project.extensions.findByName("androidComponents") ?: return
    val registered = invokeOnVariantsCompat(androidComponents) { variant ->
        val name = reflector(variant)["name"]?.valueString ?: return@invokeOnVariantsCompat
        val variants = getOrCreateCollectedVariants(rootProject).getOrPut(project.path) { mutableListOf() }
        if (variants.none { it.name == name }) {
            variants.add(Variant(name, null))
        }
    }
    if (!registered) {
        println("Jugg: androidComponents.onVariants unavailable for ${project.path}, use legacy variants fallback.")
    }
}

/** Returns variants collected for one Gradle project without retaining AGP variant instances. */
fun getCollectedAndroidVariants(rootProject: Project, project: Project): List<Variant> {
    val extra = rootProject.extensions.extraProperties
    if (!extra.has(variantCollectorKey)) {
        return emptyList()
    }
    @Suppress("UNCHECKED_CAST")
    val variants = extra.get(variantCollectorKey) as? Map<String, List<Variant>> ?: return emptyList()
    return variants[project.path] ?: emptyList()
}

/** Registers an all-variants callback across Android Components API versions. */
fun invokeOnVariantsCompat(androidComponents: Any, callback: (Any?) -> Unit): Boolean {
    return try {
        val method = androidComponents::class.java.methods.firstOrNull { method ->
            method.name == "onVariants" && method.parameterCount == 2 && method.parameterTypes[1].isInterface
        } ?: return false
        val actionInterface = method.parameterTypes[1]
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            actionInterface.classLoader,
            arrayOf(actionInterface),
        ) { _, _, args ->
            callback(args?.firstOrNull())
            null
        }
        val selector = androidComponents::class.java.getMethod("selector").invoke(androidComponents)
        method.invoke(androidComponents, selector, proxy)
        true
    } catch (e: Throwable) {
        println("Jugg: register androidComponents.onVariants failed: $e")
        false
    }
}

@Suppress("UNCHECKED_CAST")
private fun getOrCreateCollectedVariants(rootProject: Project): MutableMap<String, MutableList<Variant>> {
    val extra = rootProject.extensions.extraProperties
    val existing = if (extra.has(variantCollectorKey)) extra.get(variantCollectorKey) else null
    if (existing is MutableMap<*, *>) {
        return existing as MutableMap<String, MutableList<Variant>>
    }
    val variants = mutableMapOf<String, MutableList<Variant>>()
    extra.set(variantCollectorKey, variants)
    return variants
}
