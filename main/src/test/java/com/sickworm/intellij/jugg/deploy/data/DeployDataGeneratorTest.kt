package com.sickworm.intellij.jugg.deploy.data

import com.googlecode.d2j.DexConstants
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.MethodNode
import com.sickworm.intellij.jugg.deploy.classSigName
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.clearBuild
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import org.junit.Before
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeployDataGeneratorTest {


    private lateinit var parsedApk: ParsedApk

    private val abcParsedDexMock: ParsedDex get() = getParsedDex("com.example.myapplication.ABC")
    private val abdClassNode get() = abcParsedDexMock.classDeployItems[0].classNode

    @Before
    fun assemble() {
        clearBuild()
        parsedApk = ApkParser().parse(projectInfo.apkInfo)
    }

    @Test
    fun testOverlayContents() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())
        val data = generator.buildDeployData(emptyList(), true)
        assertEquals(431, data.overlays.size)
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

        val newMethod = MethodNode(abdClassNode.className, DexConstants.ACC_PUBLIC, "aNewMethod", "()V")
        val addMethodParsedDex = abcParsedDexMock.updateMethods(abdClassNode.methods + newMethod)
        var data = generator.buildDeployData(addMethodParsedDex, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(0, data.hotReloadModifiedClasses.size)
        assertEquals(1, data.hotFixModifiedClasses.size)
        assertEquals(0, data.effectedSourceFileNames.size)

        generator.commitDeployedData(data)
        data = generator.buildDeployData(addMethodParsedDex, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(1, data.hotReloadModifiedClasses.size)
        assertEquals(0, data.hotFixModifiedClasses.size)
        assertEquals(0, data.effectedSourceFileNames.size)

        generator.commitDeployedData(data)
        val newMethod2 = MethodNode(abdClassNode.className, DexConstants.ACC_PUBLIC, "aNewMethod2", "()V")
        val addMethodParsedDex2 = addMethodParsedDex.updateMethods(abdClassNode.methods + newMethod2)
        data = generator.buildDeployData(addMethodParsedDex2, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(0, data.hotReloadModifiedClasses.size)
        assertEquals(1, data.hotFixModifiedClasses.size)
        assertEquals(0, data.effectedSourceFileNames.size)
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
    fun testFixDefaultMethod() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())
        var parsedDex = getParsedDex("com.sickworm.jugg.demo.testcase.defaultinterface.ImplementClass1")
        var deployData = generator.buildDeployData(parsedDex, emptyList())
        assertContentEquals(listOf("Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;"), deployData.desugaredInterfacesWithDefaultMethods)

        parsedDex = getParsedDex("com.sickworm.jugg.demo.testcase.defaultinterface.ImplementClass2")
        deployData = generator.buildDeployData(parsedDex, emptyList())
        assertContentEquals(listOf("Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;"), deployData.desugaredInterfacesWithDefaultMethods)

        parsedDex = getParsedDex("com.sickworm.jugg.demo.testcase.defaultinterface.ImplementClass3")
        deployData = generator.buildDeployData(parsedDex, emptyList())
        assertContentEquals(listOf("Lcom/sickworm/jugg/demo/testcase/defaultinterface/ImplementBaseInterface3;", "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;"), deployData.desugaredInterfacesWithDefaultMethods)
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
        val effectedSources = listOf("ImplClass2.java")

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
        val effectedSources = listOf("ImplClass1.java", "ImplClass2.java")

        var data = generator.buildDeployData(removeMethodParsedDex, emptyList())
        assertEquals(effectedSources.sorted(), data.effectedSourceFileNames.sorted())
        generator.commitDeployedData(data)

        val fullParsedDex = parsedApk.toParsedDex
        data = generator.buildDeployData(fullParsedDex, emptyList())
        generator.commitDeployedData(data)

        data = generator.buildDeployData(removeMethodParsedDex, emptyList())
        assertEquals(effectedSources.sorted(), data.effectedSourceFileNames.sorted())
    }

    private fun getParsedDex(className: String): ParsedDex {
        val classNode = parsedApk.classes[className.classSigName]!!
        val deployItem = DeployItem(className, CompileOutput.Type.Dex, 0, byteArrayOf())
        return ParsedDex(
            listOf(ClassDeployItem(deployItem, classNode)),
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )
    }

    private fun ParsedDex.updateMethods(methods: List<MethodNode>): ParsedDex {
        val oldClassNode = this.classDeployItems[0]
        val newClassNode = ClassDeployItem(
            oldClassNode.deployItem,
            ClassNode(
                oldClassNode.classNode.dexFileName,
                oldClassNode.classNode.className,
                oldClassNode.classNode.access,
                methods,
                oldClassNode.classNode.fields,
                oldClassNode.classNode.interfaceNames,
                oldClassNode.classNode.superClass,
                oldClassNode.classNode.source,
            )
        )
        return ParsedDex(
            listOf(newClassNode),
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )
    }

    private val ParsedApk.toParsedDex: ParsedDex
        get() {
            return ParsedDex(
                classDeployItems = parsedApk.classes.values.map {
                    ClassDeployItem(
                        DeployItem(it.className, CompileOutput.Type.Dex, 0, byteArrayOf()),
                        it,
                    )
                },
                methodRefs = parsedApk.methodRefs,
                fieldRefs = parsedApk.fieldRefs,
                subclassRefs = parsedApk.subclassRefs,
            )
        }
}