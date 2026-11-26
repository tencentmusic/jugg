package com.sickworm.intellij.jugg.compiler.context

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.info.SigningConfig
import com.sickworm.intellij.jugg.project.info.Variant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.io.File
import kotlin.test.assertEquals

/**
 * Verifies per-APK signing config resolution in BaseCompileContext.
 */
class BaseCompileContextSigningConfigTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun getSigningConfig_usesOwnConfigForSelfTargetingLibraryTestApk() {
        val appKeystore = temp.newFile("app_debug.jks")
        val libKeystore = temp.newFile("lib_debug.keystore")
        val appSigning = signingConfig("debug", appKeystore)
        val libSigning = signingConfig("debug", libKeystore)

        val appModule = TestGlobal.applicationModule.copy(
            buildVariant = "debug",
            variants = listOf(Variant("debug", "debug")),
            signingConfigs = listOf(appSigning),
        )
        val libTestModule = appModule.copy(
            name = "features.androidTest",
            moduleType = ModuleInfo.Type.Library,
            buildVariant = "debugAndroidTest",
            applicationId = "com.example.features.test",
            instrumentationTargetPackage = "com.example.features.test",
            variants = listOf(Variant("debugAndroidTest", "debug")),
            signingConfigs = listOf(libSigning),
        )
        val appApk = ApkInfo(File("app-debug.apk"), appModule.applicationId!!)
        val libTestApk = ApkInfo(
            files = listOf(ApkFileUnit("com.example.features.test", "", true, File("features-debug-androidTest.apk"))),
            applicationId = "com.example.features.test",
            instrumentationTargetPackage = "com.example.features.test",
        )
        val context = createContext(
            modules = mapOf(appModule.name to appModule, libTestModule.name to libTestModule),
            apkInfos = listOf(appApk, libTestApk),
        )

        assertEquals(appKeystore, context.getSigningConfig(appApk)?.keystore)
        assertEquals(libKeystore, context.getSigningConfig(libTestApk)?.keystore)
    }

    @Test
    fun getSigningConfig_fallsBackToAppConfigForOtherTargetingTestApk() {
        val appKeystore = temp.newFile("app_debug.jks")
        val appSigning = signingConfig("debug", appKeystore)
        val appModule = TestGlobal.applicationModule.copy(
            buildVariant = "debug",
            variants = listOf(Variant("debug", "debug")),
            signingConfigs = listOf(appSigning),
        )
        val appTestModule = appModule.copy(
            name = "app.androidTest",
            moduleType = ModuleInfo.Type.Library,
            buildVariant = "debugAndroidTest",
            applicationId = "${appModule.applicationId}.test",
            instrumentationTargetPackage = appModule.applicationId,
            variants = emptyList(),
            signingConfigs = null,
        )
        val appTestApk = ApkInfo(
            files = listOf(ApkFileUnit("${appModule.applicationId}.test", "", true, File("app-debug-androidTest.apk"))),
            applicationId = "${appModule.applicationId}.test",
            instrumentationTargetPackage = appModule.applicationId,
        )
        val context = createContext(
            modules = mapOf(appModule.name to appModule, appTestModule.name to appTestModule),
            apkInfos = listOf(appTestApk),
        )

        assertEquals(appKeystore, context.getSigningConfig(appTestApk)?.keystore)
    }

    @Test
    fun getSigningConfig_fallsBackToDefaultDebugKeystoreWhenSelfTargetingLibraryTestHasNoSigningConfigs() {
        val appKeystore = temp.newFile("app_debug.jks")
        val appSigning = signingConfig("debug", appKeystore)
        val defaultDebugKeystore = File(File(System.getProperty("user.home"), ".android"), "debug.keystore")
        val appModule = TestGlobal.applicationModule.copy(
            buildVariant = "debug",
            variants = listOf(Variant("debug", "debug")),
            signingConfigs = listOf(appSigning),
        )
        val libTestModule = appModule.copy(
            name = "features.androidTest",
            moduleType = ModuleInfo.Type.Library,
            buildVariant = "debugAndroidTest",
            applicationId = "com.example.features.test",
            instrumentationTargetPackage = "com.example.features.test",
            variants = emptyList(),
            signingConfigs = null,
        )
        val libTestApk = ApkInfo(
            files = listOf(ApkFileUnit("com.example.features.test", "", true, File("features-debug-androidTest.apk"))),
            applicationId = "com.example.features.test",
            instrumentationTargetPackage = "com.example.features.test",
        )
        val context = createContext(
            modules = mapOf(appModule.name to appModule, libTestModule.name to libTestModule),
            apkInfos = listOf(libTestApk),
        )

        val expectedKeystore = if (defaultDebugKeystore.exists()) defaultDebugKeystore else appKeystore
        assertEquals(expectedKeystore, context.getSigningConfig(libTestApk)?.keystore)
    }

    private fun signingConfig(name: String, keystore: File) = SigningConfig(
        configName = name,
        keystore = keystore,
        storePassword = "android",
        keyAlias = "androiddebugkey",
        keyPassword = "android",
        storeType = "PKCS12",
        enableV1Signing = true,
        enableV2Signing = true,
        enableV3Signing = false,
        enableV4Signing = false,
        isSigningReady = true,
    )

    private fun createContext(
        modules: Map<String, ModuleInfo>,
        apkInfos: List<ApkInfo>,
    ): BaseCompileContext {
        val baseContext = TestGlobal.context
        return BaseCompileContext(
            logger = TestGlobal.logger,
            tempCompileDir = baseContext.tempCompileDir,
            tempModuleDir = baseContext.tempModuleDir,
            androidHome = baseContext.androidHome,
            modules = modules,
            apkInfos = apkInfos,
            projectDir = baseContext.projectDir,
            incrementalDataDir = baseContext.incrementalDataDir,
            cmdCompileEnv = emptyList(),
            scene = ICompileContext.Scene.IDE,
            deployFileManager = mock<DeployFileManager>(),
            deployHistoryManager = mock<IDeployHistoryManager>(),
            customCompilerManager = mock<CustomCompilerManager>(),
        )
    }
}
