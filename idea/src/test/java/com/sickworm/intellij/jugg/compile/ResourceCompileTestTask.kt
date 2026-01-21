package com.sickworm.intellij.jugg.compile

import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.overlay.ArscCompiler
import com.sickworm.intellij.jugg.compiler.overlay.ResourceCompiler
import com.sickworm.intellij.jugg.compiler.overlay.ResourceOverlayCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.After
import org.junit.Before
import java.io.File

class ResourceCompileTestTask {

    private val flatFiles = assetsFlatDir.listFilesRecursively()
        .map {
            CompileFile(CompileFile.Type.Flat, it, assetsFlatDir, mockModule)
        }

    private lateinit var resCompiler: ResourceCompiler
    private lateinit var arscCompiler: ArscCompiler
    private lateinit var resourceOverlayCompiler: ResourceOverlayCompiler

    @Before
    fun init() {
        clearBuild()
        resourceOverlayCompiler = ResourceOverlayCompiler(context, mockParentDisposable)
        resCompiler = ResourceCompiler(context, mockParentDisposable)
        arscCompiler = ArscCompiler(context, mockParentDisposable)
    }

    @After
    fun release() {
        Disposer.dispose(resourceOverlayCompiler)
        Disposer.dispose(resCompiler)
        Disposer.dispose(arscCompiler)
    }

    private val baseDir = File(assetsAndroidDir, "app/src/main/res/")
    val resourceOverlayTask = CompileTask(
        listOf(
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/layout/activity_main2.xml"), baseDir, mockModule),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/layout/activity_main3.xml"), baseDir, mockModule),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/drawable/ic_launcher_background.xml"), baseDir, mockModule),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/drawable/ic_launcher_background2.xml"), baseDir, mockModule),
        ),
        stagingDir
    )

    val resourceOverlayAddIdsTask = CompileTask(
        listOf(
            CompileFile(CompileFile.Type.Resource,
                File(assetsAndroidModifySourceDir, "app/src/main/res/layout/activity_main.xml"),
                File(assetsAndroidModifySourceDir, "app/src/main/res"),
                mockModule),
        ),
        stagingDir
    )

}