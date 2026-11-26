package com.sickworm.intellij.jugg.project.change

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

class WatchServiceFileChangeMonitorTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `watch service should report create rename and delete`() {
        val root = temporaryFolder.newFolder("project")
        val sourceDir = File(root, "app/src/main").apply { mkdirs() }
        val changedPaths = CopyOnWriteArraySet<String>()
        val deletedPaths = CopyOnWriteArraySet<String>()
        val monitor = WatchServiceFileChangeMonitor(root, Logger.getInstance("WatchServiceFileChangeMonitorTest"), 50L)
        monitor.startListen(object : FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                changedPaths += changedFiles.map { it.canonicalPath }
                deletedPaths += deletedFiles.map { it.canonicalPath }
            }
        })

        try {
            val original = File(sourceDir, "Original.kt")
            original.writeText("class Original")
            assertTrue(await(3_000) { original.canonicalPath in changedPaths })

            val renamed = File(sourceDir, "Renamed.kt")
            assertTrue(original.renameTo(renamed))
            assertTrue(await(3_000) {
                renamed.canonicalPath in changedPaths && original.canonicalPath in deletedPaths
            })

            assertTrue(renamed.delete())
            assertTrue(await(3_000) { renamed.canonicalPath in deletedPaths })
        } finally {
            monitor.close()
        }
    }

    @Test
    fun `watch service should ignore generated directories`() {
        val root = temporaryFolder.newFolder("project")
        val sourceDir = File(root, "app/src/main").apply { mkdirs() }
        val buildDir = File(root, "app/build/generated").apply { mkdirs() }
        val changedPaths = CopyOnWriteArraySet<String>()
        val monitor = WatchServiceFileChangeMonitor(root, Logger.getInstance("WatchServiceFileChangeMonitorTest"), 50L)
        monitor.startListen(object : FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                changedPaths += changedFiles.map { it.canonicalPath }
            }
        })

        try {
            val generated = File(buildDir, "Generated.kt")
            val source = File(sourceDir, "Source.kt")
            generated.writeText("class Generated")
            source.writeText("class Source")

            assertTrue(await(3_000) { source.canonicalPath in changedPaths })
            Thread.sleep(100L)
            assertFalse(generated.canonicalPath in changedPaths)
        } finally {
            monitor.close()
        }
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20L)
        }
        return condition()
    }
}
