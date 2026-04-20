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
import kotlin.test.assertEquals
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
        generator.mappingFile = releaseContext.mappingFile
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
            "Lcom/sickworm/jugg/demo/testcase/minify/KeepAnnotated;",
            "Lcom/sickworm/jugg/demo/testcase/minify/KeepClassName;",
            "Lcom/sickworm/jugg/demo/testcase/minify/NativeMethodClass;",
        )
        testMinifyRemove("MinifyTestActivity", removedOrPartiallyRemovedClasses)
    }

    @Test
    fun testMinifyRemoveKeepClassName() {
        val removedOrPartiallyRemovedClasses = listOf(
            "Lkotlin/jvm/internal/Intrinsics;",
            "Lcom/sickworm/jugg/demo/testcase/minify/MinifyTestActivity;",
        )
        testMinifyRemove("KeepClassName", removedOrPartiallyRemovedClasses)
    }

    @Test
    fun testMinifyInlineEffects() {
        val removedOrPartiallyRemovedClasses = listOf(
            "Lcom/sickworm/jugg/demo/testcase/minify/MinifyTestActivity;",
        )
        testMinifyRemove("MinifyTestEnum", removedOrPartiallyRemovedClasses)
    }

    @Test
    fun testMinifyInlineFieldEffects() {
        // no way to detect field inline. mapping and dex in apk don't have information
        val removedOrPartiallyRemovedClasses = emptyList<String>()
        testMinifyRemove("FullyObfuscated", removedOrPartiallyRemovedClasses)
    }

    private fun testMinifyRemove(testClassName: String, removedOrPartiallyRemovedOrEffectsClasses: List<String>) {
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

        // Build deploy data with minify removed class check enabled
        val deployData = generator.buildDeployData(changedDex, isNeedCheckRecompileMinifyRemovedClass = true)

        logger.debug("effected classes: ${deployData.effectedClassNodes.map { it.className }}")

        removedOrPartiallyRemovedOrEffectsClasses.forEach { className ->
            val result = deployData.effectedClassNodes.find {
                it.className == className
            }
            assertTrue(result != null, "$className should be detected")

            val isEffectedByMinifyTestActivity = result.effectedByClasses.any { effectedByClassName ->
                effectedByClassName.contains(testClassName)
            }
            assertTrue(isEffectedByMinifyTestActivity, "$className should be effected by MinifyTestActivity (which references it). ")
        }
    }

    /**
     * Verify that classes detected by checkMaybeMinifiedRemoveClass are tagged with
     * MINIFY_MEMBER_REMOVED (not SOURCE or INLINE_IMPL_CHANGE).
     *
     * MinifyTestActivity references many classes whose members are removed by R8.
     * These should all be MINIFY_MEMBER_REMOVED so they go through the _jugg_fix
     * bytecode-patching path rather than the source recompilation path.
     */
    @Test
    fun testMinifyRemovedClassNodesHaveMinifyMemberRemovedType() {
        val sourceCompiler = SourceCompiler(releaseContext, mockParentDisposable)

        val compileTask = CompileTask(
            files = listOf(
                CompileFile(
                    type = CompileFile.Type.Kotlin,
                    file = File(TestGlobal.assetsAndroidDir, "app/src/main/java/com/sickworm/jugg/demo/testcase/minify/MinifyTestActivity.kt"),
                    baseDir = File(TestGlobal.assetsAndroidDir, "app/src/main/java/"),
                    module = releaseContext.applicationModule,
                )
            ),
            outputDir = stagingDir,
        )

        val compileResult = sourceCompiler.compile(compileTask)
        compileResult.printCompileErrors()
        assertTrue(compileResult.isAllSuccess, "Compilation should succeed")

        val deployItems = compileResult.outputs.map { it.toDeployItem() }
        val changedDex = deployItems.filter { it.type == com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex }

        // Build with minify check but without compiling effected sources
        // (simulates the getMinifyInfo path where we only need bytecode patching)
        val deployData = generator.buildDeployData(
            changedDex,
            isNeedCheckRecompileMinifyRemovedClass = true,
            isCompilingEffectedSourceFiles = false,
        )

        // These classes are detected by checkMaybeMinifiedRemoveClass (removed or partially removed by R8)
        val minifyDetectedClassNames = listOf(
            "Lcom/sickworm/jugg/demo/testcase/minify/FullyObfuscated;",
            "Lcom/sickworm/jugg/demo/testcase/minify/InnerClassHolder\$InnerClass;",
            "Lcom/sickworm/jugg/demo/testcase/minify/InnerClassHolder;",
            "Lcom/sickworm/jugg/demo/testcase/minify/InterfaceImplementor;",
            "Lcom/sickworm/jugg/demo/testcase/minify/MinifyTestEnum;",
            "Lcom/sickworm/jugg/demo/testcase/minify/InnerClassHolder\$StaticInnerClass;",
        )

        minifyDetectedClassNames.forEach { className ->
            val node = deployData.effectedClassNodes.find { it.className == className }
            assertTrue(node != null, "$className should be detected as effected")
            assertEquals(
                EffectedClassNode.EffectedType.MINIFY_MEMBER_REMOVED,
                node.effectedType,
                "$className should have MINIFY_MEMBER_REMOVED type (not ${node.effectedType}), " +
                    "because it was removed/partially-removed by R8 minification"
            )
        }
    }
}

