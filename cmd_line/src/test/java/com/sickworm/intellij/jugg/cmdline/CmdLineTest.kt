package com.sickworm.intellij.jugg.cmdline

import kotlin.test.Test
import kotlin.test.assertTrue

class CmdLineTest {

    @Test
    fun buildBase() {
        val args = arrayOf(
            "cmd=${CmdLine.Command.BUILD_GRADLE_BASE.value}",
            "baseBuildProjectDir=${Global.projectRootDir}",
            "gradleCompileTask=assembleDebug",
            "outputApkPath=app/build/outputs/apk/debug/*.apk",
        )
        val result = CmdLine().run(args)
        assertTrue(result)
    }

    @Test
    fun buildIncrementalApk() {
        buildBase()

        val args = arrayOf(
            "cmd=${CmdLine.Command.BUILD_INCREMENTAL_APK.value}",
            "baseBuildProjectDir=${Global.projectRootDir}",
            "sourceProjectDir=${Global.projectRootDir}",
            "outputApkDir=${Global.outputDir}",
            "changedFiles=${Global.projectRootDir}/app/src/main/java/com/example/myapplication/MainActivity.kt",
        )
        val result = CmdLine().run(args)
        assertTrue(result)
    }
}