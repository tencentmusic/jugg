package com.sickworm.intellij.jugg.cmdline

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CmdLineTest {

    @Test
    fun buildBase() {
        val args = arrayOf(
            "cmd=${CmdLine.Command.BUILD_GRADLE_BASE.value}",
            "baseBuildProjectDir=../idea/src/test/assets/android/MyApplicationIntellij",
            "gradleCompileTask=assembleDebug",
            "gradleOutputApkPath=app/build/outputs/apk/debug/*.apk",
            "outputApkDir=${Global.buildOutputDir}/outputs",
        )
        val result = CmdLine().run(args)
        assertTrue(result)

        val apks = Global.buildOutputDir.listFiles { file -> file.name.endsWith(".apk") } ?: emptyArray()
        assertEquals(1, apks.size)
    }

    @Test
    fun buildIncrementalApk() {
        buildBase()

        // backup juggRootDir and delete origin for test
        val baseBuildJuggRootDir = File(Global.projectRootDir, "build/jugg")
        val backupBaseBuildJuggRootDir = File(Global.buildOutputDir, "backups")
        backupBaseBuildJuggRootDir.deleteRecursively()
        backupBaseBuildJuggRootDir.mkdirs()
        baseBuildJuggRootDir.copyRecursively(backupBaseBuildJuggRootDir)
        baseBuildJuggRootDir.deleteRecursively()

        val args = arrayOf(
            "cmd=${CmdLine.Command.BUILD_INCREMENTAL_APK.value}",
            "baseBuildJuggRootDir=$backupBaseBuildJuggRootDir",
            "sourceProjectDir=${Global.projectRootDir}",
            "outputApkDir=${Global.buildOutputDir}/outputs",
            "changedFiles=../idea/src/test/assets/android/MyApplicationIntellij/app/src/main/java/com/example/myapplication/MainActivity.kt:../idea/src/test/assets/android/MyApplicationIntellij/app/src/main/res/layout/activity_main.xml",
        )
        val result = CmdLine().run(args)
        assertTrue(result)
    }
}