package com.sickworm.intellij.jugg.compiler.databinding

import android.databinding.tool.DataBindingBuilder
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.util.*

/**
 * DataBinding compiler step 2:
 * Generate XXXDataBindingImpl.java in generated/kapt/source
 * 1. annotation process
 * 2. generate Mapper proxy
 * 3. merge BR(incremental)
 *
 * References:
 * Lightning
 * - https://android.googlesource.com/platform/frameworks/data-binding/+/refs/tags/gradle_3.4.0
 * - https://android.googlesource.com/platform/tools/base/+/refs/tags/gradle_3.4.0/build-system
 *
 */
class DataBindingGenMapperCompiler(context: ICompileContext, parent: Disposable): BaseCompiler(context, parent) {

    private lateinit var argsManager: DataBindingArgsManager

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        if (context.packageName == null) {
            logger.warn("Package name not found in project, skipping databinding process")
            return CompileResult(task, emptyList(), emptyList())
        }

        argsManager = DataBindingArgsManager(context, module)
//        argsManager.reset() // TODO better way to share DataBindingGenBaseClassesCompiler output
        try {
            generateAnnotationProcessorTrigger()
            runAnnotationProcessor(task, module)
            generateIncrementalMapperHolder()
            mergeLibraryBr()
            mergeAppBr()
        } catch (e: Exception) {
            logger.debug("DataBindingGenMapperCompiler error ", e)
            logger.warn("Compile DataBinding failed: ${e.message}")
            return CompileResult(
                task,
                task.files.map { Result.failure(CompileError(it, listOf(-1L to e.message.toString()))) },
                emptyList())
        }

