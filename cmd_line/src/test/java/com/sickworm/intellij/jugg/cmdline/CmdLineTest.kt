package com.sickworm.intellij.jugg.cmdline

import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CmdLineTest {

    @Test
    fun buildBase() {
        val cmd = SimpleSshCommand("rm -rf ../android_demo_project/build/jugg")
        val resultInt = CmdExecutor(StdLogger("JuggTest")).invoke(cmd)
        assertEquals(0, resultInt)

        val outputDir = File("${Global.buildOutputDir}/outputs")
        val args = arrayOf(
            "cmd=${CmdLine.Command.BUILD_GRADLE_BASE.value}",
            "baseBuildProjectDir=../android_demo_project",
            "gradleCompileTask=assembleDebug",
            "gradleOutputApkPath=app/build/outputs/apk/debug/*.apk",
            "logLevel=debug",
            "outputApkDir=$outputDir",
        )
        val result = CmdLine().run(args)
        assertTrue(result)

        val apks = outputDir.listFiles { file -> file.name.endsWith(".apk") } ?: emptyArray()
        assertEquals(1, apks.size)
        val settingsFile = JuggGlobalPathManager.settingsFile
        assertFalse(settingsFile.exists() && settingsFile.readText().contains("\"isEnableBackupClasspath\""))
    }

    @Test
    fun buildIncrementalApk() {
        doBuildIncrementalApk(
            modify = {
                return@doBuildIncrementalApk listOf(
                    "../android_demo_project/app/src/main/java/com/example/myapplication/MainActivity.kt",
                    "../android_demo_project/app/src/main/res/layout/activity_main.xml",
                ).map(::File)
            },
            revert = {
            },
        )
    }

    private fun doBuildIncrementalApk(modify: (() -> List<File>), revert: (() -> Unit), extraArgs: (() -> Array<String>)? = null) {
        buildBase()

        // backup juggRootDir and delete origin for test
        val baseBuildJuggRootDir = File(Global.projectRootDir, "build/jugg")
        val backupBaseBuildJuggRootDir = File(Global.buildOutputDir, "backups")
        backupBaseBuildJuggRootDir.deleteRecursively()
        backupBaseBuildJuggRootDir.mkdirs()
        baseBuildJuggRootDir.copyRecursively(backupBaseBuildJuggRootDir)
        baseBuildJuggRootDir.deleteRecursively()

        val changedFiles = modify.invoke()

        var args = arrayOf(
            "cmd=${CmdLine.Command.BUILD_INCREMENTAL_APK.value}",
            "baseBuildJuggRootDir=$backupBaseBuildJuggRootDir",
            "sourceProjectDir=${Global.projectRootDir}",
            "outputApkDir=${Global.buildOutputDir}/outputs",
            "logLevel=debug",
            "changedFiles=${changedFiles.joinToString(":")}",
        )
        extraArgs?.let {
            args += it()
        }
        val result = CmdLine().run(args)

        revert.invoke()

        assertTrue(result)
    }


    @Test
    fun buildIncrementalApkEffects() {
        val classFile = File("../android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/subclass/RootClass.java")
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

    @Test
    fun buildIncrementalApkWithCustomCompilers() {
        doBuildIncrementalApk(
            modify = {
                return@doBuildIncrementalApk listOf(
                    "../android_demo_project/app/src/main/java/com/example/myapplication/MainActivity.kt",
                    "../android_demo_project/app/src/main/res/layout/activity_main.xml",
                ).map(::File)
            },
            revert = {
            },
            extraArgs = {
                arrayOf(
                    "customCompilerJars=src/demo/custom_compilers/custom_compiler_instrument-1.0.jar:src/demo/custom_compilers/dependency.jar"
                )
            }
        )
    }

    @Test
    fun buildIncrementalApkManifest() {
        val manifestFile = File("../android_demo_project/app/src/main/AndroidManifest.xml")
        val originCode = manifestFile.readText()
        doBuildIncrementalApk(
            modify = {
                manifestFile.writeText(manifestFile.readText().replace(
                    """<meta-data android:name="com.test.metadata" android:value="0" />""",
                    """<meta-data android:name="com.test.metadata" android:value="100" />
                        |<meta-data android:name="com.test.metadata2" android:value="200" />""".trimMargin(),
                ))
                return@doBuildIncrementalApk listOf(
                    manifestFile.path,
                ).map(::File)
            },
            revert = {
                manifestFile.writeText(originCode)
            }
        )
    }
}
