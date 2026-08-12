package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.DesugarInfo
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import com.sickworm.intellij.jugg.deploy.data.SourceFileManager
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompileEffectAnalyzerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `isBootClasspathClass returns true for java stdlib classes`() {
        assertTrue("Ljava/lang/Object;".isBootClasspathClass)
        assertTrue("Ljava/lang/String;".isBootClasspathClass)
        assertTrue("Ljava/util/ArrayList;".isBootClasspathClass)
        assertTrue("Ljava/lang/Exception;".isBootClasspathClass)
        assertTrue("Ljava/lang/ref/WeakReference;".isBootClasspathClass)
        assertTrue("Ljava/lang/System;".isBootClasspathClass)
        assertTrue("Ljava/util/Objects;".isBootClasspathClass)
        assertTrue("Ljava/util/Iterator;".isBootClasspathClass)
        assertTrue("Ljava/util/List;".isBootClasspathClass)
        assertTrue("Ljava/lang/Integer;".isBootClasspathClass)
        assertTrue("Ljava/lang/Boolean;".isBootClasspathClass)
        assertTrue("Ljava/lang/Class;".isBootClasspathClass)
        assertTrue("Ljava/lang/StringBuilder;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns true for android SDK classes`() {
        assertTrue("Landroid/view/View;".isBootClasspathClass)
        assertTrue("Landroid/widget/Toast;".isBootClasspathClass)
        assertTrue("Landroid/os/Bundle;".isBootClasspathClass)
        assertTrue("Landroid/content/Context;".isBootClasspathClass)
        assertTrue("Landroid/content/Intent;".isBootClasspathClass)
        assertTrue("Landroid/content/IntentFilter;".isBootClasspathClass)
        assertTrue("Landroid/content/res/Resources;".isBootClasspathClass)
        assertTrue("Landroid/os/Handler;".isBootClasspathClass)
        assertTrue("Landroid/os/SystemClock;".isBootClasspathClass)
        assertTrue("Landroid/text/TextUtils;".isBootClasspathClass)
        assertTrue("Landroid/view/LayoutInflater;".isBootClasspathClass)
        assertTrue("Landroid/view/ViewGroup;".isBootClasspathClass)
        assertTrue("Landroid/view/Window;".isBootClasspathClass)
        assertTrue("Landroid/view/animation/AlphaAnimation;".isBootClasspathClass)
        assertTrue("Landroid/view/animation/ScaleAnimation;".isBootClasspathClass)
        assertTrue("Landroid/view/animation/AnimationSet;".isBootClasspathClass)
        assertTrue("Landroid/widget/ImageView;".isBootClasspathClass)
        assertTrue("Landroid/widget/RelativeLayout;".isBootClasspathClass)
        assertTrue("Landroid/widget/RelativeLayout\$LayoutParams;".isBootClasspathClass)
        assertTrue("Landroid/app/ActivityManager;".isBootClasspathClass)
        assertTrue("Landroid/app/ActivityManager\$MemoryInfo;".isBootClasspathClass)
        assertTrue("Landroid/provider/Settings;".isBootClasspathClass)
        assertTrue("Landroid/provider/Settings\$Global;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns true for javax and dalvik classes`() {
        assertTrue("Ljavax/crypto/Cipher;".isBootClasspathClass)
        assertTrue("Ljavax/net/ssl/SSLContext;".isBootClasspathClass)
        assertTrue("Ldalvik/system/DexFile;".isBootClasspathClass)
        assertTrue("Ldalvik/system/PathClassLoader;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns false for androidx classes`() {
        assertFalse("Landroidx/fragment/app/Fragment;".isBootClasspathClass)
        assertFalse("Landroidx/lifecycle/LiveData;".isBootClasspathClass)
        assertFalse("Landroidx/lifecycle/ViewModelProvider;".isBootClasspathClass)
        assertFalse("Landroidx/constraintlayout/widget/ConstraintLayout\$LayoutParams;".isBootClasspathClass)
        assertFalse("Landroidx/fragment/app/FragmentManager;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns false for app classes`() {
        assertFalse("Lcom/example/MyActivity;".isBootClasspathClass)
        assertFalse("Lcom/tencent/wemusic/ui/main/activity/MainTabActivity;".isBootClasspathClass)
        assertFalse("Lcom/tencent/wemusic/ui/main/utils/ListenGuideReportUtils;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns false for kotlin stdlib classes`() {
        assertFalse("Lkotlin/collections/CollectionsKt;".isBootClasspathClass)
        assertFalse("Lkotlinx/coroutines/Dispatchers;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns false for obfuscated classes`() {
        assertFalse("Lxxx/oig;".isBootClasspathClass)
        assertFalse("Lxxx/eig;".isBootClasspathClass)
        assertFalse("Lxxx/iig;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns false for third party library classes`() {
        assertFalse("Lcom/google/android/material/tabs/TabLayout;".isBootClasspathClass)
        assertFalse("Lcom/appsflyer/MultipleInstallBroadcastReceiver;".isBootClasspathClass)
    }

    @Test
    fun `getRecompileFiles passes changed source paths to const ref on first round`() {
        val deployDataGenerator = mockDeployDataGenerator()
        val analyzer = newAnalyzer(deployDataGenerator)
        val sourceFile = File.createTempFile("MainTabActivity", ".java")
        sourceFile.deleteOnExit()
        val changedFile = ChangedFile(
            type = CompileFile.Type.Java,
            file = sourceFile,
            baseDir = sourceFile.parentFile,
            module = ModuleInfo.virtualModule,
        )

        analyzer.getRecompileFiles(
            stagingFiles = emptyList(),
            compiledFiles = listOf(changedFile),
            moduleInfos = emptyMap(),
            isMinified = false,
            isCompilingEffectedSourceFiles = false,
            classObfuscator = null,
        )

        verify(deployDataGenerator).buildDeployData(
            items = eq(emptyList<DeployItem>()),
            isWarmUp = eq(false),
            isNeedCheckRecompile = eq(true),
            isNeedCheckRecompileMinifyRemovedClass = eq(false),
            isCompilingEffectedSourceFiles = eq(false),
            constRefChangedSourcePaths = eq(listOf(sourceFile.stdAbsPath)),
        )
    }

    @Test
    fun `getRecompileFiles skips const ref changed source paths on effected source round`() {
        val deployDataGenerator = mockDeployDataGenerator()
        val analyzer = newAnalyzer(deployDataGenerator)
        val sourceFile = File.createTempFile("ColdSplashAdActivity", ".kt")
        sourceFile.deleteOnExit()
        val changedFile = ChangedFile(
            type = CompileFile.Type.Kotlin,
            file = sourceFile,
            baseDir = sourceFile.parentFile,
            module = ModuleInfo.virtualModule,
        )

        analyzer.getRecompileFiles(
            stagingFiles = emptyList(),
            compiledFiles = listOf(changedFile),
            moduleInfos = emptyMap(),
            isMinified = false,
            isCompilingEffectedSourceFiles = true,
            classObfuscator = null,
        )

        verify(deployDataGenerator).buildDeployData(
            items = eq(emptyList<DeployItem>()),
            isWarmUp = eq(false),
            isNeedCheckRecompile = eq(true),
            isNeedCheckRecompileMinifyRemovedClass = eq(false),
            isCompilingEffectedSourceFiles = eq(true),
            constRefChangedSourcePaths = eq(emptyList()),
        )
    }

    @Test
    fun `getDesugarInfo includes superclass chain in d8 classpath`() {
        val projectInfo = AssembleAndroidProjectOnce.getProjectInfo()
        val appModule = projectInfo.modules.getValue("app")
        val javaClassPath = appModule.buildPathInfo.javaClassPath
        val childClass = File(
            javaClassPath,
            "com/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideChildClass.class",
        )
        val deployDataGenerator = mock<DeployDataGenerator>()
        whenever(deployDataGenerator.getDesugarInfo(any(), any())).thenReturn(
            DesugarInfo(
                allInterfacesWithDefaultMethod = listOf(
                    "Lcom/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideChildInterface;",
                    "Lcom/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideDefaultInterface;",
                ),
                coreLibraryRewriteClassMap = emptyMap(),
                isNeedRewriteCoreLibrary = false,
                desugaredLibraryConfiguration = null,
            )
        )
        val outputDir = temporaryFolder.newFolder("desugar-classpath")
        val analyzer = CompileEffectAnalyzer(
            pathManager = JuggPathManager(TestGlobal.projectRootDir),
            deployDataGenerator = deployDataGenerator,
            sourceFileManager = mock(),
            logger = mock(),
        )

        analyzer.getDesugarInfo(
            compileFiles = listOf(
                CompileFile(CompileFile.Type.Class, childClass, javaClassPath, appModule),
            ),
            moduleInfo = appModule,
            moduleInfos = projectInfo.modules,
            toDir = outputDir,
            apkFile = TestGlobal.apkFile,
        )

        assertTrue(
            File(
                outputDir,
                "com/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideChildInterface.class",
            ).exists()
        )
        assertTrue(
            File(
                outputDir,
                "com/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideDefaultInterface.class",
            ).exists()
        )
        assertTrue(
            File(
                outputDir,
                "com/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideBaseClass.class",
            ).exists()
        )
        assertTrue(
            File(
                outputDir,
                "com/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideRootClass.class",
            ).exists()
        )
    }

    private fun newAnalyzer(deployDataGenerator: DeployDataGenerator): CompileEffectAnalyzer {
        return CompileEffectAnalyzer(
            pathManager = mock<JuggPathManager>(),
            deployDataGenerator = deployDataGenerator,
            sourceFileManager = mock<SourceFileManager>(),
            logger = mock<Logger>(),
        )
    }

    private fun mockDeployDataGenerator(): DeployDataGenerator {
        val deployDataGenerator = mock<DeployDataGenerator>()
        whenever(
            deployDataGenerator.buildDeployData(
                items = any(),
                isWarmUp = any(),
                isNeedCheckRecompile = any(),
                isNeedCheckRecompileMinifyRemovedClass = any(),
                isCompilingEffectedSourceFiles = any(),
                constRefChangedSourcePaths = any(),
            )
        ).thenReturn(emptyDeployData())
        return deployDataGenerator
    }

    private fun emptyDeployData(): JuggDeployData {
        return JuggDeployData(
            apks = emptyList(),
            newClasses = emptyList(),
            hotFixModifiedClasses = emptyList(),
            hotReloadModifiedClasses = emptyList(),
            effectedClassNodes = emptyList(),
            overlays = emptyList(),
            parsedDex = ParsedDex.EMPTY,
            isFullRes = false,
            isWarmUp = false,
        )
    }
}
