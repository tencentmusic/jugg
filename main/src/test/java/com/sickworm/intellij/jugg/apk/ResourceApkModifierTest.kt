package com.sickworm.intellij.jugg.apk

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.util.zip.ZipFile

class ResourceApkModifierTest {

    @Test
    fun `failed resource apk creation keeps the last published apk`() {
        val testRoot = Files.createTempDirectory("resource-apk-modifier-test").toFile()
        val resourceApk = File(testRoot, "resource.ap_")
        val modifier = ResourceApkModifier("base.apk", resourceApk, logger)
        modifier.createResourceApk(listOf(overlay("stable.txt", "stable")))

        try {
            modifier.createResourceApk(
                listOf(
                    overlay("new.txt", "new"),
                    overlay("\u0000invalid", "invalid"),
                ),
            )
            fail("Expected invalid ZIP path to fail")
        } catch (_: InvalidPathException) {
            // Expected.
        }

        assertEquals("stable", readEntry(resourceApk, "stable.txt"))
        modifier.createResourceApk(listOf(overlay("retry.txt", "retry")))
        assertEquals("retry", readEntry(resourceApk, "retry.txt"))
    }

    private fun overlay(name: String, content: String): DeployItem {
        return DeployItem(
            name = name,
            type = CompileOutput.Type.Asset,
            checksum = 0,
            content = content.toByteArray(),
            apkPath = "base.apk",
        )
    }

    private fun readEntry(apk: File, name: String): String {
        ZipFile(apk).use { zipFile ->
            val entry = zipFile.getEntry(name) ?: error("Missing ZIP entry: $name")
            return zipFile.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }
}
