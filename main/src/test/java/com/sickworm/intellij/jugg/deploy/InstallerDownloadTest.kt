package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.mock.MockAndroidProfilerDownloader
import org.junit.Test
import kotlin.test.assertTrue

class InstallerDownloadTest {

    @Test
    fun test() {
        val downloader = MockAndroidProfilerDownloader()
        val result = downloader.makeSureComponentIsInPlace()
        assertTrue(result)
        assertTrue(downloader.installerFilePath.exists())
    }
}