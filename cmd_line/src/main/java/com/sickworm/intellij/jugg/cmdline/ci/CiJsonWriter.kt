package com.sickworm.intellij.jugg.cmdline.ci
import java.io.File
object CiJsonWriter { fun write(output: File, json: String) { output.parentFile?.mkdirs(); val temp=File(output.parentFile ?: output.absoluteFile.parentFile, ".${output.name}.tmp"); temp.writeText(json); check(temp.renameTo(output)) { "Unable to write output: ${output.absolutePath}" } } }
