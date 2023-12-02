package com.sickworm.intellij.jugg.manager.utils

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.absolutePathString

object ListFiles {

    fun listFileOrderedByNameLastChar(rootDir: File): List<File> {
        val fileList = mutableListOf<File>()
        Files.walkFileTree(rootDir.toPath(), object : SimpleFileVisitor<Path>() {

            private val fileListPerDir = ListNode(File(""))

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val fileName = dir.fileName.toString()
                if (fileName == "build") {
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (fileName == "buildSrc") {
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

                if (fileName.endsWith(".java") || fileName.endsWith(".kt")) {
                    fileListPerDir.orderAdd(file.toFile())
                }

                if (file.absolutePathString().contains("/res/") && fileName.endsWith(".xml")) {
                    fileListPerDir.orderAdd(file.toFile())
                }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path?, exc: IOException?): FileVisitResult {
                var currentNode: ListNode<File>? = fileListPerDir.next
                while (currentNode != null) {
                    fileList.add(currentNode.value)
                    currentNode = currentNode.next
                }
                fileListPerDir.next = null

                return FileVisitResult.CONTINUE
            }

            private fun ListNode<File>.orderAdd(file: File) {
                var insertNode = this

                val fileCompileOrder = file.nameWithoutExtension.last()
                while (insertNode.next != null) {
                    val next = insertNode.next!!
                    val currentOrder = next.value.nameWithoutExtension.last()
                    if (fileCompileOrder < currentOrder) {
                        break
                    }
                    insertNode = next
                }

                val next = insertNode.next
                val node = ListNode(file)
                insertNode.next = node
                node.next = next
            }
        })
        return fileList
    }
}

private class ListNode<T>(
    val value: T
) {
    var next: ListNode<T>? = null
}