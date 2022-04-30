package com.sickworm.intellij.jugg.git

import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitManagerTest {

    @Test
    fun test() {
        val gitManager = GitManager(assetsAndroidDir)
        gitManager.deleteGit()
        assertFalse(gitManager.hasInit())
        gitManager.init()
        assertTrue(gitManager.hasInit())
        gitManager.deleteGit()
    }
}