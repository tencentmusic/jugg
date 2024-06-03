package com.sickworm.intellij.jugg.gradle.script

import java.io.ByteArrayOutputStream
import java.io.PrintStream

fun printException(e: Throwable) {
    val outputStream = ByteArrayOutputStream()
    e.printStackTrace(PrintStream(outputStream))
    val result = outputStream.toString()
    val lines = result.split('\n')
    lines.forEach { line ->
        if (line.contains(".gradle.kts:")) {
            println(line)
        }
    }
}