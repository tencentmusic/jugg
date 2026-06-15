package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class JuggDeployDataTest {

    private val basePath = "/base.apk"
    private val testPath = "/test.apk"
    private val baseApk = apkInfo("com.example.app", basePath)
    private val testApk = apkInfo("com.example.library.test", testPath)

    @Test
    fun `filterForApks keeps only deploy items that target selected apk`() {
        val baseOnlyClass = classDeployItem("BaseOnly", listOf(basePath))
        val testOnlyClass = classDeployItem("TestOnly", listOf(testPath))
        val sharedClass = classDeployItem("Shared", listOf(basePath, testPath))
        val data = deployData(
            newClasses = listOf(baseOnlyClass, testOnlyClass, sharedClass),
            overlays = listOf(
                deployItem("base.xml", CompileOutput.Type.Res, basePath, listOf(basePath)),
                deployItem("test.xml", CompileOutput.Type.Res, testPath, listOf(testPath)),
                deployItem("shared.xml", CompileOutput.Type.Res, basePath, listOf(basePath, testPath)),
            ),
            updateApkFiles = listOf(
                deployItem("base.so", CompileOutput.Type.NativeLib, basePath, listOf(basePath)),
                deployItem("test.so", CompileOutput.Type.NativeLib, testPath, listOf(testPath)),
                deployItem("shared.so", CompileOutput.Type.NativeLib, basePath, listOf(basePath, testPath)),
            ),
        )

        val baseScoped = data.filterForApks(listOf(baseApk))
        val testScoped = data.filterForApks(listOf(testApk))

        assertEquals(listOf("BaseOnly", "Shared"), baseScoped.newClasses.map { it.name })
        assertEquals(listOf("base.xml", "shared.xml"), baseScoped.overlays.map { it.name })
        assertEquals(listOf("base.so", "shared.so"), baseScoped.updateApkFiles.map { it.name })
        assertEquals(listOf(baseApk), baseScoped.apks)

        assertEquals(listOf("TestOnly", "Shared"), testScoped.newClasses.map { it.name })
        assertEquals(listOf("test.xml", "shared.xml"), testScoped.overlays.map { it.name })
        assertEquals(listOf("test.so", "shared.so"), testScoped.updateApkFiles.map { it.name })
        assertEquals(listOf(testApk), testScoped.apks)
    }

    @Test
    fun `filterForApks filters hot fix and hot reload classes`() {
        val data = deployData(
            hotFixModifiedClasses = listOf(
                classDeployItem("BaseHotFix", listOf(basePath)),
                classDeployItem("TestHotFix", listOf(testPath)),
            ),
            hotReloadModifiedClasses = listOf(
                classDeployItem("BaseHotReload", listOf(basePath)),
                classDeployItem("TestHotReload", listOf(testPath)),
            ),
        )

        val testScoped = data.filterForApks(listOf(testApk))

        assertEquals(listOf("TestHotFix"), testScoped.hotFixModifiedClasses.map { it.name })
        assertEquals(listOf("TestHotReload"), testScoped.hotReloadModifiedClasses.map { it.name })
    }

    @Test
    fun `groupByApplicationId returns scoped deploy data for each package`() {
        val data = deployData(
            newClasses = listOf(
                classDeployItem("BaseOnly", listOf(basePath)),
                classDeployItem("Shared", listOf(basePath, testPath)),
                classDeployItem("TestOnly", listOf(testPath)),
            ),
        )

        val scopedGroups = data.groupByApplicationId()

        assertEquals(listOf("com.example.app", "com.example.library.test"), scopedGroups.map { it.first })
        assertEquals(listOf(baseApk), scopedGroups[0].second)
        assertEquals(listOf("BaseOnly", "Shared"), scopedGroups[0].third.newClasses.map { it.name })
        assertEquals(listOf(testApk), scopedGroups[1].second)
        assertEquals(listOf("Shared", "TestOnly"), scopedGroups[1].third.newClasses.map { it.name })
    }

    @Test
    fun `filterForApks keeps lifecycle metadata because scoped data is only for deploy transport`() {
        val baseClass = classDeployItem("BaseOnly", listOf(basePath))
        val testClass = classDeployItem("TestOnly", listOf(testPath))
        val parsedDex = ParsedDex(
            classDeployItems = listOf(baseClass, testClass),
            methodRefs = mapOf(MethodNode("BaseOnly", 0, "run", "()V") to listOf("TestOnly")),
            fieldRefs = mapOf(FieldNode("BaseOnly", 0, "value", "I") to listOf("TestOnly")),
            subclassRefs = mapOf("BaseOnly" to listOf("TestOnly")),
        )
        val data = deployData(
            newClasses = listOf(baseClass, testClass),
            parsedDex = parsedDex,
            constRefEffectedSourcePaths = listOf("BaseOnly.kt", "TestOnly.kt"),
        )

        val baseScoped = data.filterForApks(listOf(baseApk))

        assertEquals(listOf("BaseOnly"), baseScoped.newClasses.map { it.name })
        assertEquals(parsedDex, baseScoped.parsedDex)
        assertEquals(data.constRefEffectedSourcePaths, baseScoped.constRefEffectedSourcePaths)
    }

    @Test
    fun `targetApkPathSample exposes original deploy targets for scoped logs`() {
        val data = deployData(
            newClasses = listOf(classDeployItem("TestOnly", listOf(testPath))),
            hotFixModifiedClasses = listOf(classDeployItem("Shared", listOf(basePath, testPath))),
            overlays = listOf(deployItem("base.xml", CompileOutput.Type.Res, basePath, listOf(basePath))),
        )

        assertEquals(listOf(testPath, basePath), data.targetApkPathSample())
    }

    @Test
    fun `splitData keeps full resource metadata on every slice`() {
        val data = deployData(
            overlays = listOf(
                deployItem("res/layout/first.xml", CompileOutput.Type.Res, basePath, listOf(basePath)),
                deployItem("res/layout/second.xml", CompileOutput.Type.Res, basePath, listOf(basePath)),
                deployItem("res/layout/third.xml", CompileOutput.Type.Res, basePath, listOf(basePath)),
            ),
            isFullRes = true,
        )

        val slices = data.splitData(firstMaxOverlaySize = 1, maxOverlaySize = 1)

        assertEquals(3, slices.size)
        assertEquals(listOf(true, true, true), slices.map { it.isFullRes })
        assertEquals(listOf(true, true, false), slices.map { it.isPushOverlayOnly })
    }

    private fun apkInfo(applicationId: String, apkPath: String): ApkInfo {
        return ApkInfo(
            files = listOf(ApkFileUnit(applicationId, "", true, File(apkPath))),
            applicationId = applicationId,
        )
    }

    private fun deployData(
        newClasses: List<ClassDeployItem> = emptyList(),
        hotFixModifiedClasses: List<ClassDeployItem> = emptyList(),
        hotReloadModifiedClasses: List<ClassDeployItem> = emptyList(),
        overlays: List<DeployItem> = emptyList(),
        updateApkFiles: List<DeployItem> = emptyList(),
        parsedDex: ParsedDex = ParsedDex.EMPTY,
        constRefEffectedSourcePaths: List<String> = emptyList(),
        isFullRes: Boolean = false,
    ): JuggDeployData {
        return JuggDeployData(
            apks = listOf(baseApk, testApk),
            newClasses = newClasses,
            hotFixModifiedClasses = hotFixModifiedClasses,
            hotReloadModifiedClasses = hotReloadModifiedClasses,
            effectedClassNodes = emptyList(),
            overlays = overlays,
            parsedDex = parsedDex,
            isFullRes = isFullRes,
            isWarmUp = false,
            updateApkFiles = updateApkFiles,
            constRefEffectedSourcePaths = constRefEffectedSourcePaths,
        )
    }

    private fun classDeployItem(name: String, targetApkPaths: List<String>): ClassDeployItem {
        return ClassDeployItem(
            deployItem(name, CompileOutput.Type.Dex, DeployItem.FLAG_CLASS, targetApkPaths),
            listOf(classNode(name)),
        )
    }

    private fun deployItem(
        name: String,
        type: CompileOutput.Type,
        apkPath: String,
        targetApkPaths: List<String>,
    ): DeployItem {
        return DeployItem(
            name = name,
            type = type,
            checksum = 1L,
            content = byteArrayOf(1),
            apkPath = apkPath,
            targetApkPaths = targetApkPaths,
        )
    }

    private fun classNode(name: String): ClassNode {
        return ClassNode(
            dexFileName = "$name.dex",
            className = name,
            access = 0,
            methods = emptyList(),
            fields = emptyList(),
            interfaceNames = emptyList(),
            superClass = "java.lang.Object",
            sourceArg = "$name.kt",
        )
    }
}
