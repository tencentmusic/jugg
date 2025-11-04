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
}