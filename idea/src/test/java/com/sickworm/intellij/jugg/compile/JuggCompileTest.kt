package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.overlay.ARSC_FILE_NAME
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.IllegalStateException
import kotlin.test.assertTrue

class JuggCompileTest {

    private val juggCompiler = JuggCompiler(context, mockParentDisposable)

    @Before
    fun init() {
        clearBuild()
        ResourceCompileTestTask().init()
    }

    @Test
    fun compileSingleJava() {
        val task = JavaCompileTest().helloWorldTask
        val result = juggCompiler.compile(task)
        assertCompileResultJugg(task, result)
    }

    @Test
    fun compileMultiJava() {
        val task = JavaCompileTest().multiFilesTask
        val result = juggCompiler.compile(task)
        assertCompileResultJugg(task, result)
    }

    @Test
    fun compileMultiJavaWithError() {
        val task = JavaCompileTest().multiFilesWithErrorTask
        val result = juggCompiler.compile(task)
        assertCompileResultFailed(task, result, mapOf(JavaCompileTest().errorTask.files[0] to 2))
    }

    @Test
    fun compileMultiAssets() {
        val task = AssetCompileTest().multiFilesTask
        val result = juggCompiler.compile(task)
        assertCompileResultJugg(task, result)
    }

    @Test
    fun compileResource() {
        val task = ResourceCompileTestTask().resourceOverlayTask
        val result = juggCompiler.compile(task)
        assertCompileResultJugg(task, result)
    }

    @Test
    fun compileResourceAddIds() {
        val task = ResourceCompileTestTask().resourceOverlayAddIdsTask
        val result = juggCompiler.compile(task)
        assertCompileResultJugg(task, result)
    }

    @Test
    fun compileStyleableResourceChangeIncludesMainRStyleableDexOutput() {
        val attrsFile = File(assetsAndroidModifySourceDir, "app/src/main/res/values/attrs.xml")
        val task = CompileTask(
            listOf(CompileFile(CompileFile.Type.Resource, attrsFile, attrsFile.parentFile.parentFile, mockModule)),
            stagingDir,
        )
        val result = juggCompiler.compile(task)

        assertTrue(result.isAllSuccess)

        val rStyleableDex = File(
            task.outputDir,
            "classes/${androidApkPackage.replace(".", "/")}/R\$styleable.dex"
        )
        assertTrue(rStyleableDex.exists(), "R\$styleable.dex should be compiled")
        assertTrue(
            String(rStyleableDex.readBytes(), Charsets.ISO_8859_1).contains("test_add_styleable_value"),
            "R\$styleable.dex should include changed styleable fields"
        )
        assertTrue(
            result.outputs.any { it.file == rStyleableDex },
            "R\$styleable.dex should be included in compile outputs"
        )
    }

    @Test
    fun compileMultiJavaAndAsset() {
        val task = JavaCompileTest().multiFilesTask + AssetCompileTest().multiFilesTask
        val result = juggCompiler.compile(task)
        assertCompileResultJugg(task, result)
    }

    @Test
    fun compileMultiJavaAndAssetAndRes() {
        val task = JavaCompileTest().multiFilesTask + AssetCompileTest().multiFilesTask + ResourceCompileTestTask().resourceOverlayTask
        val result = juggCompiler.compile(task)
        assertCompileResultJugg(task, result)
    }

    @Test
    fun compileMultiJavaErrorAndAsset() {
        val sourceTask = JavaCompileTest().multiFilesWithErrorTask
        val assetTask = AssetCompileTest().multiFilesTask
        val task = sourceTask + assetTask
        val result = juggCompiler.compile(task)

        val sourceResult = CompileResult(
            sourceTask,
            result.details.filter { sourceTask.files.contains(it.file) },
            emptyList()
        )
        assertCompileResultFailed(sourceTask, sourceResult, mapOf(JavaCompileTest().errorTask.files[0] to 2))

        val assetResult = CompileResult(
            assetTask,
            result.details.filter { assetTask.files.contains(it.file) },
            result.outputs
        )
        assertCompileResultJugg(assetTask, assetResult)
    }

