package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import org.junit.Test
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/**
 * scan all the compilable files and check the compilre result is match the result
 */
class CompileConsistencyTest {

    @Test
    fun testConsistency() {
        val jugg = MockJugg()
        jugg.initEnv()
        jugg.resetAllState()

        val fileList = mutableListOf<File>()
        val rootDir = assetsAndroidDir
        Files.walkFileTree(rootDir.toPath(), object: SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val fileName = dir.fileName.toString()
                if (fileName == "build") {
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (fileName.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val fileName = file.fileName.toString()
                if (fileName.startsWith(".")) {
                    return FileVisitResult.CONTINUE
                }
                fileList.add(file.toFile())
                return FileVisitResult.CONTINUE
            }
        })

        println("${fileList.size} files to be check (including not compilable files)")

        for (file in fileList) {
            println("checking ${file.relativeTo(rootDir)}...")
            checkFileCompileConsistency(file)
        }
    }

    private fun checkFileCompileConsistency(file: File) {
        // TODO
    }
}