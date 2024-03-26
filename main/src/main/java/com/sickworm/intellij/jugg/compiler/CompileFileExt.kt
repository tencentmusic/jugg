package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.project.ChangedFile

private const val KEY_DEPENDENCY_NAME = "dependency_name"

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


// e.g. Gradle: org.reactivestreams:reactive-streams:1.0.3 -> #org.reactivestreams#reactive-streams.dex
private fun dependencyNameToDexFileName(libraryName: String): String {
    try {
        libraryName.substringAfter(": ").split(":").also {
            return "#${it[0]}#${it[1]}.dex"
        }
    } catch (e: Exception) {
        return libraryName.replace(" ", "").replace(":", "#")
    }
}