    @Test
    fun compileMultiJavaErrorAndAssetAndRes() {
        val sourceTask = JavaCompileTest().multiFilesWithErrorTask
        val assetTask = AssetCompileTest().multiFilesTask
        val resourceTask = ResourceCompileTestTask().resourceOverlayTask
        val task = sourceTask + assetTask + resourceTask
        val result = juggCompiler.compile(task)

        val sourceResult = CompileResult(
            sourceTask,
            result.details.filter { sourceTask.files.contains(it.file) },
            emptyList()
        )
        assertCompileResultFailed(sourceTask, sourceResult, mapOf(JavaCompileTest().errorTask.files[0] to 2))
    }

    @Test
    fun compileDataBinding() {
        val compileTask = CompileHelper.makeTask(
            File(assetsAndroidDir, "app/src/main/res/layout/activity_data_binding_java_demo.xml")
        )

        val result = juggCompiler.compile(compileTask)
        assertTrue(result.isAllSuccess)
        CompileHelper.checkOutputFiles(result, listOf(
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",
            "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBinding.dex",
            "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBindingImpl.dex",
            "res/layout/activity_data_binding_java_demo.xml",
            "resources.arsc",
        ))
    }

    @Test
    fun compileDataBindingIncludes() {

        fun compileNewXmlDataBinding() {
            CompileHelper.outputDir.clearDir()
            val compileTask = CompileHelper.makeTask(
                File(TestGlobal.projectInfo.modifiedSource, "app/src/main/res/layout/activity_data_binding_new.xml"),
            )
            val result = juggCompiler.compile(compileTask)
            assertTrue(result.isAllSuccess)

            CompileHelper.checkOutputFiles(result, listOf(
                "androidx/databinding/DataBinderMapperImpl.dex",
                "androidx/databinding/DataBindingComponent.dex",
                "com/example/myapplication/BR.dex",
                "com/example/myapplication/DataBinderMapperImpl.dex",
                "com/example/myapplication/DataBinderMapperImpl_Full.dex",
                "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",
                "com/example/myapplication/databinding/ActivityDataBindingNewBinding.dex",
                "com/example/myapplication/databinding/ActivityDataBindingNewBindingImpl.dex",
                "res/layout/activity_data_binding_new.xml",
                "resources.arsc",
            ))

            (juggCompiler.context as SimpleCompileContext).deployedFiles.addAll(result.outputs)
        }

        fun compileNewXml2DataBinding() {
            CompileHelper.outputDir.clearDir()
            val compileTask = CompileHelper.makeTask(
                File(TestGlobal.projectInfo.modifiedSource, "app/src/main/res/layout/activity_data_binding_new2.xml"),
            )
            val result = juggCompiler.compile(compileTask)
            assertTrue(result.isAllSuccess)

            CompileHelper.checkOutputFiles(result, listOf(
                "androidx/databinding/DataBinderMapperImpl.dex",
                "androidx/databinding/DataBindingComponent.dex",
                "com/example/myapplication/BR.dex",
                "com/example/myapplication/DataBinderMapperImpl.dex",
                "com/example/myapplication/DataBinderMapperImpl_Full.dex",
                "com/example/myapplication/DataBinderMapperImpl_Inc_2.dex",
                "com/example/myapplication/databinding/ActivityDataBindingNew2Binding.dex",
                "com/example/myapplication/databinding/ActivityDataBindingNew2BindingImpl.dex",
                "res/layout/activity_data_binding_new2.xml",
                "resources.arsc",
            ))

            (juggCompiler.context as SimpleCompileContext).deployedFiles.addAll(result.outputs)
        }

        fun compileXmlIncludeNewXmlDataBinding() {
            CompileHelper.outputDir.clearDir()
            val compileTask = CompileHelper.makeTask(
                File(TestGlobal.projectInfo.modifiedSource, "app/src/main/res/layout/activity_data_binding_new_include.xml"),
            )
            val result = juggCompiler.compile(compileTask)
            assertTrue(result.isAllSuccess)

            CompileHelper.checkOutputFiles(result, listOf(
                "androidx/databinding/DataBinderMapperImpl.dex",
                "androidx/databinding/DataBindingComponent.dex",
                "com/example/myapplication/BR.dex",
                "com/example/myapplication/DataBinderMapperImpl.dex",
                "com/example/myapplication/DataBinderMapperImpl_Full.dex",
                "com/example/myapplication/DataBinderMapperImpl_Inc_3.dex",
                "com/example/myapplication/databinding/ActivityDataBindingNewIncludeBinding.dex",
                "com/example/myapplication/databinding/ActivityDataBindingNewIncludeBindingImpl.dex",
                "res/layout/activity_data_binding_new_include.xml",
                "resources.arsc",
            ))

            (juggCompiler.context as SimpleCompileContext).deployedFiles.addAll(result.outputs)
        }

        fun compileXmlIncludeOldXmlDataBinding() {
            CompileHelper.outputDir.clearDir()
            val compileTask = CompileHelper.makeTask(
                File(TestGlobal.projectInfo.modifiedSource, "app/src/main/res/layout/activity_data_binding_old_include.xml"),
            )
            val result = juggCompiler.compile(compileTask)
            assertTrue(result.isAllSuccess)

            CompileHelper.checkOutputFiles(result, listOf(
                "androidx/databinding/DataBinderMapperImpl.dex",
                "androidx/databinding/DataBindingComponent.dex",
                "com/example/myapplication/BR.dex",
                "com/example/myapplication/DataBinderMapperImpl.dex",
                "com/example/myapplication/DataBinderMapperImpl_Full.dex",
                "com/example/myapplication/DataBinderMapperImpl_Inc_4.dex",
                "com/example/myapplication/databinding/ActivityDataBindingOldIncludeBinding.dex",
                "com/example/myapplication/databinding/ActivityDataBindingOldIncludeBindingImpl.dex",
                "res/layout/activity_data_binding_old_include.xml",
                "resources.arsc",
            ))

            (juggCompiler.context as SimpleCompileContext).deployedFiles.addAll(result.outputs)
        }

        compileNewXmlDataBinding()
        compileNewXml2DataBinding()
        compileXmlIncludeNewXmlDataBinding()
        compileXmlIncludeOldXmlDataBinding()
    }

