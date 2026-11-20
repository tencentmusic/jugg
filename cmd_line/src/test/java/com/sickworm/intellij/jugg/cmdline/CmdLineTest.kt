package com.sickworm.intellij.jugg.cmdline

import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CmdLineTest {

    private val demoProjectDir = File("../android_demo_project").absoluteFile.normalize()

    // The demo project sets `layout.buildDirectory` to `<project root>/build/<module name>`,
    // so the Gradle produced APK lives under build/app instead of the default app/build.
    private val gradleOutputApkPath = ModuleBuildPathInfo(
        demoProjectDir,
        File(demoProjectDir, "app"),
        "debug",
        buildDirRelativePath = "build/app",
    ).buildDir.relativeTo(demoProjectDir).path.replace('\\', '/') + "/outputs/apk/debug/*.apk"

    @Test
    fun buildBase() {
        val cmd = SimpleSshCommand("rm -rf ../android_demo_project/build/jugg")
        val resultInt = CmdExecutor(StdLogger("JuggTest")).invoke(cmd)
        assertEquals(0, resultInt)

        File(Global.buildOutputDir, "backups").deleteRecursively()
        val outputDir = File("${Global.buildOutputDir}/outputs")
        val args = arrayOf(
            "cmd=${CmdLine.Command.BUILD_GRADLE_BASE.value}",
            "baseBuildProjectDir=../android_demo_project",
            "gradleCompileTask=assembleDebug",
            "gradleOutputApkPath=$gradleOutputApkPath",
            "logLevel=debug",
            "outputApkDir=$outputDir",
        )
        val result = CmdLine().run(args)
        assertTrue(result)

        val apks = outputDir.listFiles { file -> file.name.endsWith(".apk") } ?: emptyArray()
        assertEquals(1, apks.size)
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
            verify = { apk -> assertEquals(0, runBuildTool("apksigner", "verify", apk.absolutePath)) },
        )
    }

    @Test
    fun buildIncrementalApkWithoutSigning() {
        doBuildIncrementalApk(
            modify = { listOf(File(demoProjectDir, "app/src/main/java/com/example/myapplication/MainActivity.kt")) },
            revert = {},
            extraArgs = { arrayOf("skipSign=true") },
            prepareBaseline = { baseline ->
                val paths = JuggPathManager(demoProjectDir, baseline)
                val serializer = ProjectInfoSerializer(paths.gradleProjectInfoFile, StdLogger("JuggTest"))
                val projectInfo = checkNotNull(serializer.load())
                val app = projectInfo.modules.getValue("app")
                val signingConfigs = checkNotNull(app.signingConfigs).map {
                    it.copy(keystore = File(baseline, "missing.keystore"))
                }
                serializer.save(projectInfo.copy(modules = projectInfo.modules + ("app" to app.copy(signingConfigs = signingConfigs))))
            },
            verify = { apk ->
                ZipFile(apk).use { zip ->
                    assertTrue(zip.entries().asSequence().any { it.name.startsWith("assets/jugg_/") && it.name.endsWith(".dex") })
                }
                assertEquals(0, runBuildTool("zipalign", "-c", "4", apk.absolutePath))
                assertEquals(1, runBuildTool("apksigner", "verify", apk.absolutePath))
                val keystore = File(apk.parentFile, "test-resign.p12")
                val keytool = File(System.getProperty("java.home"), "bin/keytool")
                val keygen = ProcessBuilder(
                    keytool.absolutePath, "-genkeypair", "-keystore", keystore.absolutePath,
                    "-storepass", "test-only", "-alias", "ci-test", "-keyalg", "RSA", "-dname", "CN=CI Test",
                ).inheritIO().start()
                assertEquals(0, keygen.waitFor())
                assertEquals(0, runBuildTool("apksigner", "sign", "--ks", keystore.absolutePath,
                    "--ks-pass", "pass:test-only", apk.absolutePath))
                assertEquals(0, runBuildTool("apksigner", "verify", apk.absolutePath))
            },
        )
    }

    private fun runBuildTool(tool: String, vararg args: String): Int {
        val buildTools = File(checkNotNull(System.getenv("ANDROID_HOME")), "build-tools/36.0.0")
        return ProcessBuilder(listOf(File(buildTools, tool).absolutePath) + args).inheritIO().start().waitFor()
    }

    private fun doBuildIncrementalApk(
        modify: () -> List<File>,
        revert: () -> Unit,
        extraArgs: (() -> Array<String>)? = null,
        prepareBaseline: (File) -> Unit = {},
        verify: (File) -> Unit = {},
    ) {
        buildBase()

        // backup juggRootDir and delete origin for test
        val baseBuildJuggRootDir = File(Global.projectRootDir, "build/jugg")
        val backupBaseBuildJuggRootDir = Files.createTempDirectory("jugg-cmdline-backup").toFile()
        try {
            baseBuildJuggRootDir.copyRecursively(backupBaseBuildJuggRootDir)
            baseBuildJuggRootDir.deleteRecursively()
            prepareBaseline(backupBaseBuildJuggRootDir)

            val changedFiles = modify.invoke()
            val outputDir = File(backupBaseBuildJuggRootDir, "test-outputs")

            var args = arrayOf(
                "cmd=${CmdLine.Command.BUILD_INCREMENTAL_APK.value}",
                "baseBuildJuggRootDir=$backupBaseBuildJuggRootDir",
                "sourceProjectDir=${Global.projectRootDir}",
                "outputApkDir=$outputDir",
                "logLevel=debug",
                "changedFiles=${changedFiles.joinToString(":")}",
            )
            extraArgs?.let {
                args += it()
            }
            assertTrue(CmdLine().run(args))
            verify(checkNotNull(outputDir.listFiles { file -> file.extension == "apk" }).single())
        } finally {
            revert.invoke()
            backupBaseBuildJuggRootDir.deleteRecursively()
        }
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
