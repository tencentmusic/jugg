package com.sickworm.intellij.aidp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.moveTo
import kotlin.test.assertTrue

class Test {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @ExperimentalPathApi
    @Test
    fun tempTest() {
        clearBuild()
        val dir1 = File(buildDir, "1")
        val dir11 = File(dir1, "2")
        dir1.mkdirs()
        val dir2 = File(buildDir, "2")
        val dir22 = File(dir2, "2")
        dir22.mkdirs()
        val f1 = File(dir1, "A")
        val f2 = File(dir1, "B")
        val f3 = File(dir22, "C")
        f1.createNewFile()
        f2.createNewFile()
        f3.createNewFile()

        dir2.listFilesRecursively().forEach {
            val destFile = File(dir1, it.relativeTo(dir2).path)
            destFile.parentFile?.run {
                if (!exists()) {
                    mkdirs()
                }
            }
            if (destFile.exists()) {
                destFile.delete()
            }
            it.renameTo(destFile)
        }

        val f4 = File(dir22, "D")
        dir22.mkdirs()
        f4.createNewFile()
        dir2.listFilesRecursively().forEach {
            val destFile = File(dir1, it.relativeTo(dir2).path)
            destFile.parentFile?.run {
                if (!exists()) {
                    mkdirs()
                }
            }
            if (destFile.exists()) {
                destFile.delete()
            }
            it.renameTo(destFile)
        }

        dir22.mkdirs()
        f4.createNewFile()
        dir2.listFilesRecursively().forEach {
            val destFile = File(dir1, it.relativeTo(dir2).path)
            destFile.parentFile?.run {
                if (!exists()) {
                    mkdirs()
                }
            }
            if (destFile.exists()) {
                destFile.delete()
            }
            assertTrue(it.renameTo(File(dir11, it.name)))
        }
    }
}