package com.sickworm.intellij.jugg.compiler.databinding

import com.sickworm.intellij.jugg.compiler.CompilerUtils
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Manage argument for data binding.
 * Output structure is same as AGP 7.2.2.
 */
class DataBindingArgsManager(private val context: ICompileContext, private val moduleInfo: ModuleInfo) {

    val isJava = false
    val isUseAndroidX = true // just leave it true
    val isUseViewBinding = isUseViewBinding(moduleInfo)
    val isUseDataBinding = isUseDataBinding(moduleInfo)
    val isIncremental = false // we do incremental by our own way
    val packageName get() = context.getModulePackageName(moduleInfo) ?: ""
    private val packagePath get() = packageName.replace(".", "/")

    private val tempCompileDir = context.tempCompileDir

    /** ViewBinding start */
    // generate ViewBinding things e.g. ActivityMainBinding.java
    val dataBindingSourcesOutputDir get() = dir(tempCompileDir, "generated/data_binding_base_class_source_out/${moduleInfo.buildVariant}/out")
    val dataBindingStrippedXmlDir get() = dir(tempCompileDir, "intermediates/incremental/${moduleInfo.buildVariant}/merge${moduleInfo.buildVariant.camel}/stripped.dir")
    val artifactFolder get() = dir(tempCompileDir, "intermediates/data_binding_base_class_log_artifact/${moduleInfo.buildVariant}/out") // AGP 8.4 will get intermediates/data_binding_base_class_log_artifact/${moduleInfo.buildVariant}/dataBindingGenBaseClasses${moduleInfo.buildVariant.camel}/out
    val v1ArtifactsFolder get() = dir(tempCompileDir, "intermediates/data_binding_dependency_artifacts/${moduleInfo.buildVariant}")
    val blameLogDir = File(tempCompileDir, "intermediates/merged_res_blame_folder/${moduleInfo.buildVariant}/out")
    val logFolder get() = dir(tempCompileDir, "intermediates/incremental/dataBindingGenBaseClasses${moduleInfo.buildVariant.camel}")
    val dependencyClassesFolders: List<File> = listOf(
        incrementalDependencyClassesFolder, // incremental info
        moduleInfo.buildPathInfo.dataBindingInfoDir.resolve("out"), // AGP 7.2.2
        moduleInfo.buildPathInfo.dataBindingInfoDir.resolve("dataBindingGenBaseClasses${moduleInfo.buildVariant.camel}/out"), // AGP 8.4
        moduleInfo.buildPathInfo.dataBindingDependencyInfoDir,
    ).filter { it.exists() }
    val tempDataBindingLayoutXmlDir: File = dir(tempCompileDir, "intermediates/data_binding_layout_info_type_merge/${moduleInfo.buildVariant}/out")

    val incrementalDependencyClassesFolder get() = dir(context.tempModule.buildPathInfo.buildDir,
        moduleInfo.buildPathInfo.dataBindingInfoDir.resolve("out").relativeTo(moduleInfo.buildPathInfo.buildDir).path,
    )
    val incrementalBaseClassOutDir get() = dir(context.tempModule.buildPathInfo.buildDir,
        dataBindingSourcesOutputDir.relativeTo(tempCompileDir).path
    )
    /** ViewBinding end */


    /** DataBinding start */
    // generate DataBinding things e.g. DataBinderMapperImpl.java
    val dataBindingDependencyArtifacts get() = dir(tempCompileDir, "other/kapt/dependency_artifacts") // v1ArtifactsFolder? set the same will override and compile failed
    val dataBindingArtifactFolder get() = dir(tempCompileDir, "other/kapt/base_class_log_artifact") // artifactFolder? set the same will override and compile failed
    val dataBindingAarOutDir get() = dir(tempCompileDir, "intermediates/data_binding_artifact/${moduleInfo.buildVariant}/kapt${moduleInfo.buildVariant.camel}Kotlin") // output setter_store.json
    val dataBindingBaseFeatureInfoDir get() = dir(tempCompileDir, "intermediates/base_feature_info") // no output
    val dataBindingKaptOutputDir get() = "other/kapt/output"

