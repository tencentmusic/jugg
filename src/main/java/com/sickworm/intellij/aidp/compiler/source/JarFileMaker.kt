package com.sickworm.intellij.aidp.compiler.source

import java.io.*
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest


class JarFileMaker {

    @Throws(IOException::class)
    fun jar(classDir: File, outputFile: File, classFile: File = classDir, isNeedManifest: Boolean = false) {
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val target = if (isNeedManifest) {
            val manifest = Manifest()
            manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            JarOutputStream(FileOutputStream(outputFile), manifest)
        } else {
            JarOutputStream(FileOutputStream(outputFile))
        }
        add(classDir, classFile, target)
        target.close()
    }

    private fun add(baseDir: File, source: File, target: JarOutputStream) {
        var ins: BufferedInputStream? = null
        try {
            if (source.isDirectory) {
                var name: String = source.path.replace("\\", "/")
                name = name.substring(baseDir.absolutePath.length)
                if (name.isNotEmpty()) {
                    if (!name.endsWith("/")) name += "/"
                    val entry = JarEntry(name.substring(1))
                    entry.time = source.lastModified()
                    target.putNextEntry(entry)
                    target.closeEntry()
                }
                for (nestedFile in source.listFiles()) add(baseDir, nestedFile, target)
                return
            }
            var name: String = source.path.replace("\\", "/")
            name = name.substring(baseDir.absolutePath.length + 1)
            val entry = JarEntry(name)
            entry.time = source.lastModified()
            target.putNextEntry(entry)
            ins = BufferedInputStream(FileInputStream(source))
            val buffer = ByteArray(1024)
            while (true) {
                val count = ins.read(buffer)
                if (count == -1) break
                target.write(buffer, 0, count)
            }
            target.closeEntry()
        } finally {
            ins?.close()
        }
    }
}