    @Test
    fun compileDataBindingIncludeWithOnlyBindingClassLog() {
        val compileTask = CompileHelper.makeTask(
            File(TestGlobal.projectInfo.modifiedSource, "app/src/main/res/layout/activity_data_binding_new.xml"),
        )
        val layoutInfoDir = File(
            TestGlobal.projectInfo.projectRoot,
            "build/app/intermediates/data_binding_layout_info_type_merge/debug/out",
        )
        val includedLayoutInfos = layoutInfoDir.listFiles().orEmpty()
            .filter { it.name == "test_layout-layout.xml" || it.name.startsWith("test_layout-layout-") }
            .associateWith { it.readBytes() }
        val bindingClassLog = File(
            TestGlobal.projectInfo.projectRoot,
            "build/app/intermediates/data_binding_base_class_log_artifact/debug/out/" +
                    "com.example.myapplication-binding_classes.json",
        )

        assertTrue(includedLayoutInfos.isNotEmpty())
        assertTrue(bindingClassLog.readText(Charsets.UTF_16).contains("TestLayoutBinding"))
        try {
            includedLayoutInfos.keys.forEach { assertTrue(it.delete()) }

            val result = juggCompiler.compile(compileTask)

            assertTrue(result.isAllSuccess)
            CompileHelper.checkOutputFiles(result, listOf(
                "com/example/myapplication/databinding/ActivityDataBindingNewBinding.dex",
                "com/example/myapplication/databinding/ActivityDataBindingNewBindingImpl.dex",
            ))
        } finally {
            includedLayoutInfos.forEach { (file, content) -> file.writeBytes(content) }
        }
    }


