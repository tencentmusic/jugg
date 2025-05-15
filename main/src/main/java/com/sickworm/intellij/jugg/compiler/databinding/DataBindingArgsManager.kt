package com.sickworm.intellij.jugg.compiler.databinding

import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Manage argument for data binding.
 * Output structure is same as AGP 7.2.2.
 */
class DataBindingArgsManager(private val context: ICompileContext, private val moduleInfo: ModuleInfo) {

    val isUseAndroidX = true // just leave it true
    val isUseViewBinding = isUseViewBinding(moduleInfo)
    val isUseDataBinding = isUseDataBinding(moduleInfo)
    val isIncremental = false // we do incremental by our own way
    val packageName get() = context.getModulePackageName(moduleInfo) ?: ""

    val tempCompileDir = context.tempCompileDir

    // generate ViewBinding things e.g. ActivityMainBinding.java
    val dataBindingSourcesOutputDir get() = dir(tempCompileDir, "generated/data_binding_base_class_source_out/${moduleInfo.buildVariant}/out")
    val dataBindingLayoutXmlDir get() = dir(tempCompileDir, "intermediates/data_binding_layout_info_type_package/${moduleInfo.buildVariant}/out") // AGP 8.6 has both data_binding_layout_info_type_package and data_binding_layout_info_type_merge (seems the same)
    val dataBindingStrippedXmlDir get() = dir(tempCompileDir, "intermediates/incremental/${moduleInfo.buildVariant}/merge${moduleInfo.buildVariant.camel}/stripped.dir")
    val artifactFolder get() = dir(tempCompileDir, "intermediates/data_binding_base_class_log_artifact/${moduleInfo.buildVariant}/out") // AGP 8.6 will get intermediates/data_binding_base_class_log_artifact/${moduleInfo.buildVariant}/dataBindingGenBaseClasses${moduleInfo.buildVariant.camel}/out
    val v1ArtifactsFolder get() = dir(tempCompileDir, "intermediates/data_binding_dependency_artifacts/${moduleInfo.buildVariant}")
    val blameLogDir = File(tempCompileDir, "intermediates/merged_res_blame_folder/${moduleInfo.buildVariant}/out")
    val logFolder get() = dir(tempCompileDir, "intermediates/incremental/dataBindingGenBaseClasses${moduleInfo.buildVariant.camel}")
    val dependencyClassesFolders: List<File> = listOf(
        moduleInfo.buildPathInfo.dataBindingInfoDir.resolve("out"),
        moduleInfo.buildPathInfo.dataBindingInfoDir.resolve("dataBindingGenBaseClasses${moduleInfo.buildVariant.camel}/out"),
        moduleInfo.buildPathInfo.dataBindingDependencyInfoDir,
    ).filter { it.exists() }

    // generate DataBinding things e.g. DataBinderMapperImpl.java
    val dataBindingDependencyArtifacts get() = dir(tempCompileDir, "dependency_artifacts")
    val dataBindingArtifactFolder get() = dir(tempCompileDir, "base_class_log_artifact")
    val dataBindingPreProcessorSources get() = dir(tempCompileDir, "data_binding_trigger/${moduleInfo.buildVariant}")
    val dataBindingKaptProcessorTrigger get() = file(dataBindingPreProcessorSources, packageName.replace(".", "/") + "/DataBindingInfo.java")
    val dataBindingKaptSourceTrigger get() = file(dataBindingPreProcessorSources, packageName.replace(".", "/") + "/DataBindingTrigger.kt")
    val dataBindingAarOutDir get() = dir(tempCompileDir, "bundle-bin")
    val dataBindingBaseFeatureInfoDir get() = dir(tempCompileDir, "base_feature_info")
    val dataBindingKaptTempDir get() = "other/kapt_output"

    val gradleDataBindingKaptOutputDir get() = File(moduleInfo.buildPathInfo.buildDir, "generated/source/kapt/${moduleInfo.buildVariant}")
    val libraryBrRelativePath get() = if (isUseAndroidX) {
        "androidx/databinding/library/baseAdapters/BR.java"
    } else {
        "com/android/databinding/library/baseAdapters/BR.java"
    }
    val appBrRelativePath = "${packageName.replace(".", "/")}/BR.java"

    // custom incremental mapper things
    private val mapperDir get() = dir(tempCompileDir, "mapper")
    val dataBindingMapperIncrementalDir get() = dir(mapperDir, "inc")
    val dataBindingMapperDelegateFile get() = file(mapperDir, "DataBinderMapperImpl.java")
    val dataBindingMapperFullFile get() = file(mapperDir, "full/DataBinderMapperImpl_Full.java")
    val dataBindingMapperRelativePath get() = packageName.replace(".", "/") + "/DataBinderMapperImpl.java"

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

    companion object {

        fun isUseViewBinding(moduleInfo: ModuleInfo): Boolean {
            if (moduleInfo.isUseViewBinding == true) return true
            val gradleViewBindingOutputDir = File(moduleInfo.buildPathInfo.buildDir, "generated/data_binding_base_class_source_out")
            val isHasViewBinding = gradleViewBindingOutputDir.exists()
            return isHasViewBinding
        }

        fun isUseDataBinding(moduleInfo: ModuleInfo, xmlFile: File? = null): Boolean {
            if (moduleInfo.isUseDataBinding == true) return true

            val gradleKaptOutputDir = File(moduleInfo.buildPathInfo.buildDir, "generated/source/kapt/${moduleInfo.buildVariant}")
            val gradleDataBindingOutputGuessDir = File(gradleKaptOutputDir, "androidx/databinding")
            val isHasDataBindingOutput = gradleDataBindingOutputGuessDir.exists()
            if (!isHasDataBindingOutput) return false

            if (xmlFile == null) {
                return true
            }

            return guessXmlFileHasDataBinding(xmlFile)
        }

        private fun guessXmlFileHasDataBinding(xmlFile: File): Boolean {
            // just do a simple guess
            if (xmlFile.exists()) return false
            val hasLayoutTag = xmlFile.readText().contains("<layout") || xmlFile.readText().contains("<Layout")
            return hasLayoutTag
        }
    }
}