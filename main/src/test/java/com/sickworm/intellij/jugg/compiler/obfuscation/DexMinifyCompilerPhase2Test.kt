package com.sickworm.intellij.jugg.compiler.obfuscation

import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.node.insn.FieldStmtNode
import com.googlecode.d2j.node.insn.MethodStmtNode
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.googlecode.d2j.visitors.DexMethodVisitor
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2 tests for _jugg_fix generation and redirection.
 */
class DexMinifyCompilerPhase2Test {

    private lateinit var releaseContext: SimpleCompileContext
    private lateinit var releaseApkFile: File
    private lateinit var generator: DeployDataGenerator

    @Before
    fun assemble() {
        clearBuild()
        GradleBuildHelper.appAssembleRelease()

        releaseApkFile = File(TestGlobal.assetsAndroidDir, "build/app/outputs/apk/release/app-release.apk")
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
        val testClassName = "Lcom/sickworm/jugg/demo/testcase/minify/MinifyTestEnum;"
        val testClassFile = File(
            TestGlobal.assetsAndroidDir,
            "app/build/tmp/kotlin-classes/release/com/sickworm/jugg/demo/testcase/minify/MinifyTestEnum.class"
        )

        if (!testClassFile.exists()) {
            logger.warn("Test class file not found: ${testClassFile.absolutePath}")
            logger.warn("Skipping test")
            return
        }

        releaseContext.setMinifyInfo(
            MinifyInfo(
                inlineEffectedClasses = listOf(
                    InlineEffectedClass(
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
        val compileResult = sourceCompiler.compile(createSourceCompileTask("MinifyTestEnum.kt"))
        compileResult.printCompileErrors()
        assertTrue(compileResult.isAllSuccess, "Compilation should succeed for MinifyTestEnum")

        val dexOutputs = compileResult.outputs.filter { it.type == CompileOutput.Type.Dex }
        assertTrue(dexOutputs.isNotEmpty(), "Should have DEX outputs")

        val allClasses = mutableSetOf<String>()
        dexOutputs.forEach { output ->
            val classes = extractClassNamesFromDex(output.file)
            allClasses.addAll(classes)
            logger.debug("DEX file ${output.file.name} contains classes: $classes")
        }

        val juggFixClasses = allClasses.filter { it.contains("_jugg_fix") }
        logger.info("Found ${juggFixClasses.size} _jugg_fix classes: $juggFixClasses")
        assertTrue(juggFixClasses.isNotEmpty(), "Should generate _jugg_fix classes in Phase 2")
    }

    @Test
    fun testJuggFixShouldStubMethodsDeletedInUsageFile() {
        val originalClassName = "com.sickworm.jugg.demo.testcase.minify.KeepClassMembers"
        val classSigName = "Lcom/sickworm/jugg/demo/testcase/minify/KeepClassMembers;"
        val testClassFile = File(
            TestGlobal.assetsAndroidDir,
            "app/build/tmp/kotlin-classes/release/com/sickworm/jugg/demo/testcase/minify/KeepClassMembers.class"
        )
        assertTrue(testClassFile.exists(), "Class file should exist for KeepClassMembers")

        releaseContext.setMinifyInfo(
            MinifyInfo(
                inlineEffectedClasses = listOf(
                    InlineEffectedClass(
                        className = classSigName,
                        effectedByClasses = listOf("Lcom/sickworm/jugg/demo/testcase/minify/MinifyTestActivity;")
                    )
                ),
                classFiles = mapOf(originalClassName to testClassFile)
            )
        )

        val sourceCompiler = SourceCompiler(releaseContext, mockParentDisposable)
        val compileResult = sourceCompiler.compile(createSourceCompileTask("KeepClassMembers.kt"))
        compileResult.printCompileErrors()
        assertTrue(compileResult.isAllSuccess, "Compilation should succeed for KeepClassMembers")

        val mappingReader = R8MappingReader.fromFile(releaseContext.mappingFile!!)
        val obfuscatedClassName = mappingReader.getObfuscatedClassName(originalClassName)
        assertNotNull(obfuscatedClassName, "KeepClassMembers should exist in mapping.txt")
        val juggFixClassName = "L${obfuscatedClassName!!.replace('.', '/')}${DexObfuscator.SUFFIX};"

        val dexOutputs = compileResult.outputs.filter { it.type == CompileOutput.Type.Dex }
        val juggFixDexOutput = dexOutputs.firstOrNull { output ->
            extractClassNamesFromDex(output.file).contains(juggFixClassName)
        }
        assertNotNull(juggFixDexOutput, "Should generate a _jugg_fix dex for KeepClassMembers")

        val methodNames = extractMethodNamesFromDex(juggFixDexOutput!!.file, juggFixClassName)
        val classMapping = mappingReader.getClassMappingByOriginalName(originalClassName)
        assertNotNull(classMapping, "KeepClassMembers should have class mapping details")
        val keptSetterMapping = classMapping!!.methods.firstOrNull {
            it.originalName == "setKeptField" && it.parameters == "java.lang.String"
        }
        assertNotNull(keptSetterMapping, "setKeptField should still exist in mapping")

        assertTrue(
            methodNames.contains(keptSetterMapping!!.obfuscatedName),
            "Non-deleted method should still be present in _jugg_fix"
        )
        assertTrue(methodNames.contains("getKeptField"), "Deleted getter should stay as compatibility stub")
        assertTrue(methodNames.contains("getObfuscatedField"), "Deleted getter should stay as compatibility stub")
        assertTrue(methodNames.contains("obfuscatedMethod"), "Deleted method should stay as compatibility stub")
        assertTrue(methodNames.contains("setObfuscatedField"), "Deleted setter should stay as compatibility stub")

        assertMethodHasNoFieldOrMethodRefs(juggFixDexOutput.file, juggFixClassName, "getKeptField")
        assertMethodHasNoFieldOrMethodRefs(juggFixDexOutput.file, juggFixClassName, "getObfuscatedField")
        assertMethodHasNoFieldOrMethodRefs(juggFixDexOutput.file, juggFixClassName, "setObfuscatedField")
    }

    private fun createSourceCompileTask(fileName: String): CompileTask {
        return CompileTask(
            files = listOf(
                CompileFile(
                    type = CompileFile.Type.Kotlin,
                    file = File(
                        TestGlobal.assetsAndroidDir,
                        "app/src/main/java/com/sickworm/jugg/demo/testcase/minify/$fileName"
                    ),
                    baseDir = File(TestGlobal.assetsAndroidDir, "app/src/main/java/"),
                    module = releaseContext.applicationModule,
                )
            ),
            outputDir = stagingDir,
        )
    }

    private fun extractClassNamesFromDex(dexFile: File): Set<String> {
        val classNames = mutableSetOf<String>()
        val dexReader = DexFileReader(dexFile.readBytes())

        dexReader.accept(object : DexFileVisitor() {
            override fun visit(
                accessFlags: Int,
                className: String,
                superClass: String?,
                interfaceNames: Array<out String>?
            ): DexClassVisitor? {
                classNames.add(className)
                return null
            }
        }, 0)

        return classNames
    }

    private fun extractMethodNamesFromDex(dexFile: File, targetClassName: String): Set<String> {
        val methodNames = mutableSetOf<String>()
        val dexReader = DexFileReader(dexFile.readBytes())

        dexReader.accept(object : DexFileVisitor() {
            override fun visit(
                accessFlags: Int,
                className: String,
                superClass: String?,
                interfaceNames: Array<out String>?
            ): DexClassVisitor? {
                if (className != targetClassName) {
                    return null
                }
                return object : DexClassVisitor() {
                    override fun visitMethod(
                        accessFlags: Int,
                        method: com.googlecode.d2j.Method
                    ): DexMethodVisitor? {
                        methodNames.add(method.name)
                        return null
                    }
                }
            }
        }, 0)

        return methodNames
    }

    private fun extractTypeReferencesFromDex(dexFile: File): Set<String> {
        val references = mutableSetOf<String>()
        val dexReader = DexFileReader(dexFile.readBytes())

        dexReader.accept(object : DexFileVisitor() {
            override fun visit(
                accessFlags: Int,
                className: String,
                superClass: String?,
                interfaceNames: Array<out String>?
            ): DexClassVisitor? {
                superClass?.let { references.add(it) }
                interfaceNames?.forEach { references.add(it) }
                return null
            }
        }, 0)

        return references
    }

    private fun assertMethodHasNoFieldOrMethodRefs(
        dexFile: File,
        targetClassName: String,
        methodName: String,
    ) {
        val dexNode = readDex(dexFile.readBytes())
        val classNode = dexNode.clzs.firstOrNull { it.className == targetClassName }
        assertNotNull(classNode, "Target class should exist in DEX: $targetClassName")
        val methodNode = classNode!!.methods.firstOrNull { it.method.name == methodName }
        assertNotNull(methodNode, "Target method should exist in DEX: $methodName")

        val fieldRefs = methodNode!!.codeNode?.stmts?.filterIsInstance<FieldStmtNode>().orEmpty()
        val methodRefs = methodNode.codeNode?.stmts?.filterIsInstance<MethodStmtNode>().orEmpty()
        assertTrue(fieldRefs.isEmpty(), "Stub method should not access fields: $methodName")
        assertTrue(methodRefs.isEmpty(), "Stub method should not invoke methods: $methodName")
    }

    private fun readDex(bytes: ByteArray): DexFileNode {
        val node = DexFileNode()
        DexFileReader(bytes).accept(node, 0)
        return node
    }
}
