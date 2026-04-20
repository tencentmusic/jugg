package com.sickworm.intellij.jugg.compiler.databinding

import com.sickworm.intellij.jugg.compiler.CompilerUtils
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Manage argument for data binding.
 * Output structure is same as AGP 7.2.2.
 */
class DataBindingArgsManager(val context: ICompileContext, val moduleInfo: ModuleInfo) {

    val isFallbackApt = isKaAptRetryAptSuccess || isLastFallbackAptFailed
    var isJava = isFallbackApt || !isUseKaptForDataBinding(moduleInfo)

    val isUseAndroidX = true // just leave it true
    val isUseViewBinding = isUseViewBinding(moduleInfo)
    val isUseDataBinding = isUseDataBinding(moduleInfo)
    val isIncremental = false // we do incremental by our own way
    val packageName get() = context.getModulePackageName(moduleInfo) ?: ""
    private val packagePath get() = packageName.replace(".", "/")

    private val tempCompileDir = run {
        val relativePath = moduleInfo.moduleRootDir.relativeTo(moduleInfo.projectRootDir).path.replace("..", "__")
        File(context.tempModule.buildPathInfo.buildDir, "data_binding/$relativePath")
    }

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
    val tempDataBindingLayoutXmlDir: File get() = dir(tempCompileDir, "intermediates/data_binding_layout_info_type_merge/${moduleInfo.buildVariant}/out")

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
    val dataBindingExportClassListOutFile get() = file(tempCompileDir, "intermediates/data_binding_export_class_list/${moduleInfo.buildVariant}/kapt${moduleInfo.buildVariant.camel}Kotlin")
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
    val dataBindingAptSourceTrigger get() = file(dataBindingPreProcessorSources, "$packagePath/DataBindingInfo.java")
    val dataBindingKaptSourceTrigger get() = file(dataBindingPreProcessorSources, "$packagePath/DataBindingInfo.kt")

    // mapper
    val dataBindingMapperRelativePath = "$packagePath/DataBinderMapperImpl.java"
    val mapperDir get() = dir(tempCompileDir, "other/mapper")
    val dataBindingMapperDelegateFile get() = file(mapperDir, "DataBinderMapperImpl.java")
    val dataBindingMapperFullFile get() = file(mapperDir, "DataBinderMapperImpl_Full.java")
    val databindingIncCount get() = context.deployedFiles.count {
        val isIncMapper = it.file.nameWithoutExtension.startsWith("DataBinderMapperImpl_Inc_")
                && it.file.extension == "dex"
                && !it.file.nameWithoutExtension.contains("$") // not inner class
        if (!isIncMapper) return@count false
        val isMyPackage = it.relativeFile.parentFile.path.replace("\\", "/") == packagePath
        return@count isMyPackage
    }
    /** databinding end */



    /** gradle intermediates dir start */
    // need to reuse this dir to compat with <include> in data binding
    private val gradleKaptOutputDir = File(moduleInfo.buildPathInfo.buildDir, "generated/source/kapt/${moduleInfo.buildVariant}")
    val gradleDataBindingLayoutXmlDir: File = CompilerUtils.matchGradleDir(listOf(
        // AGP 7.2.2 application module
        File(moduleInfo.buildPathInfo.applicationDataBindingIntoTypeDir, "out"),
        // AGP 8.4 application module, has both data_binding_layout_info_type_package and data_binding_layout_info_type_merge, seems the same
        File(moduleInfo.buildPathInfo.applicationDataBindingIntoTypeDir, "package${moduleInfo.buildVariant.camel}Resources/out"),
        // AGP 7.2.2 library module
        File(moduleInfo.buildPathInfo.libraryDataBindingIntoTypeDir, "out"),
        // AGP 8.4 library module
        File(moduleInfo.buildPathInfo.libraryDataBindingIntoTypeDir, "package${moduleInfo.buildVariant.camel}Resources/out"),
    ),
        default = tempDataBindingLayoutXmlDir,
    )
    val gradleMapperFile = File(gradleKaptOutputDir, dataBindingMapperRelativePath)
    val gradleLibraryBrFile = File(gradleKaptOutputDir, libraryBrRelativePath)
    val gradleAppBrFile = File(gradleKaptOutputDir, appBrRelativePath)
    // backup dir to avoid gradle compilation failed if file create and delete
    val backupDataBindingLayoutXmlDir = context.backupGradleDir(gradleDataBindingLayoutXmlDir, dryRun = true)
    /** gradle intermediates dir end */

    fun reset() {
        tempCompileDir.deleteRecursively()
        context.backupGradleDir(gradleDataBindingLayoutXmlDir) // to backupDataBindingLayoutXmlDir
    }

    companion object {

        /**
         * True when kapt failed and the subsequent apt fallback succeeded.
         * Used to decide `isJava` for newly created [DataBindingArgsManager] instances.
         */
        var isKaAptRetryAptSuccess = false
            get() {
                if (isForceUseAptInTest != null) {
                    return isForceUseAptInTest!!
                }
                return field
            }

        /**
         * True when kapt failed AND the subsequent apt fallback also failed.
         * Set in the catch block of the fallback apt call, then the exception is rethrown.
         * Used by [com.sickworm.intellij.jugg.compiler.source.DataBindingAptRetryStrategy]
         * to decide whether retry logic should be active.
         */
        var isLastFallbackAptFailed = false
            get() {
                if (isForceUseAptInTest != null) {
                    return isForceUseAptInTest!!
                }
                return field
            }

        // test only
        var isForceUseAptInTest: Boolean? = null

        private val dataBindingKaptDependencyHints = listOf(
            "databinding-compiler",
            "databinding-compiler-common",
            "databinding-common",
        )

        fun isUseKaptForDataBinding(moduleInfo: ModuleInfo): Boolean {
            // use apt if databinding not in kapt deps, otherwise kapt
            return moduleInfo.kaptDependencies.any { dependency ->
                dataBindingKaptDependencyHints.any { hint -> dependency.file.path.contains(hint) }
            }
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

    fun isTriggerFile(file: File): Boolean {
        if (file == dataBindingKaptSourceTrigger) {
            return true
        }
        if (file == dataBindingAptSourceTrigger) {
            return true
        }
        return false
    }
}
