package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleDependency
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

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
            buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debug", buildDirRelativePath = ""),
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
        buildPathInfo = ModuleBuildPathInfo(projectDir, appDir, "debugAndroidTest", buildDirRelativePath = ""),
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
        buildPathInfo = ModuleBuildPathInfo(projectDir, File("/project/$name"), "debug", buildDirRelativePath = ""),
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

    @Test
    fun `runtime module dependencies keep feature only dependency out of base apk`() {
        val testRoot = Files.createTempDirectory("jugg-module-apk-runtime").toFile()
        try {
            val appMod = appModule().copy(
                projectRootDir = testRoot,
                moduleRootDir = File(testRoot, "app"),
                buildPathInfo = ModuleBuildPathInfo(testRoot, File(testRoot, "app"), "debug", buildDirRelativePath = ""),
                moduleDependencies = listOf(ModuleDependency("mlive")),
                runtimeModuleDependencies = listOf(ModuleDependency("mlive")),
            )
            val featureDir = File(testRoot, "feature")
            val featureMod = ModuleInfo.virtualModule.copy(
                name = "feature",
                moduleType = ModuleInfo.Type.DynamicFeature,
                projectRootDir = testRoot,
                moduleRootDir = featureDir,
                buildPathInfo = ModuleBuildPathInfo(testRoot, featureDir, "debug", buildDirRelativePath = ""),
                moduleDependencies = listOf(ModuleDependency("mlive")),
                runtimeModuleDependencies = listOf(ModuleDependency("mlive"), ModuleDependency("business_gift")),
            )
            featureMod.buildPathInfo.mergedManifest.apply {
                parentFile.mkdirs()
                writeText("<manifest featureSplit=\"feature\" />")
            }
            val mlive = libraryModule("mlive").copy(
                projectRootDir = testRoot,
                moduleRootDir = File(testRoot, "mlive"),
                moduleDependencies = listOf(ModuleDependency("business_gift")),
            )
            val businessGift = libraryModule("business_gift").copy(
                projectRootDir = testRoot,
                moduleRootDir = File(testRoot, "business_gift"),
            )
            val modules = listOf(appMod, featureMod, mlive, businessGift).associateBy { it.name }
            val baseApk = ApkFileUnit("com.example.app", "", true, File("base.apk"))
            val featureApk = ApkFileUnit("com.example.app", "feature", true, File("feature.apk"))

            val result = ModuleApkBelongsUtils.getModuleApkBelongs(
                appMod,
                listOf(ApkInfo(listOf(baseApk, featureApk), "com.example.app")),
                modules,
                ModuleInfo.virtualModule,
                com.intellij.openapi.diagnostic.Logger.getInstance("test"),
            )

            assertEquals(baseApk, result.getBelongsApk(mlive))
            assertEquals(featureApk, result.getBelongsApk(businessGift))
        } finally {
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun `missing runtime module dependencies use legacy dependency traversal`() {
        val appMod = appModule().copy(moduleDependencies = listOf(ModuleDependency("mlive")))
        val mlive = libraryModule("mlive").copy(
            moduleDependencies = listOf(ModuleDependency("business_gift")),
        )
        val businessGift = libraryModule("business_gift")
        val featureApk = ApkFileUnit("com.example.app", "feature", true, File("feature.apk"))
        val baseApk = appApkInfo().files.first()
        val modules = listOf(appMod, mlive, businessGift).associateBy { it.name }

        val result = ModuleApkBelongsUtils.getModuleApkBelongs(
            appMod,
            listOf(ApkInfo(listOf(baseApk, featureApk), "com.example.app")),
            modules,
            ModuleInfo.virtualModule,
            com.intellij.openapi.diagnostic.Logger.getInstance("test"),
        )

        assertEquals(baseApk, result.getBelongsApk(businessGift))
    }
}
