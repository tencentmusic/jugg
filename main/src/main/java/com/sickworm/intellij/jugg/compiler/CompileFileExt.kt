package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.project.ChangedFile
import java.io.File

private const val KEY_DEPENDENCY_NAME = "dependency_name"
private const val KEY_OLD_DEPENDENCY_MANIFEST = "relative_old_dependency_manifest"

val ChangedFile.dependencyName: String
    get() = extraInfo[KEY_DEPENDENCY_NAME] as String

val ChangedFile.jarDexFileName: String
    get() = dependencyNameToDexFileName(dependencyName)

val CompileFile.isDependency: Boolean
    get() = extraInfo.containsKey(KEY_DEPENDENCY_NAME)

val CompileFile.dependencyName: String
    get() = extraInfo[KEY_DEPENDENCY_NAME] as String

val CompileFile.jarDexFileName: String
    get() = dependencyNameToDexFileName(dependencyName)

// e.g. Gradle: reactive-streams-1.0.3.jar
fun ChangedFile.withDependencyName(name: String): ChangedFile {
    return copy(extraInfo = extraInfo + (KEY_DEPENDENCY_NAME to name))
}

fun CompileFile.withDependencyName(name: String): CompileFile {
    return copy(extraInfo = extraInfo + (KEY_DEPENDENCY_NAME to name))
}


val CompileFile.oldManifest: File?
    get() = extraInfo[KEY_OLD_DEPENDENCY_MANIFEST] as? File

fun ChangedFile.withOldManifest(file: File?): ChangedFile {
    if (file == null) {
        return copy()
    }
    return copy(extraInfo = extraInfo + (KEY_OLD_DEPENDENCY_MANIFEST to file))
}

fun CompileFile.withOldManifest(file: File?): CompileFile {
    if (file == null) {
        return copy()
    }
    return copy(extraInfo = extraInfo + (KEY_OLD_DEPENDENCY_MANIFEST to file))
}


// e.g. org.reactivestreams:reactive-streams:1.0.3 -> #org.reactivestreams#reactive-streams.dex
private fun dependencyNameToDexFileName(libraryName: String): String {
    try {
        if (libraryName.contains(":")) {
            // e.g. org.reactivestreams:reactive-streams:1.0.3 -> #org.reactivestreams#reactive-streams.dex
            val (group, name, _) = libraryName.split(":")
            return "#$group#$name.dex"
        } else {
            // e.g. ./app/libs/library2.v2.jar -> #app#libs#library2#library2.v2.dex
            return libraryName
                .replace("./", "#")
                .replace("/", "#")
                .replace(".\\", "#")
                .replace("\\", "#")
                .replace(".jar", "") + ".dex"
        }
    } catch (e: Exception) {
        return libraryName
            .replace("./", "#")
            .replace("/", "#")
            .replace(".\\", "#")
            .replace("\\", "#")
            .replace(".jar", "") + ".dex"
    }
}