    private fun assertCompileResultJugg(task: CompileTask, result: CompileResult, isRFileChanged: Boolean = false) {
        val mapper: OutputFileMapper = {
            if (it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin) {
                val outputBaseDir = File(task.outputDir, "classes")
                val outputFile = it.file.changeBaseDir(it.baseDir, outputBaseDir, "dex")
                listOf(CompileOutput(CompileOutput.Type.Dex, outputFile, outputBaseDir))
            } else if (it.type == CompileFile.Type.Asset) {
                val outputBaseDir = File(task.outputDir, "overlays")
                val outputFile = it.file.changeBaseDir(it.baseDir, File(outputBaseDir, "assets"))
                listOf(CompileOutput(CompileOutput.Type.Asset, outputFile, outputBaseDir, apkPath = TestGlobal.projectInfo.apkFile.path))
            } else if (it.type == CompileFile.Type.Resource) {
                val outputBaseDir = File(task.outputDir, "overlays")
                val outputFile = it.file.changeBaseDir(it.baseDir, File(outputBaseDir, "res"))
                val flatOutput = CompileOutput(
                    CompileOutput.Type.Res,
                    outputFile,
                    outputBaseDir,
                    apkPath = TestGlobal.projectInfo.apkFile.path
                )

                // R*.dex
                var dexOutputs = mutableListOf<CompileOutput>()
                if (isRFileChanged) {
                    val sourceBaseDir = File(task.outputDir, "classes")
                    val rOutDir = File(sourceBaseDir, androidApkPackage.replace(".", "/"))
                    val rDexList = ("R\$anim.dex, R\$attr.dex, R\$bool.dex, R\$color.dex, R\$dimen.dex, " +
                            "R\$drawable.dex, R\$id.dex, R\$integer.dex, R\$layout.dex, R\$mipmap.dex, " +
                            "R\$string.dex, R\$style.dex, R.dex").split(", ")
                    dexOutputs = rDexList.map { name ->
                        CompileOutput(CompileOutput.Type.Dex, File(rOutDir, name), sourceBaseDir)
                    }.toMutableList()
                }

                // resources.arsc
                val overlayBaseDir = File(task.outputDir, "overlays")
                val arscFile = File(overlayBaseDir, ARSC_FILE_NAME)
                val arscOutput = CompileOutput(CompileOutput.Type.Res, arscFile, overlayBaseDir, apkPath = TestGlobal.projectInfo.apkFile.path)

                // view binding
                if (it.file.name.endsWith(".xml") && it.file.parentFile.name.contains("layout")) {
                    val classesOutputBaseDir = File(task.outputDir, "classes")
                    val bindingFileName = toBindingClass(it.file)
                    val bindingFile = File(classesOutputBaseDir, TestGlobal.projectInfo.packageName.replace(".", "/") + "/databinding/" + bindingFileName)
                    val bindingOutput = CompileOutput(CompileOutput.Type.Dex, bindingFile, classesOutputBaseDir)
                    dexOutputs += bindingOutput
                }

                listOf<CompileOutput>() + flatOutput + arscOutput + dexOutputs
            } else {
                throw IllegalStateException("not supported")
            }
        }

        assertCompileResult(task, result, mapper)
    }

    private fun toBindingClass(xmlFile: File): String {
        var isNeedCamelCase = true
        val javaFileNameArray: List<String> = xmlFile.nameWithoutExtension.map { c ->
            return@map if (c == '_') {
                isNeedCamelCase = true
                ""
            } else if (isNeedCamelCase) {
                isNeedCamelCase = false
                c.uppercase()
            } else {
                c.toString()
            }
        }
        return javaFileNameArray.joinToString("") + "Binding.dex"
    }
}