    // br
    private val libraryBrRelativePath get() = if (isUseAndroidX) {
        "androidx/databinding/library/baseAdapters/BR.java"
    } else {
        "com/android/databinding/library/baseAdapters/BR.java"
    }
    private val appBrRelativePath = "${packageName.replace(".", "/")}/BR.java"
    val currentIncrementalLibraryBrFile = File(dataBindingSourcesOutputDir, libraryBrRelativePath)
    val currentIncrementalAppBrFile = File(dataBindingSourcesOutputDir, appBrRelativePath)

    // trigger file
    val dataBindingPreProcessorSources get() = dir(tempCompileDir, "other/data_binding_trigger/${moduleInfo.buildVariant}")
    val dataBindingKaptProcessorTrigger get() = file(dataBindingPreProcessorSources, "$packagePath/DataBindingInfo.java")
    val dataBindingKaptSourceTrigger get() = file(dataBindingPreProcessorSources, "$packagePath/DataBindingTrigger.kt")

    // mapper
    val dataBindingMapperRelativePath = "$packagePath/DataBinderMapperImpl.java"
    val mapperDir = dir(tempCompileDir, "other/mapper")
    val dataBindingMapperDelegateFile get() = file(mapperDir, "DataBinderMapperImpl.java")
    val dataBindingMapperFullFile get() = file(mapperDir, "DataBinderMapperImpl_Full.java")
    val databindingIncCount get() = context.deployedFiles.count {
        val isIncMapper = it.file.nameWithoutExtension.startsWith("DataBinderMapperImpl_Inc_")
        if (!isIncMapper) return@count false
        val isMyPackage = it.relativeFile.parentFile.path.replace("\\", "/") == packagePath
        return@count isMyPackage
    }
    /** databinding end */



    /** gradle intermediates dir start */
    // need to reuse this dir to compat with <include> in data binding
    private val gradleKaptOutputDir = File(moduleInfo.buildPathInfo.buildDir, "generated/source/kapt/${moduleInfo.buildVariant}")
    val gradleDataBindingLayoutXmlDir: File = CompilerUtils.matchGradleDir(listOf(
        // AGP 7.2.2
        File(moduleInfo.buildPathInfo.dataBindingIntoTypeDir, "out"),
        // AGP 8.4 has both data_binding_layout_info_type_package and data_binding_layout_info_type_merge, seems the same
        File(moduleInfo.buildPathInfo.dataBindingIntoTypeDir, "package${moduleInfo.buildVariant.camel}Resources/out"),
    ),
        default = tempDataBindingLayoutXmlDir,
    )
    val gradleMapperFile = File(gradleKaptOutputDir, dataBindingMapperRelativePath)
    val gradleLibraryBrFile = File(gradleKaptOutputDir, libraryBrRelativePath)
    val gradleAppBrFile = File(gradleKaptOutputDir, appBrRelativePath)
    /** gradle intermediates dir end */

    fun reset() {
        tempCompileDir.deleteRecursively()
    }

    companion object {

        private fun dir(file: File, name: String): File {
            return File(file, name).also { it.mkdirs() }
        }

        private fun file(file: File, name: String): File {
            return File(file, name).also { it.parentFile.mkdirs() }
        }

        private val String.camel: String get() {
            return this.replaceFirstChar { it.uppercaseChar() }
        }

        fun isUseViewBinding(moduleInfo: ModuleInfo): Boolean {
            if (moduleInfo.isUseViewBinding == true) return true
            val gradleViewBindingOutputDir = File(moduleInfo.buildPathInfo.buildDir, "generated/data_binding_base_class_source_out")
            val isHasViewBinding = gradleViewBindingOutputDir.exists()
            return isHasViewBinding
        }

        fun isUseDataBinding(moduleInfo: ModuleInfo, xmlFile: List<File>? = null): Boolean {
            if (moduleInfo.isUseDataBinding == true) return true

            val gradleKaptOutputDir = File(moduleInfo.buildPathInfo.buildDir, "generated/source/kapt/${moduleInfo.buildVariant}")
            val gradleDataBindingOutputGuessDir = File(gradleKaptOutputDir, "androidx/databinding")
            val isHasDataBindingOutput = gradleDataBindingOutputGuessDir.exists()
            if (!isHasDataBindingOutput) return false

            if (xmlFile.isNullOrEmpty()) {
                return true
            }

            return xmlFile.any(::guessXmlFileHasDataBinding)
        }

        private fun guessXmlFileHasDataBinding(xmlFile: File): Boolean {
            // simple guess
            if (!xmlFile.exists()) return false
            return xmlFile.readText().contains("<layout")
        }
    }
}