        return CompileResult(task, task.files.map { Result.success(it) }, emptyList())
    }

    /**
     * Create DataBindingInfo.java and DataBindingTrigger.kt
     */
    private fun generateAnnotationProcessorTrigger() {
        logger.debug("generateAnnotationProcessorTrigger trigger.")

        val triggerFile = argsManager.dataBindingKaptProcessorTrigger
        triggerFile.parentFile.mkdirs()
        val gradleFileWriter = DataBindingBuilder.GradleFileWriter(argsManager.dataBindingPreProcessorSources.absolutePath)
        val annotation = if (argsManager.isUseAndroidX) "androidx.databinding.BindingBuildInfo" else "android.databinding.BindingBuildInfo"
        val classString = StringBuilder()
            .appendLine("package ${argsManager.packageName};")
            .appendLine("@$annotation")
            .appendLine("public class DataBindingInfo {}")
        gradleFileWriter.writeToFile(argsManager.packageName + ".DataBindingInfo", classString.toString())
        if (!triggerFile.exists()) {
            throw RuntimeException("trigger file not exist: $triggerFile")
        }

        // it seems unnecessary, but just keep it.
        val ktSourceTriggerFile = argsManager.dataBindingKaptSourceTrigger
        ktSourceTriggerFile.parentFile.mkdirs()
        val content = StringBuilder()
            .appendLine("package ${argsManager.packageName}")
            .appendLine("class DataBindingIncTrigger {}")
        ktSourceTriggerFile.writeText(content.toString())
        if (!ktSourceTriggerFile.exists()) {
            throw RuntimeException("ktSourceTriggerFile file not exist: $ktSourceTriggerFile")
        }

        logger.debug("generateAnnotationProcessorTrigger end. triggerFile: $triggerFile, ktSourceTriggerFile: $ktSourceTriggerFile")
    }

    private fun createFieldsMapFromBrFile(brFile: File): MutableMap<String, String> {
        val lastFieldsMap = LinkedHashMap<String, String>()
        brFile.forEachLine {
            if (it.trim().startsWith("public static final int")) {
                val content = it.trim().replace("public static final int", "").trim().replace(";", "")
                val splits = content.split(" = ")
                lastFieldsMap[splits[0]] = splits[1]
            }
        }
        return lastFieldsMap
    }

    /**
     * Use the file with the same name in DataBinding_BR/merge as the baseline,
     * append the new constants from the newly generated BR file to the end,
     * and then replace the file under DataBinding_BR/merge.
     */
    private fun mergeLibraryBr() {
        val lastLibraryBrFile = File(argsManager.dataBindingBrMergedDir, argsManager.libraryBrRelativePath)
        val currentIncrementalLibraryBrFile = File(argsManager.dataBindingSourcesOutputDir, argsManager.libraryBrRelativePath)

        if (!lastLibraryBrFile.exists()) {
            throw RuntimeException("library br file not exist: $lastLibraryBrFile")
        }

        logger.debug("merge lib br.java: lastLibraryBrFile = $lastLibraryBrFile")
        logger.debug("merge lib br.java: currentIncrementalLibraryBrFile = $currentIncrementalLibraryBrFile")

        if (!currentIncrementalLibraryBrFile.exists()) {
            logger.debug("merge lib br.java: skip, because current has no br file.")
            return
        }

        val lastFieldsMap = createFieldsMapFromBrFile(lastLibraryBrFile)
        val currentIncrementalFieldsMap = createFieldsMapFromBrFile(currentIncrementalLibraryBrFile)

        var index = lastFieldsMap.size

        currentIncrementalFieldsMap.forEach { (key, _) ->
            if (!lastFieldsMap.containsKey(key)) {
                lastFieldsMap[key] = index++.toString()
            }
        }

        val newLibraryBrFileContent = StringBuilder()
            .append("package com.android.databinding.library.baseAdapters;\n\n")
            .append("public class BR {\n\n")
            .apply {
                lastFieldsMap.forEach { (key, value) ->
                    append("public static final int $key = $value;\n\n")
                }
            }
            .append("}")

        var targetFile = currentIncrementalLibraryBrFile
        if (targetFile.exists()) {
            targetFile.delete()
        }

        targetFile.writer().use {
            it.write(newLibraryBrFileContent.toString())
        }

        if (!targetFile.exists()) {
            throw RuntimeException("library br file not exist: $targetFile")
        }

        targetFile = lastLibraryBrFile
        if (targetFile.exists()) {
            targetFile.delete()
        }

        targetFile.writer().use {
            it.write(newLibraryBrFileContent.toString())
        }
    }

    private fun mergeAppBr() {
        val lastLibraryBrFile = File(argsManager.dataBindingBrMergedDir, argsManager.appBrRelativePath)
        val currentIncrementalLibraryBrFile = File(argsManager.dataBindingSourcesOutputDir, argsManager.appBrRelativePath)

        if (!lastLibraryBrFile.exists()) {
            throw RuntimeException("library br file not exist: $lastLibraryBrFile")
        }

        logger.debug("merge app br.java: lastLibraryBrFile = $lastLibraryBrFile")
        logger.debug("merge app br.java: currentIncrementalLibraryBrFile = $currentIncrementalLibraryBrFile")

        if (!currentIncrementalLibraryBrFile.exists()) {
            logger.debug("merge app br.java: skip, because current has no br file.")
            return
        }

        val lastFieldsMap = createFieldsMapFromBrFile(lastLibraryBrFile)
        val currentIncrementalFieldsMap = createFieldsMapFromBrFile(currentIncrementalLibraryBrFile)

        var index = lastFieldsMap.size

        currentIncrementalFieldsMap.forEach { (key, _) ->
            if (!lastFieldsMap.containsKey(key)) {
                lastFieldsMap[key] = index++.toString()
            }
        }

        val newLibraryBrFileContent = StringBuilder()
            .append("package ${argsManager.packageName};\n\n")
            .append("public class BR {\n\n")
            .apply {
                lastFieldsMap.forEach { (key, value) ->
                    append("public static final int $key = $value;\n\n")
                }
            }
            .append("}")

        var targetFile = currentIncrementalLibraryBrFile
        if (targetFile.exists()) {
            targetFile.delete()
        }

        targetFile.writer().use {
            it.write(newLibraryBrFileContent.toString())
        }

        targetFile = lastLibraryBrFile
        if (targetFile.exists()) {
            targetFile.delete()
        }

        targetFile.writer().use {
            it.write(newLibraryBrFileContent.toString())
        }
    }

    /**
     * Get current incremental mapper, copy to the inc directory and rename it to DataBinderMapper_Inc_N.java.
     * According to the contents of all mapper/inc directories, generate a new DataBinderMapperIncrementalHolder.java
     * to the current source directory.
     * For this incremental build, delete the Mapper in the current source directory and generate a new Mapper proxy class.
     */
    private fun generateIncrementalMapperHolder() {
        val currentDataBinderMapperImplFile = File(argsManager.dataBindingSourcesOutputDir,
            "${argsManager.packageName.replace(".", File.separator)}${File.separator}DataBinderMapperImpl.java")
        if (!currentDataBinderMapperImplFile.exists()) {
            return
        }

        logger.debug("generateIncrementalMapperHolder currentDataBinderMapperImplFile = $currentDataBinderMapperImplFile")

        val incDir = argsManager.dataBindingMapperIncrementalDir
        var index = 1
        if (incDir.exists()) {
            index = incDir.listFiles()?.size ?: 0
            if (index == 0) {
                index = 1
            } else {
                index += 1
            }
        }

        logger.debug("generateIncrementalMapperHolder currentDataBinderMapperImplFile = $currentDataBinderMapperImplFile")

        val newName = "DataBinderMapperImpl_Inc_$index"
        logger.debug("generateIncrementalMapperHolder newName = $newName")

        val targetFile = File(incDir, "$newName.java")
        val content = currentDataBinderMapperImplFile.readText().replaceFirst("DataBinderMapperImpl", newName)
        if (targetFile.exists()) {
            targetFile.delete()
        }

        if (!targetFile.parentFile.exists()) {
            targetFile.parentFile.mkdirs()
        }

        targetFile.writer().use {
            it.write(content)
        }

        val targetFile2 = File(currentDataBinderMapperImplFile.parentFile, targetFile.name)
        targetFile.copyTo(targetFile2)

        currentDataBinderMapperImplFile.delete()

        val holderTemplate = if (argsManager.isUseAndroidX) {
            """
                package _package_name_holder_;
                
                import androidx.databinding.DataBinderMapper;
                
                public class DataBinderMapper_IncrementalHolder {
                    public static DataBinderMapper[] get() {
                        return new DataBinderMapper[] {
                            _inc_mapper_array_holder_
                        };
                    }
                }
            """
        } else {
            """
                package _package_name_holder_;
                
                import android.databinding.DataBinderMapper;
                
                public class DataBinderMapper_IncrementalHolder {
                    public static DataBinderMapper[] get() {
                        return new DataBinderMapper[] {
                            _inc_mapper_array_holder_
                        };
                    }
                }
            """
        }

        val allIncMapperFiles = incDir.listFiles()
        allIncMapperFiles?.sortWith { o1, o2 ->
            val index1 = o1.name.replace("DataBinderMapperImpl_Inc_", "").replace(".java", "").toInt()
            val index2 = o2.name.replace("DataBinderMapperImpl_Inc_", "").replace(".java", "").toInt()
            index2 - index1
        }

        val incMapperArrays = StringBuilder()
        allIncMapperFiles?.forEach {
            incMapperArrays.append("\n                                new ${argsManager.packageName}.${it.name.replace(".java", "")}(),")
        }

        val holderContent = holderTemplate
            .replace("_package_name_holder_", argsManager.packageName)
            .replace("_inc_mapper_array_holder_", incMapperArrays.toString())

        val allIncMapperHolderJavaFile = File(currentDataBinderMapperImplFile.parentFile, "DataBinderMapper_IncrementalHolder.java")
        if (allIncMapperHolderJavaFile.exists()) {
            allIncMapperHolderJavaFile.delete()
        }

        allIncMapperHolderJavaFile.writer().use {
            it.write(holderContent)
        }

        if (!allIncMapperHolderJavaFile.exists()) {
            throw RuntimeException("error to create DataBinderMapperIncrementalHolder : $allIncMapperHolderJavaFile")
        }

        val delegateMapperFile = argsManager.dataBindingMapperDelegateFile
        if (delegateMapperFile.exists()) {
            logger.debug("delegate file already exist, skip")
            return
        }

        val delegateMapperContentTemplateNormal = """
            package _package_name_holder_;

            import android.databinding.DataBinderMapper;
            import android.databinding.DataBindingComponent;
            import android.databinding.ViewDataBinding;
            import android.view.View;
            import java.lang.Override;
            import java.lang.String;
            import java.util.List;

            public class DataBinderMapperImpl extends DataBinderMapper {
                private final _package_name_holder_.DataBinderMapperImpl_Full origin = new _package_name_holder_.DataBinderMapperImpl_Full();
                private final DataBinderMapper[] incDataBinderMapperArray = DataBinderMapper_IncrementalHolder.get();

                @Override
                public ViewDataBinding getDataBinder(DataBindingComponent component, View view, int layoutId) {
                    if (incDataBinderMapperArray.length > 0) {
                        for (DataBinderMapper inc: incDataBinderMapperArray) {
                            ViewDataBinding viewDataBinding = inc.getDataBinder(component, view, layoutId);
                            if (viewDataBinding != null) {
                                return viewDataBinding;
                            }
                        }
                    }
                    return origin.getDataBinder(component, view, layoutId);
                }

                @Override
                public ViewDataBinding getDataBinder(DataBindingComponent component, View[] views, int layoutId) {
                    if (incDataBinderMapperArray.length > 0) {
                        for (DataBinderMapper inc: incDataBinderMapperArray) {
                            ViewDataBinding viewDataBinding = inc.getDataBinder(component, views, layoutId);
                            if (viewDataBinding != null) {
                                return viewDataBinding;
                            }
                        }
                    }
                    return origin.getDataBinder(component, views, layoutId);
                }

                @Override
                public int getLayoutId(String tag) {
                    if (incDataBinderMapperArray.length > 0) {
                        for (DataBinderMapper inc: incDataBinderMapperArray) {
                            int layoutId = inc.getLayoutId(tag);
                            if (layoutId != 0) {
                                return layoutId;
                            }
                        }
                    }
                    return origin.getLayoutId(tag);
                }

                @Override
                public String convertBrIdToString(int localId) {
                    if (incDataBinderMapperArray.length > 0) {
                        for (DataBinderMapper inc: incDataBinderMapperArray) {
                            String str = inc.convertBrIdToString(localId);
                            if (str != null) {
                                return str;
                            }
                        }
                    }
                    return origin.convertBrIdToString(localId);
                }

                @Override
                public List<DataBinderMapper> collectDependencies() {
                    if (incDataBinderMapperArray.length > 0) {
                        for (DataBinderMapper inc: incDataBinderMapperArray) {
                            List<DataBinderMapper> list = inc.collectDependencies();
                            if (list != null) {
                                return list;
                            }
                        }
                    }
                    return origin.collectDependencies();
                }
            }
        """

        val delegateMapperContentTemplateAndroidX = """
            package _package_name_holder_;

            import androidx.databinding.DataBinderMapper;
            import androidx.databinding.DataBindingComponent;
            import androidx.databinding.ViewDataBinding;
            import android.view.View;
            import java.lang.Override;
            import java.lang.String;
            import java.util.List;

            public class DataBinderMapperImpl extends DataBinderMapper {
                private final _package_name_holder_.DataBinderMapperImpl_Full origin = new _package_name_holder_.DataBinderMapperImpl_Full();
                private final DataBinderMapper[] incDataBinderMapperArray = DataBinderMapper_IncrementalHolder.get();

                @Override
                public ViewDataBinding getDataBinder(DataBindingComponent component, View view, int layoutId) {
                    if (incDataBinderMapperArray.length > 0) {
                        for (DataBinderMapper inc: incDataBinderMapperArray) {
                            ViewDataBinding viewDataBinding = inc.getDataBinder(component, view, layoutId);
                            if (viewDataBinding != null) {
                                return viewDataBinding;
                            }
                        }
                    }
                    return origin.getDataBinder(component, view, layoutId);
                }

                @Override
                public ViewDataBinding getDataBinder(DataBindingComponent component, View[] views, int layoutId) {
                    if (incDataBinderMapperArray.length > 0) {
                        for (DataBinderMapper inc: incDataBinderMapperArray) {
                            ViewDataBinding viewDataBinding = inc.getDataBinder(component, views, layoutId);
                            if (viewDataBinding != null) {
                                return viewDataBinding;
                            }
                        }
                    }
                    return origin.getDataBinder(component, views, layoutId);
                }

                @Override
                public int getLayoutId(String tag) {
                    if (incDataBinderMapperArray.length > 0) {
                        for (DataBinderMapper inc: incDataBinderMapperArray) {
                            int layoutId = inc.getLayoutId(tag);
                            if (layoutId != 0) {
                                return layoutId;
                            }
                        }
                    }
                    return origin.getLayoutId(tag);
                }

                @Override
                public String convertBrIdToString(int localId) {
                    if (incDataBinderMapperArray.length > 0) {
                        for (DataBinderMapper inc: incDataBinderMapperArray) {
                            String str = inc.convertBrIdToString(localId);
                            if (str != null) {
                                return str;
                            }
                        }
                    }
                    return origin.convertBrIdToString(localId);
                }

                @Override
                public List<DataBinderMapper> collectDependencies() {
                    if (incDataBinderMapperArray.length > 0) {
                        for (DataBinderMapper inc: incDataBinderMapperArray) {
                            List<DataBinderMapper> list = inc.collectDependencies();
                            if (list != null) {
                                return list;
                            }
                        }
                    }
                    return origin.collectDependencies();
                }
            }
        """

        val delegateMapperContentTemplate = if (argsManager.isUseAndroidX) {
            delegateMapperContentTemplateAndroidX
        } else {
            delegateMapperContentTemplateNormal
        }

        val delegateMapperContent = delegateMapperContentTemplate.replace("_package_name_holder_", argsManager.packageName)

        if (delegateMapperFile.exists()) {
            delegateMapperFile.delete()
        }

        delegateMapperFile.writer().use {
            it.write(delegateMapperContent)
        }

        if (!delegateMapperFile.exists()) {
            throw RuntimeException("error to create DataBinderMapper Delegate : $delegateMapperFile")
        }

        val targetDelegateMapperFile = File(currentDataBinderMapperImplFile.parentFile, delegateMapperFile.name)
        delegateMapperFile.copyTo(targetDelegateMapperFile)

        if (!targetDelegateMapperFile.exists()) {
            throw RuntimeException("Failed to copy file : $targetDelegateMapperFile")
        }

        val fullMapperFile = argsManager.dataBindingMapperFullFile
        if (!fullMapperFile.exists()) {
            throw RuntimeException("Full mapper file not exist ! , which should be exist under : $fullMapperFile")
        }

        val targetFullMapperFile = File(currentDataBinderMapperImplFile.parentFile, fullMapperFile.name)
        fullMapperFile.copyTo(targetFullMapperFile)
    }

    /**
     * Prepare annotation processor options.
     */
    private fun prepareAnnotationProcessorOptions(module: ModuleInfo): Array<String> {
        val minSDkVersion = module.minSdkVersion

        val classLogDir = argsManager.dataBindingArtifactFolder.path
        val aarOutDir = argsManager.dataBindingAarOutDir.path
        val enableDebugLogs = "1"
        val dependencyArtifactsDir = argsManager.dataBindingDependencyArtifacts
        val sdkDir = context.androidHome.path
        val enableForTests = "0"
        val enableV2 = "1"
        val modulePackage = argsManager.packageName
        val artifactType = "APPLICATION"
        val isTestVariant = "0"
        val baseFeatureInfoDir = argsManager.dataBindingBaseFeatureInfoDir
        val printEncodedErrorLogs = "1"
        val layoutInfoDir = argsManager.dataBindingLayoutXmlDir

        return arrayOf(
            "android.databinding.minApi=$minSDkVersion",
            "android.databinding.classLogDir=$classLogDir",
            "android.databinding.aarOutDir=$aarOutDir",
            "android.databinding.enableDebugLogs=$enableDebugLogs",
            "android.databinding.dependencyArtifactsDir=$dependencyArtifactsDir",
            "android.databinding.sdkDir=$sdkDir",
            "android.databinding.enableForTests=$enableForTests",
            "android.databinding.enableV2=$enableV2",
            "android.databinding.modulePackage=$modulePackage",
            "android.databinding.artifactType=$artifactType",
            "android.databinding.isTestVariant=$isTestVariant",
            "android.databinding.baseFeatureInfoDir=$baseFeatureInfoDir",
            "android.databinding.printEncodedErrorLogs=$printEncodedErrorLogs",
            "android.databinding.layoutInfoDir=$layoutInfoDir",
            "useAndroidX=true"
        )
    }

    /**
     * Run kapt，generate DataBindingImpl.java、BR.java、DataMapping.
     */
    private fun runAnnotationProcessor(task: CompileTask, module: ModuleInfo) {
        logger.debug("launching annotation processor ...")

        val kotlinAptPluginClassPath: String? = module.kotlinPlugins?.find {
            it.path.contains("kotlin-annotation-processing-gradle")
        }?.path
        if (kotlinAptPluginClassPath == null) {
            throw RuntimeException("Unable to find kotlin-annotation-processing-gradle.jar in project, please report to the admin.")
        }
        logger.debug("kotlinAptPluginClassPath: $kotlinAptPluginClassPath")

        val source = mutableListOf<File>()
        source.add(argsManager.dataBindingKaptProcessorTrigger)
        source.add(argsManager.dataBindingKaptSourceTrigger)
        source.addAll(task.files.map { it.file})
        // TODO need?
//        argsManager.currentIncrementalRJavaDir.listFilesRecursively().forEach {
//            if (it.isFile && it.name.endsWith(".java")) {
//                source.add(it)
//            }
//        }
        logger.debug("source : $source")

        val classpath = context.getModuleDependencies(module, task)

        val annotationProcessorsClassPaths = module.annotationProcessorDependencies

        logger.debug("classpath each:")
        classpath.forEach {
            logger.debug("   -- $it")
        }

        logger.debug("annotationProcessorsClassPaths each:")
        annotationProcessorsClassPaths.forEach {
            logger.debug("   -- $it")
        }

        val apOptionArray = prepareAnnotationProcessorOptions(module)
        logger.debug("apOptionArray each:")
        apOptionArray.forEach {
            logger.debug("   -- $it")
        }

        val apOptions = ArrayList(apOptionArray.toList())

        // kapt compile
        // TODO what to compile?
    }

}