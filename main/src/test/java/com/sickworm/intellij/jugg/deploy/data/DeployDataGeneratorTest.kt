package com.sickworm.intellij.jugg.deploy.data

import com.googlecode.d2j.DexConstants
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.MethodNode
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeployDataGeneratorTest {

    private val abcParsedDexMock: ParsedDex = getAdbParsedDex()
    private val abdClassNode get() = abcParsedDexMock.classDeployItems[0].classNode

    @Test
    fun testOverlayContents() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())
        val overlayDeployItem = DeployItem("test_overlay", CompileOutput.Type.Overlay, 0, byteArrayOf())
        val data = generator.buildDeployData(listOf(overlayDeployItem), false)
        assertEquals(475, data.overlays.size)
        assertTrue(data.isFullOverlays)
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

    private fun getAdbParsedDex(): ParsedDex {
        val parsedApk = ApkParser().parse(projectInfo.apkInfo)
        val className = "com.example.myapplication.ABC"
        val classNode = parsedApk.classes[className.convertClassToSigFormat()]!!
        val deployItem = DeployItem(className, CompileOutput.Type.Dex, 0, byteArrayOf())
        return ParsedDex(
            listOf(ClassDeployItem(deployItem, classNode)),
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
        )
    }
}