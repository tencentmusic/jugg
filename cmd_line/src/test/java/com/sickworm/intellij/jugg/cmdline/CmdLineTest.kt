package com.sickworm.intellij.jugg.cmdline

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
            "outputApkDir=${Global.outputDir}",
        )
        val result = CmdLine().run(args)
        assertTrue(result)

        val apks = Global.outputDir.listFiles { file -> file.name.endsWith(".apk") } ?: emptyArray()
        assertEquals(1, apks.size)
    }

    @Test
    fun buildIncrementalApk() {
        buildBase()

        val args = arrayOf(
            "cmd=${CmdLine.Command.BUILD_INCREMENTAL_APK.value}",
            "baseBuildJuggRootDir=${Global.projectRootDir}/build/jugg",
            "sourceProjectDir=${Global.projectRootDir}",
            "outputApkDir=${Global.outputDir}",
            "changedFiles=../idea/src/test/assets/android/MyApplicationIntellij/app/src/main/java/com/example/myapplication/MainActivity.kt:../idea/src/test/assets/android/MyApplicationIntellij/app/src/main/java/com/example/myapplication/MainActivity2.java",
        )
        val result = CmdLine().run(args)
        assertTrue(result)
    }
}