package com.sickworm.intellij.jugg.mock

import org.jetbrains.android.download.AndroidComponentDownloader
import org.jetbrains.android.download.AndroidProfilerDownloader
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.zip.ZipFile

/**
 * download installer from web
 * @see AndroidProfilerDownloader
 */
class MockAndroidProfilerDownloader: AndroidComponentDownloader() {

    private val basePath = "./build/jugg/test/android-plugin-resources/$version"
    val installerFilePath = File("$basePath/plugins/android/resources/installer")

    override fun makeSureComponentIsInPlace(): Boolean {
        if (installerFilePath.exists()) {
            return true
        }

        val fileName = "$artifactName-$version.$extension"
        val url = "$baseUrl$artifactName/$version/$fileName"
        val localZipFile = File("$basePath.zip")
        if (localZipFile.exists()) {
            localZipFile.delete()
        }

        println("Start download $fileName from $url...")

        return try {
            localZipFile.parentFile?.mkdirs()
            BufferedInputStream(URL(url).openStream()).use { `in` ->
                FileOutputStream(localZipFile).use { fileOutputStream ->
                    val dataBuffer = ByteArray(1024_000)
                    var bytesRead: Int
                    var byteCount = 0
                    while (`in`.read(dataBuffer, 0, 1024_000).also { bytesRead = it } != -1) {
                        fileOutputStream.write(dataBuffer, 0, bytesRead)
                        val oldMb = byteCount / 1024_000
                        byteCount += bytesRead
                        val newMb = byteCount / 1024_000
                        if (oldMb != newMb) {
                            println("Downloaded ${newMb}MB")
                        }
                    }
                }
            }

            ZipFile(localZipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) {
                        return@forEach
                    }
                    zip.getInputStream(entry).use { input ->
                        val file = File("$basePath/${entry.name}")
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    override fun getArtifactName(): String {
        return "android-plugin-resources"
    }

}