package com.sickworm.intellij.jugg.compiler.databinding

import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Manage argument for data binding.
 * Output structure is same as AGP 7.2.2.
 */
class DataBindingArgsManager(context: ICompileContext, private val moduleInfo: ModuleInfo) {

    val isUseAndroidX = true // just leave it true
    val isUseViewBinding = true // TODO read it
    val isUseDataBinding = true // TODO read it
    val isIncremental = false // we do incremental by our own way
    val packageName = context.packageName!!

    private val tempCompileDir = context.tempCompileDir

    // generate ViewBinding things e.g. ActivityMainBinding.java
    val dataBindingSourcesOutputDir get() = dir(tempCompileDir, "generated/data_binding_base_class_source_out/${moduleInfo.buildVariant}")
    val dataBindingLayoutXmlDir get() = dir(tempCompileDir, "intermediates/data_binding_layout_info_type_merge/${moduleInfo.buildVariant}/out")
    val dataBindingStrippedXmlDir get() = dir(tempCompileDir, "intermediates/incremental/${moduleInfo.buildVariant}/merge${moduleInfo.buildVariant.camel}/stripped.dir")
    val artifactFolder get() = dir(tempCompileDir, "intermediates/data_binding_base_class_log_artifact/${moduleInfo.buildVariant}/out/base_class_log_artifact")
    val v1ArtifactsFolder get() = artifactFolder
    val blameLogDir = File(tempCompileDir, "intermediates/merged_res_blame_folder/${moduleInfo.buildVariant}/out")
    val logFolder get() = dir(tempCompileDir, "intermediates/incremental/dataBindingGenBaseClasses${moduleInfo.buildVariant.camel}")
    val dependencyClassesFolders get() = dir(tempCompileDir, "other/dependency_classes_folder")

    // generate DataBinding things e.g. DataBinderMapperImpl.java
    val dataBindingPreProcessorSources get() = dir(tempCompileDir, "data_binding_trigger/${moduleInfo.buildVariant}")
    val dataBindingDependencyArtifacts get() = dir(tempCompileDir, "dependency_artifacts")
    val dataBindingArtifactFolder get() = dir(tempCompileDir, "base_class_log_artifact")
    val dataBindingKaptProcessorTrigger get() = file(dataBindingPreProcessorSources, packageName.replace(".", "/") + "/DataBindingInfo.java")
    val dataBindingKaptSourceTrigger get() = file(dataBindingPreProcessorSources, packageName.replace(".", "/") + "/DataBindingTrigger.kt")
    val dataBindingAarOutDir get() = dir(tempCompileDir, "bundle-bin")
    val dataBindingBaseFeatureInfoDir get() = dir(tempCompileDir, "base_feature_info")

    val dataBindingBrMergedDir get() = File(moduleInfo.buildPathInfo.buildDir, "generated/source/kapt/${moduleInfo.buildVariant}")
    val libraryBrRelativePath get() = if (isUseAndroidX) {
        "androidx/databinding/library/baseAdapters/BR.java"
    } else {
        "com/android/databinding/library/baseAdapters/BR.java"
    }
    val appBrRelativePath = "${packageName.replace(".", "/")}/BR.java"

    private val mapperDir get() = dir(tempCompileDir, "mapper")
    val dataBindingMapperIncrementalDir get() = dir(mapperDir, "inc")
    val dataBindingMapperDelegateFile get() = file(mapperDir, "DataBinderMapperImpl.java")
    val dataBindingMapperFullFile get() = file(mapperDir, "full/DataBinderMapperImpl_Full.java")

    fun reset() {
        tempCompileDir.deleteRecursively()
    }

    private fun dir(file: File, name: String): File {
        return File(file, name).also { it.mkdirs() }
    }

    private fun file(file: File, name: String): File {
        return File(file, name).also { it.parentFile.mkdirs() }
    }

    private val String.camel: String get() {
        return this.replaceFirstChar { it.uppercaseChar() }
    }
}