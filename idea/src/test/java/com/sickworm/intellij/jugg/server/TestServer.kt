package com.sickworm.intellij.jugg.server

import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.manager.MockJugg
import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import com.sun.nio.file.SensitivityWatchEventModifier
import java.io.File
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.attribute.BasicFileAttributes
import kotlin.system.measureTimeMillis

val serverLogger = ServerLogger()

fun main() {
    TestServer().run()
}

/**
 * Run Jugg directly on JVM
 */
class TestServer {

    fun run() {
        val jugg = MockJugg()
        jugg.resetAllState()
        jugg.waitingLaunchAppAndCheck()
        JuggSettings.deployOnSave = true

        Thread {
            FileChangeServer().run { files ->
                serverLogger.trace("onFileChange $files")
                jugg.notifyFileChanges(files)
            }
        }.start()

        InputServer().run {
            serverLogger.info("onInput")
            println("??? ${JuggSettings.deployOnSave}")
        }
    }
}

class FileChangeServer {

    fun run(onFileChange: (List<File>) -> Unit) {
        serverLogger.debug("start init file listening")

        val watchService: WatchService
        val costTime = measureTimeMillis {
            watchService = FileSystems.getDefault().newWatchService()
            val rootDir = assetsAndroidDir.toPath()
            Files.walkFileTree(rootDir, object: SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val fileName = dir.fileName.toString()
                    if (fileName == "build") {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (fileName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    dir.register(watchService,
                        arrayOf<WatchEvent.Kind<*>>(ENTRY_CREATE, ENTRY_MODIFY),
                        SensitivityWatchEventModifier.HIGH)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        serverLogger.debug("init file listening finished, cost $costTime")

        serverLogger.debug("start listen")
        while(!Thread.currentThread().isInterrupted) {
            try {
                serverLogger.trace("waiting for file changed...")
                val key = watchService.take()
                val files = key.pollEvents().map { event ->
                    @Suppress("UNCHECKED_CAST")
                    val ev: WatchEvent<Path> = event as WatchEvent<Path>
                    val path = ev.context()
                    val dir = key.watchable() as Path
                    val fullPath: Path = dir.resolve(path)
                    return@map fullPath.toFile()
                }
                onFileChange(files)
                key.reset()
            } catch (e: Exception) {
                serverLogger.error(e.message)
            }
        }
    }
}

class InputServer {
    fun run(onInput: () -> Unit) {
        while(!Thread.currentThread().isInterrupted) {
            val char = System.`in`.read()
            if (char == '\n'.toInt()) {
                onInput()
            }
        }
    }
}