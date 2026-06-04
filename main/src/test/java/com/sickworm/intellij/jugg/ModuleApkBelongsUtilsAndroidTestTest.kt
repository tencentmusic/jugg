package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ModuleApkBelongsUtilsAndroidTestTest {

    private val projectDir = File("/project")
    private val appDir = File("/project/app")

    private fun appModule(name: String = "app", appId: String = "com.example.app") =
        ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = ModuleInfo.Type.Application,
            moduleRootDir = appDir,
            projectRootDir = projectDir,
            applicationId = appId,
            buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debug"),
        )

    private fun androidTestModule(
        name: String = "app.androidTest",
        testAppId: String = "com.example.app.test",
        targetPkg: String = "com.example.app",
    ) = ModuleInfo.virtualModule.copy(
        name = name,
        moduleType = ModuleInfo.Type.Library,
        moduleRootDir = appDir,
        projectRootDir = projectDir,
        applicationId = testAppId,
        instrumentationTargetPackage = targetPkg,
        buildVariant = "debugAndroidTest",
        buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debugAndroidTest"),
    )

    private fun libraryModule(
        name: String = "library1",
        appId: String = "com.example.library1",
    ) = ModuleInfo.virtualModule.copy(
        name = name,
        moduleType = ModuleInfo.Type.Library,
        moduleRootDir = File("/project/$name"),
        projectRootDir = projectDir,
        applicationId = appId,
        buildPathInfo = ModuleBuildPathInfo(projectDir, File("/project/$name"), "debug"),
    )

    private fun apkFileUnit(id: String, file: String = "$id.apk") =
        ApkFileUnit(id, "", true, File(file))

    private fun appApkInfo(id: String = "com.example.app") = ApkInfo(
        files = listOf(apkFileUnit(id)),
        applicationId = id,
    )

    private fun testApkInfo(
        testId: String = "com.example.app.test",
        targetPkg: String = "com.example.app",
    ) = ApkInfo(
        files = listOf(apkFileUnit(testId)),
        applicationId = testId,
        instrumentationTargetPackage = targetPkg,
    )

    @Test
    fun `app androidTest module maps to base ApkFileUnit`() {
        val appMod = appModule()
        val testMod = androidTestModule()
        val modules = mapOf(appMod.name to appMod, testMod.name to testMod)
        val apkInfos = listOf(appApkInfo(), testApkInfo())
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        val appApkUnit = apkInfos.first { !it.isTestApk }.files.first()
        assertEquals(appApkUnit, result.getBelongsApk(testMod))
        assertEquals(listOf(appApkUnit), result.getAllBelongsApk(testMod))
    }

    @Test
    fun `app module maps to base (non-test) ApkFileUnit`() {
        val appMod = appModule()
        val testMod = androidTestModule()
        val modules = mapOf(appMod.name to appMod, testMod.name to testMod)
        val apkInfos = listOf(appApkInfo(), testApkInfo())
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        val appApkUnit = apkInfos.first { !it.isTestApk }.files.first()
        assertEquals(appApkUnit, result.getBelongsApk(appMod))
        assertEquals(listOf(appApkUnit), result.getAllBelongsApk(appMod))
    }

    @Test
    fun `app androidTest module and app module map to same ApkFileUnit`() {
        val appMod = appModule()
        val testMod = androidTestModule()
        val modules = mapOf(appMod.name to appMod, testMod.name to testMod)
        val apkInfos = listOf(appApkInfo(), testApkInfo())
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        assertEquals(result.getBelongsApk(appMod), result.getBelongsApk(testMod))
    }

    @Test
    fun `self targeting androidTest module maps to test ApkFileUnit`() {
        val appMod = appModule()
        val testMod = androidTestModule(
            name = "library1.androidTest",
            testAppId = "com.example.library1.test",
            targetPkg = "com.example.library1.test",
        )
        val modules = mapOf(appMod.name to appMod, testMod.name to testMod)
        val apkInfos = listOf(
            appApkInfo(),
            testApkInfo(testId = "com.example.library1.test", targetPkg = "com.example.library1.test"),
        )
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        val testApkUnit = apkInfos.first { it.isTestApk }.files.first()
        assertEquals(testApkUnit, result.getBelongsApk(testMod))
        assertEquals(listOf(testApkUnit), result.getAllBelongsApk(testMod))
    }

    @Test
    fun `androidTest module falls back to base apk when no test apk exists`() {
        val appMod = appModule()
        val testMod = androidTestModule()
        val modules = mapOf(appMod.name to appMod, testMod.name to testMod)
        val apkInfos = listOf(appApkInfo()) // no test apk
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        val baseApkUnit = apkInfos.first().files.first()
        assertEquals(baseApkUnit, result.getBelongsApk(testMod))
        assertEquals(listOf(baseApkUnit), result.getAllBelongsApk(testMod))
    }

    @Test
    fun `androidTest module falls back to base apk when multiple apks but no test apk`() {
        val appMod = appModule()
        val testMod = androidTestModule()
        // Two APKs: base app + a feature APK, but NO test APK
        // This exercises the real fallback path in Step 0 (not the single-APK short-circuit)
        val featureApkInfo = ApkInfo(
            files = listOf(ApkFileUnit("com.example.app", "feature", true, File("feature.apk"))),
            applicationId = "com.example.app",
        )
        val apkInfos = listOf(appApkInfo(), featureApkInfo)
        val modules = mapOf(appMod.name to appMod, testMod.name to testMod)
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        val baseApkUnit = apkInfos.first { !it.isTestApk }.files.first()
        assertEquals(baseApkUnit, result.getBelongsApk(testMod))
        assertEquals(listOf(baseApkUnit), result.getAllBelongsApk(testMod))
    }

    @Test
    fun `temp module exposes both base and test ApkFileUnits`() {
        val appMod = appModule()
        val testMod = androidTestModule()
        val modules = mapOf(appMod.name to appMod, testMod.name to testMod)
        val apkInfos = listOf(appApkInfo(), testApkInfo())
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        val baseApkUnit = apkInfos.first { !it.isTestApk }.files.first()
        val testApkUnit = apkInfos.first { it.isTestApk }.files.first()
        assertEquals(baseApkUnit, result.getBelongsApk(tempModule))
        assertEquals(listOf(baseApkUnit, testApkUnit), result.getAllBelongsApk(tempModule))
    }

    @Test
    fun `library module exposes base apk and matching self targeting test apk`() {
        val appMod = appModule()
        val libraryMod = libraryModule()
        val testMod = androidTestModule(
            name = "library1.androidTest",
            testAppId = "com.example.library1.test",
            targetPkg = "com.example.library1.test",
        ).copy(
            moduleDependencies = listOf(com.sickworm.intellij.jugg.project.data.ModuleDependency("library1")),
        )
        val modules = mapOf(appMod.name to appMod, libraryMod.name to libraryMod, testMod.name to testMod)
        val apkInfos = listOf(
            appApkInfo(),
            testApkInfo(testId = "com.example.library1.test", targetPkg = "com.example.library1.test"),
        )
        val tempModule = ModuleInfo.virtualModule

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(appMod, apkInfos, modules, tempModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"))

        val baseApkUnit = apkInfos.first { !it.isTestApk }.files.first()
        val testApkUnit = apkInfos.first { it.isTestApk }.files.first()
        assertEquals(baseApkUnit, result.getBelongsApk(libraryMod))
        assertEquals(listOf(baseApkUnit, testApkUnit), result.getAllBelongsApk(libraryMod))
    }
}
