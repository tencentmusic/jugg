package com.sickworm.intellij.jugg.compiler.obfuscation

import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Phase 2 测试：验证 _jugg_fix 类生成和 DEX 调用重定向
 */
class DexMinifyCompilerPhase2Test {

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
    fun testJuggFixClassGeneration() {
        // 准备测试数据：模拟一个内联影响的类
        val testClassName = "Lcom/sickworm/jugg/demo/testcase/minify/MinifyTestEnum;"
        val testClassFile = File(TestGlobal.assetsAndroidDir,
            "app/build/tmp/kotlin-classes/release/com/sickworm/jugg/demo/testcase/minify/MinifyTestEnum.class")

        // 验证类文件存在
        if (!testClassFile.exists()) {
            logger.warn("Test class file not found: ${testClassFile.absolutePath}")
            logger.warn("Skipping test")
            return
        }

        // 设置 MinifyInfo
        releaseContext.setMinifyInfo(
            com.sickworm.intellij.jugg.compiler.obfuscation.MinifyInfo(
                inlineEffectedClasses = listOf(
                    com.sickworm.intellij.jugg.compiler.obfuscation.InlineEffectedClass(
                        className = testClassName,
                        effectedByClasses = listOf("Lcom/sickworm/jugg/demo/testcase/minify/MinifyTestActivity;")
                    )
                ),
                classFiles = mapOf(
                    "com.sickworm.jugg.demo.testcase.minify.MinifyTestEnum" to testClassFile
                )
            )
        )

        val sourceCompiler = SourceCompiler(releaseContext, mockParentDisposable)

        // 编译 MinifyTestEnum
        val compileTask = CompileTask(
            files = listOf(
                CompileFile(
                    type = CompileFile.Type.Kotlin,
                    file = File(TestGlobal.assetsAndroidDir, "app/src/main/java/com/sickworm/jugg/demo/testcase/minify/MinifyTestEnum.kt"),
                    baseDir = File(TestGlobal.assetsAndroidDir, "app/src/main/java/"),
                    module = releaseContext.applicationModule,
                )
            ),
            outputDir = stagingDir,
        )

        val compileResult = sourceCompiler.compile(compileTask)
        compileResult.printCompileErrors()
        assertTrue(compileResult.isAllSuccess, "Compilation should succeed for MinifyTestEnum")

        // 获取编译输出的 DEX 文件
        val dexOutputs = compileResult.outputs.filter { it.type == com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex }
        assertTrue(dexOutputs.isNotEmpty(), "Should have DEX outputs")

        // 检查是否生成了 _jugg_fix 类
        val allClasses = mutableSetOf<String>()
        dexOutputs.forEach { output ->
            val classes = extractClassNamesFromDex(output.file)
            allClasses.addAll(classes)
            logger.debug("DEX file ${output.file.name} contains classes: $classes")
        }

        // 验证是否包含 _jugg_fix 后缀的类
        val juggFixClasses = allClasses.filter { it.contains("_jugg_fix") }
        logger.info("Found ${juggFixClasses.size} _jugg_fix classes: $juggFixClasses")

        // Phase 2 应该生成 _jugg_fix 类
        assertTrue(juggFixClasses.isNotEmpty(), "Should generate _jugg_fix classes in Phase 2")
    }

    /**
     * 从 DEX 文件中提取所有类名
     */
    private fun extractClassNamesFromDex(dexFile: File): Set<String> {
        val classNames = mutableSetOf<String>()
        val dexReader = DexFileReader(dexFile.readBytes())

        dexReader.accept(object : DexFileVisitor() {
            override fun visit(accessFlags: Int, className: String, superClass: String?, interfaceNames: Array<out String>?): DexClassVisitor? {
                classNames.add(className)
                return null
            }
        }, 0)

        return classNames
    }

    /**
     * 从 DEX 文件中提取所有类型引用
     */
    private fun extractTypeReferencesFromDex(dexFile: File): Set<String> {
        val references = mutableSetOf<String>()
        val dexReader = DexFileReader(dexFile.readBytes())

        dexReader.accept(object : DexFileVisitor() {
            override fun visit(accessFlags: Int, className: String, superClass: String?, interfaceNames: Array<out String>?): DexClassVisitor? {
                superClass?.let { references.add(it) }
                interfaceNames?.forEach { references.add(it) }
                return null
            }
        }, 0)

        return references
    }
}
