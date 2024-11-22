package com.sickworm.intellij.jugg.logger

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.logging.ErrorManager
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.StreamHandler
import java.util.logging.XMLFormatter


/**
 * Reference from: java.util.logging.FileHandler and avoid lock on Windows.
 * Lock will let file failed to delete by gradle clean and failed in Windows.
 */
class NoLockFileHandler(
    private val pattern: String,
    private val count: Int,
    private val append: Boolean,
) : StreamHandler() {

    private lateinit var files: Array<File>

    init {
        configure()
        openFiles()
    }

    private fun configure() {
        level = Level.ALL
        formatter = XMLFormatter()
    }

    @Throws(IOException::class)
    private fun openFiles() {
        files = (0 until count).map { i ->
            this.generate(pattern, i, 0)
        }.toTypedArray()
        if (append) {
            open(files[0], true)
        } else {
            rotate()
        }
        errorManager = ErrorManager()
    }

    @Throws(IOException::class)
    private fun open(file: File, append: Boolean) {
        val outputStream = if (append) {
            Files.newOutputStream(file.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
            )
        } else {
            Files.newOutputStream(file.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            )
        }
        setOutputStream(BufferedOutputStream(outputStream))
    }

    @Suppress("SameParameterValue")
    private fun generate(pattern: String, generation: Int, unique: Int): File {
        return generate(pattern, count, generation, unique)
    }

    @Synchronized
    private fun rotate() {
        val oldLevel = this.level
        this.level = Level.OFF
        super.close()
        for (i in count - 2 downTo 0) {
            val f1 = files[i]
            val f2 = files[i + 1]
            if (f1.exists()) {
                if (f2.exists()) {
                    f2.delete()
                }
                f1.renameTo(f2)
            }
        }
        try {
            open(files[0], false)
        } catch (e: IOException) {
            reportError(null as String?, e, 4)
        }
        this.level = oldLevel
    }

    @Synchronized
    override fun publish(record: LogRecord) {
        if (isLoggable(record)) {
            super.publish(record)
            flush()
        }
    }

    @Synchronized
    @Throws(SecurityException::class)
    override fun close() {
        super.close()
    }

    companion object {
        fun generate(pat: String, count: Int, generation: Int, unique: Int): File {
            val path = Paths.get(pat)
            var result: Path? = null
            var sawg = false
            var sawu = false
            val word = StringBuilder()
            var prev: Path? = null
            var p: Path
            val iterator: Iterator<Path> = path.iterator()
            end@ while (iterator.hasNext()) {
                p = iterator.next()
                if (prev != null) {
                    prev = prev.resolveSibling(word.toString())
                    result = if (result == null) prev else result.resolve(prev!!)
                }
                val pattern = p.toString()
                var ix = 0
                word.setLength(0)
                while (true) {
                    if (ix >= pattern.length) {
                        prev = p
                        continue@end
                    }
                    val ch = pattern[ix]
                    ++ix
                    var ch2 = 0.toChar()
                    if (ix < pattern.length) {
                        ch2 = pattern[ix].lowercaseChar()
                    }
                    if (ch == '%') {
                        if (ch2 == 't') {
                            var tmpDir = System.getProperty("java.io.tmpdir")
                            if (tmpDir == null) {
                                tmpDir = System.getProperty("user.home")
                            }
                            result = Paths.get(tmpDir)
                            ++ix
                            word.setLength(0)
                            continue
                        }
                        if (ch2 == 'h') {
                            result = Paths.get(System.getProperty("user.home"))
                            ++ix
                            word.setLength(0)
                            continue
                        }
                        if (ch2 == 'g') {
                            word.append(generation)
                            sawg = true
                            ++ix
                            continue
                        }
                        if (ch2 == 'u') {
                            word.append(unique)
                            sawu = true
                            ++ix
                            continue
                        }
                        if (ch2 == '%') {
                            word.append('%')
                            ++ix
                            continue
                        }
                    }
                    word.append(ch)
                }
            }
            if (count > 1 && !sawg) {
                word.append('.').append(generation)
            }
            if (unique > 0 && !sawu) {
                word.append('.').append(unique)
            }
            if (word.isNotEmpty()) {
                val n = word.toString()
                p = if (prev == null) Paths.get(n) else prev.resolveSibling(n)
                result = if (result == null) p else result.resolve(p)
            } else if (result == null) {
                result = Paths.get("")
            }
            return if (path.root == null) {
                result!!.toFile()
            } else {
                path.root.resolve(result!!).toFile()
            }
        }
    }
}