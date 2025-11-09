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
        doBuildIncrementalApk(
            modify = {
                return@doBuildIncrementalApk listOf(
                    "../idea/src/test/assets/android/MyApplicationIntellij/app/src/main/java/com/example/myapplication/MainActivity.kt",
                    "../idea/src/test/assets/android/MyApplicationIntellij/app/src/main/res/layout/activity_main.xml",
                ).map(::File)
            },
            revert = {
            },
        )
    }

    private fun doBuildIncrementalApk(modify: (() -> List<File>), revert: (() -> Unit)) {
        buildBase()

        // backup juggRootDir and delete origin for test
        val baseBuildJuggRootDir = File(Global.projectRootDir, "build/jugg")
        val backupBaseBuildJuggRootDir = File(Global.buildOutputDir, "backups")
        backupBaseBuildJuggRootDir.deleteRecursively()
        backupBaseBuildJuggRootDir.mkdirs()
        baseBuildJuggRootDir.copyRecursively(backupBaseBuildJuggRootDir)
        baseBuildJuggRootDir.deleteRecursively()

        val changedFiles = modify.invoke()

        val args = arrayOf(
            "cmd=${CmdLine.Command.BUILD_INCREMENTAL_APK.value}",
            "baseBuildJuggRootDir=$backupBaseBuildJuggRootDir",
            "sourceProjectDir=${Global.projectRootDir}",
            "outputApkDir=${Global.buildOutputDir}/outputs",
            "logLevel=debug",
            "changedFiles=${changedFiles.joinToString(":")}",
        )
        val result = CmdLine().run(args)

        revert.invoke()

        assertTrue(result)
    }


    @Test
    fun buildIncrementalApkEffects() {
        val classFile = File("../idea/src/test/assets/android/MyApplicationIntellij/app/src/main/java/com/sickworm/jugg/demo/testcase/subclass/RootClass.java")
        val originCode = classFile.readText()

        doBuildIncrementalApk(
            modify = {
                val modifyCode = classFile.readText().replace(
                    "public void func1",
                    "protected void func1"
                )
                classFile.writeText(modifyCode)
                return@doBuildIncrementalApk listOf(classFile)
            },
            revert = {
                classFile.writeText(originCode)
            },
        )
    }
}