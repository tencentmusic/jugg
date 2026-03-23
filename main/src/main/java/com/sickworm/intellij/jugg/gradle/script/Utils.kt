package com.sickworm.intellij.jugg.gradle.script

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
