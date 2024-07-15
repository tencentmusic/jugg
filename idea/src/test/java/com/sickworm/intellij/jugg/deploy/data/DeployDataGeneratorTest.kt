package com.sickworm.intellij.jugg.deploy.data

import com.googlecode.d2j.DexConstants
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.DexCompiler
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.deploy.classNameToPath
import com.sickworm.intellij.jugg.deploy.classSigName
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.toDeployItem
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.JuggInternalException
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeployDataGeneratorTest {


    private lateinit var parsedApk: ParsedApk

    private val abcParsedDexMock: ParsedDex get() = getParsedDex("com.example.myapplication.ABC")
    private val abdClassNode get() = abcParsedDexMock.classDeployItems[0].classNode

    private lateinit var generator: DeployDataGenerator

    @Before
    fun assemble() {
        clearBuild()
        parsedApk = ApkParser().parse(projectInfo.apkInfo)
        generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())
    }

    @Test
    fun testOverlayContents() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())
        val data = generator.buildDeployData(emptyList(), true)
        assertTrue(data.overlays.isNotEmpty())
        assertTrue(data.isFullRes)
        logger.debug(data.toString())
    }

    @Test
    fun testHotModified() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())
        var data = generator.buildDeployData(abcParsedDexMock, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(1, data.hotReloadModifiedClasses.size)
        assertEquals(0, data.hotFixModifiedClasses.size)
        assertEquals(0, data.effectedSourceFileNames.size)

        generator.commitDeployedData(data)
        data = generator.buildDeployData(abcParsedDexMock, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(1, data.hotReloadModifiedClasses.size)
        assertEquals(0, data.hotFixModifiedClasses.size)
        assertEquals(0, data.effectedSourceFileNames.size)
    }

    @Test
    fun testHotFix() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())

        val addMethodParsedDex = abcParsedDexMock.updateMethods(abdClassNode.methods.subList(0, abdClassNode.methods.size - 1))
        var data = generator.buildDeployData(addMethodParsedDex, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(0, data.hotReloadModifiedClasses.size)
        assertEquals(1, data.hotFixModifiedClasses.size)

        generator.commitDeployedData(data)
        data = generator.buildDeployData(addMethodParsedDex, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(1, data.hotReloadModifiedClasses.size)
        assertEquals(0, data.hotFixModifiedClasses.size)

        generator.commitDeployedData(data)
        val addMethodParsedDex2 = addMethodParsedDex.updateMethods(emptyList())
        data = generator.buildDeployData(addMethodParsedDex2, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(0, data.hotReloadModifiedClasses.size)
        assertEquals(1, data.hotFixModifiedClasses.size)
    }

    @Test
    fun testEffectSource() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())

        val modifiedMethods = abdClassNode.methods.map {
            if (it.name == "haha") {
                MethodNode(it.owner, DexConstants.ACC_PRIVATE, it.name, it.desc)
            } else {
                it
            }
        }
        val removeMethodParsedDex = abcParsedDexMock.updateMethods(modifiedMethods)
        var data = generator.buildDeployData(removeMethodParsedDex, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(0, data.hotReloadModifiedClasses.size)
        assertEquals(1, data.hotFixModifiedClasses.size)
        assertEquals(1, data.effectedSourceFileNames.size)
        assertEquals("MainActivity2.java", data.effectedSourceFileNames[0])

        generator.commitDeployedData(data)
        data = generator.buildDeployData(removeMethodParsedDex, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(1, data.hotReloadModifiedClasses.size)
        assertEquals(0, data.hotFixModifiedClasses.size)
        assertEquals(0, data.effectedSourceFileNames.size)

        val newRemoveMethodParsedDex = abcParsedDexMock.updateMethods(abdClassNode.methods)
        data = generator.buildDeployData(newRemoveMethodParsedDex, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(0, data.hotReloadModifiedClasses.size)
        assertEquals(1, data.hotFixModifiedClasses.size)
        assertEquals(1, data.effectedSourceFileNames.size)
        assertEquals("MainActivity2.java", data.effectedSourceFileNames[0])
    }

    @Test
    fun testEffectSourceBySubclasses() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())

        val parsedDex = getParsedDex("com.sickworm.jugg.demo.testcase.subclass.RootClass")
        val classNode = parsedDex.classDeployItems[0].classNode
        val removedMethods = classNode.methods.filter { it.name != "func1" }
        val removeMethodParsedDex = parsedDex.updateMethods(removedMethods)
        val effectedSources = listOf("SubClass1.java", "SubClass2.java", "SubSubClass2.java", "SubClass3.java", "SubClass4.java", "InvokeClass.java")

        var data = generator.buildDeployData(removeMethodParsedDex, emptyList())
        assertEquals(effectedSources.sorted(), data.effectedSourceFileNames.sorted())
        generator.commitDeployedData(data)

        val fullParsedDex = parsedApk.toParsedDex
        data = generator.buildDeployData(fullParsedDex, emptyList())
        generator.commitDeployedData(data)

        data = generator.buildDeployData(removeMethodParsedDex, emptyList())
        assertEquals(effectedSources.sorted(), data.effectedSourceFileNames.sorted())
    }

    @Test
    fun testGetDesugarClasspath() {
        assertDesugarClasspath(
            "com.sickworm.jugg.demo.testcase.defaultinterface.ImplementClass1",
            "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
        )

        assertDesugarClasspath(
            "com.sickworm.jugg.demo.testcase.defaultinterface.ImplementClass3",
            "Lcom/sickworm/jugg/demo/testcase/defaultinterface/ImplementBaseInterface3;",
            "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
        )

        assertDesugarClasspath(
            "com.sickworm.jugg.demo.testcase.defaultinterface.ImplementBaseClass2",
            "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
        )

        assertDesugarClasspath(
            "com.sickworm.jugg.demo.testcase.defaultinterface.InvokerClass1",
            "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
        )

        assertDesugarClasspath(
            "com.sickworm.jugg.demo.testcase.defaultinterface.ImplementClassKt1",
            "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterfaceKt;",
        )
    }

    @Test
    fun testGetDesugarClasspathCrossInterface() {
        assertDesugarClasspath(
            "com.sickworm.jugg.demo.testcase.defaultinterface.ImplementClass4",
            "Lcom/sickworm/jugg/demo/testcase/defaultinterface/ImplementBaseInterface4;",
            "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
        )
    }

    @Test
    fun testGetDesugarClasspathByIncAddInterfaceExtend() {
        var parsedDex = getParsedDex("com.sickworm.jugg.demo.testcase.defaultinterface.DefaultInterface")
        parsedDex = parsedDex.updates(interfaceNames = listOf(
            "Lcom/sickworm/jugg/demo/testcase/newinterfacemethod/Interface;"
        ))
        val deployData = generator.buildDeployData(parsedDex, emptyList())
        generator.commitDeployedData(deployData)

        assertDesugarClasspath(
            "com.sickworm.jugg.demo.testcase.defaultinterface.ImplementClass1",
            "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
            "Lcom/sickworm/jugg/demo/testcase/newinterfacemethod/Interface;", // new interface
        )
        assertDesugarClasspath(
            "com.sickworm.jugg.demo.testcase.defaultinterface.InvokerClass1",
            "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
        )
    }

    @Test
    fun testGetDesugarClasspathByIncAddNewInterface() {
        var parsedDex = getParsedDex("com.sickworm.jugg.demo.testcase.defaultinterface.DefaultInterface")
        val newClassName = "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface2;"
        parsedDex = parsedDex.updates(className = newClassName)

        var parsedDexCc = getParsedDex("com.sickworm.jugg.demo.testcase.defaultinterface.DefaultInterface$-CC")
        val newClassNameCc = "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface2$-CC;"
        parsedDexCc = parsedDexCc.updates(className = newClassNameCc)

        val finalParsedDex = ParsedDex(
            parsedDex.classDeployItems + parsedDexCc.classDeployItems,
            parsedDex.methodRefs + parsedDexCc.methodRefs,
            parsedDex.fieldRefs + parsedDexCc.fieldRefs,
            parsedDex.subclassRefs + parsedDexCc.subclassRefs,
        )

        val deployData = generator.buildDeployData(finalParsedDex, emptyList())
        generator.commitDeployedData(deployData)

        val classpathByInterface = generator.getAllInterfacesWithDefaultMethod(listOf("Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface2;"), emptyList())
        assertContentEquals(listOf("Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface2;"), classpathByInterface)

        val classpathByStaticInvoke = generator.getAllInterfacesWithDefaultMethod(emptyList(), listOf("Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface2;"))
        assertContentEquals(listOf("Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface2;"), classpathByStaticInvoke)

        val classpathByIncorrectName = generator.getAllInterfacesWithDefaultMethod(listOf("error"), listOf("error2"))
        assertContentEquals(emptyList(), classpathByIncorrectName)
    }

    private fun assertDesugarClasspath(className: String, vararg expected: String) {
        val classFile = getClassFile(className)
        val classpath = generator.getAllInterfacesWithDefaultMethod(listOf(classFile))
        assertContentEquals(expected.sorted(), classpath.sorted())
    }

    @Test
    fun testEffectSourceByNewAbstractMethod() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())

        val parsedDex = getParsedDex("com.sickworm.jugg.demo.testcase.newabstractmethod.AbstractClass")
        val classNode = parsedDex.classDeployItems[0].classNode
        val addedMethods = classNode.methods + MethodNode(
            classNode.className,
            DexConstants.ACC_PUBLIC or DexConstants.ACC_ABSTRACT,
            "newAbstractMethod3",
            "()V",
        )
        val removeMethodParsedDex = parsedDex.updateMethods(addedMethods)
        val effectedSources = listOf("AbstractSubClass1.java", "ImplClass1.java", "ImplClass2.java")

        var data = generator.buildDeployData(removeMethodParsedDex, emptyList())
        assertEquals(effectedSources.sorted(), data.effectedSourceFileNames.sorted())
        generator.commitDeployedData(data)

        val fullParsedDex = parsedApk.toParsedDex
        data = generator.buildDeployData(fullParsedDex, emptyList())
        generator.commitDeployedData(data)

        data = generator.buildDeployData(removeMethodParsedDex, emptyList())
        assertEquals(effectedSources.sorted(), data.effectedSourceFileNames.sorted())
    }

    /**
     * Actually works same as [testEffectSourceByNewAbstractMethod]
     */
    @Test
    fun testEffectSourceByNewInterfaceMethod() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())

        val parsedDex = getParsedDex("com.sickworm.jugg.demo.testcase.newinterfacemethod.Interface")
        val classNode = parsedDex.classDeployItems[0].classNode
        val addedMethods = classNode.methods + MethodNode(
            classNode.className,
            DexConstants.ACC_PUBLIC or DexConstants.ACC_ABSTRACT,
            "fun4",
            "()V",
        )
        val removeMethodParsedDex = parsedDex.updateMethods(addedMethods)
        val effectedSources = listOf("ImplClass1.java", "ImplClass2.java", "AbstractClass1.java")

        var data = generator.buildDeployData(removeMethodParsedDex, emptyList())
        assertEquals(effectedSources.sorted(), data.effectedSourceFileNames.sorted())
        generator.commitDeployedData(data)

        val fullParsedDex = parsedApk.toParsedDex
        data = generator.buildDeployData(fullParsedDex, emptyList())
        generator.commitDeployedData(data)

        data = generator.buildDeployData(removeMethodParsedDex, emptyList())
        assertEquals(effectedSources.sorted(), data.effectedSourceFileNames.sorted())
    }

    @Test
    fun testEffectSourceByAddingKotlinDefaultParam() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())

        val sourceCompiler = SourceCompiler(context, mockParentDisposable)
        val compileTask = CompileTask(
            files = listOf(
                CompileFile(
                    CompileFile.Type.Kotlin,
                    File("$assetsAndroidModifySourceDir/app/src/main/java/com/sickworm/jugg/demo/testcase/ktdefaultparam/ClassWithDefaultParam.kt"),
                    File(assetsAndroidModifySourceDir, "app/src/main/java"),
                    context.tempModule,
                    dependencyPaths = listOf("$assetsLibDir/kotlin-stdlib-1.3.72.jar")
                )
            ),
            outputDir = stagingDir,
        )
        val compileResult = sourceCompiler.compile(compileTask)
        assertTrue(compileResult.isAllSuccess)
        assertTrue(compileResult.outputs.isNotEmpty())

        val deployItems = compileResult.outputs.map { it.toDeployItem() }
        val deployData = generator.buildDeployData(deployItems)
        assertEquals(listOf("JavaInvoker.java", "KtInvoker.kt"), deployData.effectedSourceFileNames.sorted())
    }

    @Test
    fun testEffectSourceByAddingKotlinDefaultParamOnTopLevelFunction() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())

        val sourceCompiler = SourceCompiler(context, mockParentDisposable)
        val compileTask = CompileTask(
            files = listOf(
                CompileFile(
                    CompileFile.Type.Kotlin,
                    File("$assetsAndroidModifySourceDir/app/src/main/java/com/sickworm/jugg/demo/testcase/kttopleveloptionalfunction/TopLevelClass1.kt"),
                    File(assetsAndroidModifySourceDir, "app/src/main/java"),
                    context.tempModule,
                    dependencyPaths = listOf("$assetsLibDir/kotlin-stdlib-1.3.72.jar")
                ),
                CompileFile(
                    CompileFile.Type.Kotlin,
                    File("$assetsAndroidDir/app/src/main/java/com/sickworm/jugg/demo/testcase/kttopleveloptionalfunction/InvokeClass2.kt"),
                    File(assetsAndroidDir, "app/src/main/java"),
                    context.tempModule,
                    dependencyPaths = listOf("$assetsLibDir/kotlin-stdlib-1.3.72.jar")
                ),
            ),
            outputDir = stagingDir,
        )
        val compileResult = sourceCompiler.compile(compileTask)
        assertTrue(compileResult.isAllSuccess)
        assertTrue(compileResult.outputs.isNotEmpty())

        val deployItems = compileResult.outputs.map { it.toDeployItem() }
        val deployData = generator.buildDeployData(deployItems)
        assertEquals(listOf("InvokeClass2.kt"), deployData.effectedSourceFileNames.sorted())
    }

    @Test
    fun testEffectSourceByAddingKotlinDefaultParamOnTopLevelFunction2() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())

        val sourceCompiler = SourceCompiler(context, mockParentDisposable)
        val compileTask = CompileTask(
            files = listOf(
                CompileFile(
                    CompileFile.Type.Kotlin,
                    File("$assetsAndroidModifySourceDir/app/src/main/java/com/sickworm/jugg/demo/testcase/kttopleveloptionalfunction/TopLevelClass3.kt"),
                    File(assetsAndroidModifySourceDir, "app/src/main/java"),
                    context.tempModule,
                    dependencyPaths = listOf("$assetsLibDir/kotlin-stdlib-1.3.72.jar")
                ),
                CompileFile(
                    CompileFile.Type.Kotlin,
                    File("$assetsAndroidDir/app/src/main/java/com/sickworm/jugg/demo/testcase/kttopleveloptionalfunction/InvokeClass4.kt"),
                    File(assetsAndroidDir, "app/src/main/java"),
                    context.tempModule,
                    dependencyPaths = listOf("$assetsLibDir/kotlin-stdlib-1.3.72.jar")
                ),
            ),
            outputDir = stagingDir,
        )
        val compileResult = sourceCompiler.compile(compileTask)
        assertTrue(compileResult.isAllSuccess)
        assertTrue(compileResult.outputs.isNotEmpty())

        val deployItems = compileResult.outputs.map { it.toDeployItem() }
        val deployData = generator.buildDeployData(deployItems)
        assertEquals(listOf("InvokeClass4.kt"), deployData.effectedSourceFileNames.sorted())
    }

    private fun getClassFile(className: String): CompileFile {
        val relativePath = className.classNameToPath
        val baseJavaDir = assetsAndroidDir.resolve("app/build/intermediates/javac/debug/classes")
        val baseKotlinDir = assetsAndroidDir.resolve("app/build/tmp/kotlin-classes/debug")
        if (File(baseJavaDir, relativePath).exists()) {
            val file = File(baseJavaDir, relativePath)
            return CompileFile(
                CompileFile.Type.Class,
                file,
                baseJavaDir,
                context.tempModule,
            )
        }
        if (File(baseKotlinDir, relativePath).exists()) {
            val file = File(baseKotlinDir, relativePath)
            return CompileFile(
                CompileFile.Type.Class,
                file,
                baseKotlinDir,
                context.tempModule,
            )
        }

        throw IllegalArgumentException("class $className not found")
    }

    private fun getParsedDex(className: String): ParsedDex {
        val classSigName = className.classSigName
        return ParsedDex(
            parsedApk.classes.filter { it.key == classSigName }.map {
                ClassDeployItem(
                    DeployItem(it.key, CompileOutput.Type.Dex, 0, byteArrayOf()),
                    listOf(it.value),
                )
            },
            parsedApk.methodRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
            parsedApk.fieldRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
            parsedApk.subclassRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
        )
    }

    private fun ParsedDex.updateMethods(methods: List<MethodNode>): ParsedDex {
        return updates(methods = methods)
    }

    private fun ParsedDex.updates(
        className: String = this.classDeployItems[0].classNode.className,
        methods: List<MethodNode> = this.classDeployItems[0].classNode.methods,
        interfaceNames: List<String> = this.classDeployItems[0].classNode.interfaceNames,
    ): ParsedDex {
        val oldClassNode = this.classDeployItems[0]
        val newClassNode = ClassDeployItem(
            oldClassNode.deployItem,
            listOf(ClassNode(
                oldClassNode.classNode.dexFileName,
                className,
                oldClassNode.classNode.access,
                methods,
                oldClassNode.classNode.fields,
                interfaceNames,
                oldClassNode.classNode.superClass,
                oldClassNode.classNode.source,
            ))
        )
        return ParsedDex(
            listOf(newClassNode),
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )
    }

    @Test
    fun testJars() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())

        val dexCompiler = DexCompiler(context, mockParentDisposable)
        val compileTask = CompileTask(
            files = listOf(
                CompileFile(
                    CompileFile.Type.Class,
                    File(assetsLibDir, "rxjava-3.0.12.jar"),
                    File(assetsLibDir, "rxjava-3.0.12.jar"),
                    context.tempModule,
                ).withDependencyName("Gradle: io.reactivex.rxjava3:rxjava:3.0.12@aar")
            ),
            outputDir = stagingDir,
        )
        val compileResult = dexCompiler.compile(compileTask)
        assertTrue(compileResult.isAllSuccess)
        assertTrue(compileResult.outputs.isNotEmpty())

        val deployItems = compileResult.outputs.map { it.toDeployItem() }
        val deployData = generator.buildDeployData(deployItems)
        assertEquals(0, deployData.newClasses.size)
        assertEquals(1, deployData.hotFixModifiedClasses.size)
        assertEquals(0, deployData.hotReloadModifiedClasses.size)
    }

    private val ParsedApk.toParsedDex: ParsedDex
        get() {
            return ParsedDex(
                classDeployItems = this.classes.values.map {
                    ClassDeployItem(
                        DeployItem(it.className, CompileOutput.Type.Dex, 0, byteArrayOf()),
                        listOf(it),
                    )
                },
                methodRefs = this.methodRefs,
                fieldRefs = this.fieldRefs,
                subclassRefs = this.subclassRefs,
            )
        }
}

private val JuggDeployData.effectedSourceFileNames get() = effectedClassNodes.map { it.sourceFileName }.distinct()

val ClassDeployItem.classNode: ClassNode
    get() {
        if (classNodes.size != 1) {
            throw JuggInternalException.dexFileNotContainsOnlyOneClass(classNodes.size)
        }
        return classNodes.first()
    }
