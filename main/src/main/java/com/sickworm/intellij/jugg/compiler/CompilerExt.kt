package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

private const val KEY_DEPENDENCY_NAME = "dependency_name"
private const val KEY_OLD_DEPENDENCY_MANIFEST = "relative_old_dependency_manifest"
private const val KEY_OLD_DEPENDENCY_JAR = "relative_old_dependency_jar"
private const val KEY_OLD_DEPENDENCY_RES = "relative_old_dependency_res"

// name extension

val ChangedFile.dependencyName: String
    get() = extraInfo[KEY_DEPENDENCY_NAME] as String

val ChangedFile.jarDexFileName: String
    get() = dependencyNameToDexFileName(file, dependencyName)

val CompileFile.isDependency: Boolean
    get() = extraInfo.containsKey(KEY_DEPENDENCY_NAME)

val CompileFile.dependencyName: String
    get() = extraInfo[KEY_DEPENDENCY_NAME] as? String ?: "unknown_dependency"

val CompileFile.jarDexFileName: String
    get() = dependencyNameToDexFileName(file, dependencyName)

// e.g. Gradle: reactive-streams-1.0.3.jar
fun ChangedFile.withDependencyName(name: String): ChangedFile {
    return copy(extraInfo = extraInfo + (KEY_DEPENDENCY_NAME to name))
}

fun CompileFile.withDependencyName(name: String): CompileFile {
    return copy(extraInfo = extraInfo + (KEY_DEPENDENCY_NAME to name))
}

// relative manifest extension

val ChangedFile.oldManifest: File?
    get() = extraInfo[KEY_OLD_DEPENDENCY_MANIFEST] as? File

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

val ChangedFile.oldJar: File?
    get() = extraInfo[KEY_OLD_DEPENDENCY_JAR] as? File

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

val ChangedFile.oldRes: File?
    get() = extraInfo[KEY_OLD_DEPENDENCY_RES] as? File

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
// e.g. libs/micro_annotation.jar in org.reactivestreams:reactive-streams:1.0.3 -> #org.reactivestreams#reactive-streams#libs#micro_annotation.dex
private fun dependencyNameToDexFileName(file: File, libraryName: String): String {
    val baseName = try {
        if (libraryName.contains(":")) {
            // e.g. org.reactivestreams:reactive-streams:1.0.3 -> #org.reactivestreams#reactive-streams.dex
            val (group, name, _) = libraryName.split(":")
            "#$group#$name"
        } else {
            // e.g. ./app/libs/library2.v2.jar -> #app#libs#library2#library2.v2.dex
            libraryName
                .replace("./", "#")
                .replace("/", "#")
                .replace(".\\", "#")
                .replace("\\", "#")
                .replace(".jar", "")
        }
    } catch (e: Exception) {
        libraryName
            .replace("./", "#")
            .replace("/", "#")
            .replace(".\\", "#")
            .replace("\\", "#")
            .replace(".jar", "")
    }

    var subName = ""
    if (file.parentFile.name != "jars" || file.name != "classes.jar") {
        // it's not the main classes.jar, use unique name
        subName = "#${file.parentFile.name}#${file.nameWithoutExtension}"
    }

    return "$baseName$subName.dex"
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
                    CompileFile.Type.ClasspathResource -> "classpath-resource"
                    CompileFile.Type.Resource -> "resource"
                    CompileFile.Type.ComposeResource -> "compose-resource"
                    CompileFile.Type.Flat -> "flat"
                    CompileFile.Type.BuildFile -> "gradle"
                    CompileFile.Type.AndroidManifest -> "manifest"
                    CompileFile.Type.DexToChangePackageName -> "dex"
                    CompileFile.Type.NativeLib -> "lib"
                    CompileFile.Type.Dex -> "dex"
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

fun CompileResult.toVisibleErrorMessage(maxErrorCount: Int = 5): String {
    return compiledFailedFiles
        .flatMap { detail ->
            val compileError = detail.getFailure()
            compileError.errors.map { error ->
                val line = error.first
                val lineText = if (line > 0) ":$line" else ""
                "${compileError.file.file.name}$lineText: ${error.second}"
            }
        }
        .distinct()
        .take(maxErrorCount)
        .joinToString("\n")
}

fun ICompileContext.subContext(subTempCompileDirName: String): ICompileContext {
    val origin = this
    return object : ICompileContext by origin {
        override val tempCompileDir: File
            get() = File(origin.tempCompileDir, subTempCompileDirName)
    }
}


fun CompileOutput.toCompileFile(defaultModule: ModuleInfo): CompileFile? {
    val type = type.toCompileFileType() ?: return null
    return CompileFile(type, file, baseDir, relativeModule ?: defaultModule)
}

fun CompileFile.toCompileOutput(): CompileOutput? {
    return CompileOutput(type.toCompileOutputType() ?: return null, file, baseDir, null, module)
}

fun CompileOutput.Type.toCompileFileType(): CompileFile.Type? {
    return when (this) {
        CompileOutput.Type.Class -> CompileFile.Type.Class
        CompileOutput.Type.Flat -> CompileFile.Type.Flat
        CompileOutput.Type.Dex -> CompileFile.Type.Dex
        CompileOutput.Type.Res -> CompileFile.Type.Resource
        CompileOutput.Type.Asset -> CompileFile.Type.Asset
        CompileOutput.Type.NativeLib -> CompileFile.Type.NativeLib
        CompileOutput.Type.Java -> CompileFile.Type.Java
        CompileOutput.Type.Kotlin -> CompileFile.Type.Kotlin
        else -> null
    }
}

fun CompileFile.Type.toCompileOutputType(): CompileOutput.Type? {
    return when (this) {
        CompileFile.Type.Class -> CompileOutput.Type.Class
        CompileFile.Type.Flat -> CompileOutput.Type.Flat
        CompileFile.Type.Dex -> CompileOutput.Type.Dex
        CompileFile.Type.Resource -> CompileOutput.Type.Res
        CompileFile.Type.Asset -> CompileOutput.Type.Asset
        CompileFile.Type.ClasspathResource -> CompileOutput.Type.Asset
        CompileFile.Type.NativeLib -> CompileOutput.Type.NativeLib
        CompileFile.Type.Java -> CompileOutput.Type.Java
        CompileFile.Type.Kotlin -> CompileOutput.Type.Kotlin
        else -> null
    }
}

fun CompileResult.failedAll(task: CompileTask, message: String): CompileResult {
    // mark all failed
    val successDetails = details.filter { it.isSuccess }
    val failedDetails = details.filter { !it.isSuccess }
    val failedDexDetails = successDetails.map {
        Result.failure<CompileFile, CompileError>(CompileError(it.file, listOf(-1L to message)))
    }
    return CompileResult(task, failedDexDetails + failedDetails, emptyList())
}

fun CompileTask.wrapToResult(): CompileResult {
    val compileTask = this
    return CompileResult(compileTask,
        details = compileTask.files.map {
            Result.success(it)
        },
        outputs = compileTask.files.mapNotNull {
            it.toCompileOutput()
        })
}
