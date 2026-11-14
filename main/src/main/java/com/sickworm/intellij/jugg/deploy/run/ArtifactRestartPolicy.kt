package com.sickworm.intellij.jugg.deploy.run

// Generated registration indexes only; ordinary route classes and Autowired helpers are excluded.
private val HOT_RELOAD_CLASS_BLACKLIST = listOf(
    "ARouter\$\$Group\$\$",
    "ARouter\$\$Root\$\$",
    "ARouter\$\$Providers\$\$",
    "ARouter\$\$Interceptors\$\$",
)

/**
 * Keeps artifact-based Hot Reload exclusions together. Recompute from the payload after APK
 * filtering or history replay, without persisting compiler hints or previous deployment types.
 */
internal fun JuggDeployData.hasArtifactsRequiringAppRestart(): Boolean {
    // Structural changes, extracted Flutter JIT files and loaded native libraries need a new process.
    if (hotFixModifiedClasses.isNotEmpty() || flutterJitRuntimeFiles.isNotEmpty() || nativeLibraryOverlays.isNotEmpty()) {
        return true
    }

    // Redefining an ARouter index does not rebuild its process-wide registration caches.
    // New indexes also need registration. Match simple names to support customized packages,
    // and conservatively restart whenever an index is present, including regenerated identical code.
    val classes = newClasses.asSequence() + hotReloadModifiedClasses.asSequence()
    if (classes.flatMap { it.classNodes.asSequence() }.any { node ->
            val simpleName = node.className.substringAfterLast('/')
            HOT_RELOAD_CLASS_BLACKLIST.any { simpleName.startsWith(it) }
        }) {
        return true
    }

    // APK-root classpath resources (including legacy Compose resources) can be cached by the
    // ClassLoader. Modern Compose paths come from Gradle metadata and retain their separate hint.
    return overlays.any {
        !it.name.startsWith("res/") && !it.name.startsWith("assets/") && it.name != "resources.arsc"
    }
}
