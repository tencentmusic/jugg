package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.deploy.toDeployItem
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class DeployDataGeneratorReleaseTest {

    private lateinit var releaseContext: SimpleCompileContext
    private lateinit var releaseApkFile: File
    private lateinit var generator: DeployDataGenerator

    @Before
    fun assemble() {
        clearBuild()
        GradleBuildHelper.appAssembleRelease()

        releaseApkFile = File(TestGlobal.assetsAndroidDir, "app/build/outputs/apk/release/app-release.apk")
        require(releaseApkFile.exists()) { "Release APK not found: ${releaseApkFile.absolutePath}" }

        releaseContext = TestGlobal.context.copy(
            modules = ProjectInfoSerializer(
                AssembleAndroidProjectOnce.gradleProjectInfoFile,
                logger
            ).load()!!.modules,
            apkInfos = listOf(ApkInfo(releaseApkFile, TestGlobal.packageName))
        )

        generator = DeployDataGenerator(logger, buildDir)
        generator.init(listOf(ApkInfo(releaseApkFile, TestGlobal.packageName)), emptyList())
    }

    @Test
    fun testMinifyRemoveMinifyTestActivity() {
        val removedOrPartiallyRemovedClasses = listOf(
            "Lcom/sickworm/jugg/demo/testcase/minify/FullyObfuscated;",
            "Lcom/sickworm/jugg/demo/testcase/minify/InnerClassHolder\$InnerClass;",
            "Lcom/sickworm/jugg/demo/testcase/minify/InnerClassHolder;",
            "Lcom/sickworm/jugg/demo/testcase/minify/InterfaceImplementor;",
            "Lcom/sickworm/jugg/demo/testcase/minify/MinifyTestEnum;",
            "Lcom/sickworm/jugg/demo/testcase/minify/InnerClassHolder\$StaticInnerClass;",
            "Lb3/a;",
            "Lb3/b;",
            "Lb3/c;",
            "Lb3/d;",
            "Lcom/sickworm/jugg/demo/testcase/minify/KeepAnnotated;",
            "Lcom/sickworm/jugg/demo/testcase/minify/KeepClassName;",
            "Lcom/sickworm/jugg/demo/testcase/minify/NativeMethodClass;",
        )
        testMinifyRemove("MinifyTestActivity", removedOrPartiallyRemovedClasses)
    }

    @Test
    fun testMinifyRemoveKeepClassName() {
        val removedOrPartiallyRemovedClasses = listOf(
            "Ljava/lang/Object;",
            "Ljava/lang/StringBuilder;",
            "Lkotlin/jvm/internal/Intrinsics;",
        )
        testMinifyRemove("KeepClassName", removedOrPartiallyRemovedClasses)
    }

    private fun testMinifyRemove(testClassName: String, removedOrPartiallyRemovedClasses: List<String>) {
        val sourceCompiler = SourceCompiler(releaseContext, mockParentDisposable)

        // Compile MinifyTestActivity which references MinifyTestEnum and other potentially removed classes
        val compileTask = CompileTask(
            files = listOf(
                CompileFile(
                    type = CompileFile.Type.Kotlin,
                    file = File(TestGlobal.assetsAndroidDir, "app/src/main/java/com/sickworm/jugg/demo/testcase/minify/$testClassName.kt"),
                    baseDir = File(TestGlobal.assetsAndroidDir, "app/src/main/java/"),
                    module = releaseContext.applicationModule,
                )
            ),
            outputDir = stagingDir,
        )

        val compileResult = sourceCompiler.compile(compileTask)
        compileResult.printCompileErrors()
        assertTrue(compileResult.isAllSuccess, "Compilation should succeed for $testClassName")

        // Parse the compiled dex to get class information
        val deployItems = compileResult.outputs.map { it.toDeployItem() }
        val changedDex = deployItems.filter { it.type == com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex }
        val parsedDex = ApkParser().parseDex(changedDex, isSkipOfficialClass = false)

        // Build deploy data with minify removed class check enabled
        val deployData = generator.buildDeployData(changedDex, isNeedCheckRecompileMinifyRemovedClass = true)

        logger.debug("effected classes: ${deployData.effectedClassNodes.map { it.className }}")

        removedOrPartiallyRemovedClasses.forEach { className ->
            val result = deployData.effectedClassNodes.classes.find {
                it.className == className
            }
            assertTrue(result != null, "$className should also be detected as removed")

            val isEffectedByMinifyTestActivity = result.effectedByClasses.any { className ->
                className.contains(testClassName)
            }
            assertTrue(isEffectedByMinifyTestActivity, "$className should be effected by MinifyTestActivity (which references it). ")
        }
    }
}

