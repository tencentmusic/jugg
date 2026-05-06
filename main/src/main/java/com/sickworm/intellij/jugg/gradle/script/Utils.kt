package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.Variant

fun printException(e: Throwable) {
    val stackTrace = e.stackTrace
    stackTrace.forEach {
        if (it.fileName?.contains(".gradle.kts") == true) {
            println("Jugg: $it")
        }
    }
}

/**
 * Utils stores Kotlin-1.4-compatible helper functions used by Gradle scripts.
 */
object Utils {

    // for compat with Kotlin 1.4 in readProjectInfo.gradle.kts
    fun <T, R : Comparable<R>> maxByOrNullForKt14(iteratable: Iterable<T>, selector: (T) -> R): T? {
        val iterator = iteratable.iterator()
        if (!iterator.hasNext()) return null
        var maxElem = iterator.next()
        if (!iterator.hasNext()) return maxElem
        var maxValue = selector(maxElem)
        do {
            val e = iterator.next()
            val v = selector(e)
            if (maxValue < v) {
                maxElem = e
                maxValue = v
            }
        } while (iterator.hasNext())
        return maxElem
    }

    fun uppercaseFirstAsciiCompat(value: String): String {
        if (value.isEmpty()) {
            return value
        }
        val firstChar = value[0]
        val first = if (firstChar in 'a'..'z') {
            firstChar - ('a' - 'A')
        } else {
            firstChar
        }
        return if (value.length == 1) {
            first.toString()
        } else {
            first + value.substring(1)
        }
    }
}

// for compat with Kotlin 1.4 in readProjectInfo.gradle.kts
fun <T, R : Comparable<R>> Iterable<T>.maxByOrNullForKt14(selector: (T) -> R): T? {
    return Utils.maxByOrNullForKt14(this, selector)
}

fun String.uppercaseFirstAsciiCompat(): String {
    return Utils.uppercaseFirstAsciiCompat(this)
}

val String.camelCompat: String
    get() = uppercaseFirstAsciiCompat()

/**
 * Guesses the active Android variant from Gradle task names with the same priority rules used by project info reading and task injection.
 */
fun guessBuildVariant(
    moduleName: String,
    variants: List<Variant>,
    taskNames: Set<String>,
    startTaskNames: List<String>?,
): String? {
    val executedVariants = variants.filter {
        val capitalizedName = it.name.camelCompat
        val manifestTaskName = "process${capitalizedName}Manifest"
        return@filter manifestTaskName in taskNames || taskNames.any { taskName -> taskName.contains(capitalizedName) }
    }

    val isRelease = startTaskNames?.any { it.contains("release", ignoreCase = true) } ?: false
    val priorityVariant = if (isRelease) "release" else "debug"

    if (executedVariants.size == 1) {
        return executedVariants[0].name
    } else if (executedVariants.isEmpty()) {
        val guessVariant = variants.firstOrNull {
            it.name.contains(priorityVariant, ignoreCase = true)
        }
        println("Jugg: $moduleName has no executedVariants, " +
                "variants $variants, startTaskNames: $startTaskNames, guessVariant: $guessVariant")
        return guessVariant?.name
    } else {
        val guessVariant = executedVariants.firstOrNull {
            it.name.contains(priorityVariant, ignoreCase = true)
        } ?: executedVariants.firstOrNull()
        println("Jugg: $moduleName has multiple executedVariants: $executedVariants, " +
                "startTaskNames: $startTaskNames, guessVariant: $guessVariant")
        return guessVariant?.name
    }
}
