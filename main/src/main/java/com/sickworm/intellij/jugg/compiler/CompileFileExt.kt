package com.sickworm.intellij.jugg.compiler

import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.project.ChangedFile
import java.io.File

private const val KEY_DEPENDENCY_NAME = "dependency_name"
private const val KEY_OLD_DEPENDENCY_MANIFEST = "relative_old_dependency_manifest"
private const val KEY_OLD_DEPENDENCY_JAR = "relative_old_dependency_jar"
private const val KEY_OLD_DEPENDENCY_RES = "relative_old_dependency_res"

// name extension

val ChangedFile.dependencyName: String
    get() = extraInfo[KEY_DEPENDENCY_NAME] as String

val ChangedFile.jarDexFileName: String
    get() = dependencyNameToDexFileName(dependencyName)

val CompileFile.isDependency: Boolean
    get() = extraInfo.containsKey(KEY_DEPENDENCY_NAME)

val CompileFile.dependencyName: String
    get() = extraInfo[KEY_DEPENDENCY_NAME] as? String ?: "unknown_dependency"

val CompileFile.jarDexFileName: String
    get() = dependencyNameToDexFileName(dependencyName)

// e.g. Gradle: reactive-streams-1.0.3.jar
fun ChangedFile.withDependencyName(name: String): ChangedFile {
    return copy(extraInfo = extraInfo + (KEY_DEPENDENCY_NAME to name))
}

fun CompileFile.withDependencyName(name: String): CompileFile {
    return copy(extraInfo = extraInfo + (KEY_DEPENDENCY_NAME to name))
}

// relative manifest extension

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

// relative jar extension

val CompileFile.oldJar: File?
    get() = extraInfo[KEY_OLD_DEPENDENCY_JAR] as? File

fun ChangedFile.withOldJar(file: File?): ChangedFile {
    if (file == null) {
        return copy()
    }
    return copy(extraInfo = extraInfo + (KEY_OLD_DEPENDENCY_JAR to file))
}

fun CompileFile.withOldJar(file: File?): CompileFile {
    if (file == null) {
        return copy()
    }
    return copy(extraInfo = extraInfo + (KEY_OLD_DEPENDENCY_JAR to file))
}

// relative resources extension

val CompileFile.oldRes: File?
    get() = extraInfo[KEY_OLD_DEPENDENCY_RES] as? File

fun ChangedFile.withOldRes(file: File?): ChangedFile {
    if (file == null) {
        return copy()
    }
    return copy(extraInfo = extraInfo + (KEY_OLD_DEPENDENCY_RES to file))
}

fun CompileFile.withOldRes(file: File?): CompileFile {
    if (file == null) {
        return copy()
    }
    return copy(extraInfo = extraInfo + (KEY_OLD_DEPENDENCY_RES to file))
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

val ApkInfo.apkInfoKey: String
    get() = "ApkInfo:[" +
            files.joinToString(";") {
                it.apkFile.absolutePath + ":" + it.apkFile.lastModified()
            } + "]"


fun List<CompileFile>.desc(): String {
    val compileFilesMap = this.groupBy {
        it.module.name
    }
    return compileFilesMap.entries.joinToString("\n") { entry ->
        val value = entry.value
            .groupBy {
                if (it.isDependency) {
                    return@groupBy "library"
                }
                val type = when (it.type) {
                    CompileFile.Type.Java -> "source"
                    CompileFile.Type.Kotlin -> "source"
                    CompileFile.Type.Class -> "class"
                    CompileFile.Type.Asset -> "asset"
                    CompileFile.Type.Resource -> "resource"
                    CompileFile.Type.Flat -> "flat"
                    CompileFile.Type.BuildFile -> "gradle"
                    CompileFile.Type.AndroidManifest -> "manifest"
                    CompileFile.Type.DexToChangePackageName -> "dex"
                }
                return@groupBy type
            }
            .mapValues {
                it.value.map { file ->
                    if (file.isDependency) {
                        file.dependencyName
                    } else {
                        file.file.name
                    }
                }.distinct()
            }
        val valueContent = value.entries.joinToString("\n    ", prefix = "    ") {
            "${it.key}: ${it.value}"
        }
        return@joinToString "${entry.key}: [\n$valueContent\n]"
    }
}

fun CompileTask.toCancelResult(): CompileResult {
    return CompileResult(this, this.files.map {
        Result.failure(CompileError(it, listOf(0L to "Compile canceled.")))
    }, emptyList())
}