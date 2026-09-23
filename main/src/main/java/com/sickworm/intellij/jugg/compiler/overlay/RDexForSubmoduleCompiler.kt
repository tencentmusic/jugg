package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.deploy.packageNameToPath
import com.sickworm.intellij.jugg.JuggException
import java.io.File

/**
 * Generate R.dex for different module.
 * In Jugg, we only generate one final R.dex for main module, for other submodules, we generate
 * R.dex for each module.
 */
class RDexForSubmoduleCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes: List<CompileFile.Type>
        get() = listOf(CompileFile.Type.DexToChangePackageName, CompileFile.Type.Java, CompileFile.Type.Kotlin, CompileFile.Type.Resource)

    // we don't persist generated modules, it's ok to generate once for each module
    private var generatedModules = mutableSetOf<String>()

    override fun doCompile(task: CompileTask): CompileResult {
        // if input has files, which means R file is update. we need to generate all R.dex for all module
        // if input has no files, we only generate R.dex once
        val isRFileUpdated = task.files.any { it.type == CompileFile.Type.DexToChangePackageName }
        if (isRFileUpdated) {
            logger.debug("R file has update, going to regenerate R.dex for all submodules")
            generatedModules.clear()
        }
        return super.doCompile(task)
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val rDexOutputDir = File(task.outputDir, context.packageName!!.packageNameToPath)
        val rDexOutputFile = File(rDexOutputDir, "R.dex")
        if (module == context.tempModule) {
            return doTempModuleCompile(task, module, rDexOutputDir, rDexOutputFile)
        }
        val isNeedGenerate = rDexOutputFile.exists() && !generatedModules.contains(module.name)
        if (!isNeedGenerate) {
            logger.debug("Module ${module.name} has no R file update, skip generate submodule R.dex")
            return CompileResult(task, emptyList(), emptyList())
        }

        val packageName = run getPackageName@{
            val manifestFile = module.manifestFile
            if (manifestFile == null || !manifestFile.exists()) {
                logger.debug("Module ${module.name} has no manifest file, skip generate submodule R.dex")
                return CompileResult(task, emptyList(), emptyList())
            }

            val packageName: String?
            if (module.namespace != null) {
                // namespace has higher priority
                logger.debug("Module ${module.name} has namespace ${module.namespace}, use it as package name")
                packageName = module.namespace
            } else {
                packageName = RPackageReader(manifestFile, logger).readPackageName()
                if (packageName.isNullOrEmpty()) {
                    logger.warn("Read package name from manifest file ${manifestFile.absolutePath} failed, which should not happened." +
                            "Compilation may failed because R file generate failed.")
                    return CompileResult(task, emptyList(), emptyList())
                }
            }

            if (packageName == context.packageName) {
                logger.debug("Module ${module.name} is main module (packageName $packageName is same as application package), no need generate submodule R.dex")
                return CompileResult(task, emptyList(), emptyList())
            }
            packageName
        }

        val destRDexFiles = run convertDexFiles@{
            val sourceRDexFiles = rDexOutputDir.listFiles()?.filter { it.isFile && it.extension == "dex" }
            if (sourceRDexFiles.isNullOrEmpty()) {
                logger.debug("Module ${module.name} has no R.dex files in $rDexOutputDir, skip generate submodule R.dex")
                return CompileResult(task, emptyList(), emptyList())
            }
            logger.debug("going to generate R.dex for module ${module.name}, package name is $packageName, source R.dex files are $sourceRDexFiles")

            sourceRDexFiles.map { sourceFile ->
                val (destDexFile, _) = DexPackageRenamer(sourceFile, packageName)
                    .generate(task.outputDir, context.tempModule.buildPathInfo.javaClassPath)
                CompileOutput(
                    CompileOutput.Type.Dex,
                    destDexFile,
                    task.outputDir,
                    apkPath = context.getBelongsApkPath(module),
                    targetApkPaths = context.getAllBelongsApkPaths(module),
                )
            }
        }

        generatedModules.add(module.name)
        // RDexForSubmoduleCompiler has no details because it doesn't really compile files
        return CompileResult(task, emptyList(), destRDexFiles)
    }

    /**
     * Generates R.dex for the external AAR namespaces collected in the temp module. An AAR does not
     * ship its own R class in classes.jar, so the host build must provide one per dependency
     * namespace; otherwise library bytecode fails with NoSuchFieldError after a resource update.
     */
    private fun doTempModuleCompile(
        task: CompileTask,
        module: ModuleInfo,
        rDexOutputDir: File,
        rDexOutputFile: File,
    ): CompileResult {
        val skipResult = CompileResult(task, emptyList(), emptyList())
        if (!rDexOutputFile.exists()) {
            logger.debug("Module ${module.name} has no R file update, skip generate submodule R.dex")
            return skipResult
        }

        val externalResources = task.files.filter { it.type == CompileFile.Type.Resource && it.isDependency }
        if (externalResources.isEmpty()) {
            logger.debug("Module ${module.name} has no external resource input, skip generate submodule R.dex")
            return skipResult
        }

        val rPackageNamesByDependency = externalResources
            .groupBy { it.dependencyName }
            .mapValues { (_, files) -> files.firstNotNullOfOrNull { it.rPackageName?.takeIf(String::isNotEmpty) } }
        val missingPackageNames = rPackageNamesByDependency.filterValues { it == null }.keys
        if (missingPackageNames.isNotEmpty()) {
            logger.warn("Can not resolve R package name for external dependencies: $missingPackageNames")
            throw JuggException.externalRDexPackageNameMissing(missingPackageNames)
        }

        val sourceRDexFiles = rDexOutputDir.listFiles()?.filter { it.isFile && it.extension == "dex" }
        if (sourceRDexFiles.isNullOrEmpty()) {
            logger.debug("Module ${module.name} has no R.dex files in $rDexOutputDir, skip generate submodule R.dex")
            return skipResult
        }

        val pendingPackageNames = rPackageNamesByDependency.values
            .filterNotNull()
            .distinct()
            // the application namespace already gets the main R.dex
            .filter { it != context.packageName }
            .filterNot { generatedModules.contains("${module.name}#$it") }
        logger.debug("going to generate R.dex for module ${module.name}, package names are $pendingPackageNames" +
                ", source R.dex files are $sourceRDexFiles")

        val destRDexFiles = pendingPackageNames.flatMap { rPackageName ->
            sourceRDexFiles.map { sourceFile ->
                val (destDexFile, _) = DexPackageRenamer(sourceFile, rPackageName)
                    .generate(task.outputDir, module.buildPathInfo.javaClassPath)
                CompileOutput(
                    CompileOutput.Type.Dex,
                    destDexFile,
                    task.outputDir,
                    apkPath = context.getBelongsApkPath(module),
                    targetApkPaths = context.getAllBelongsApkPaths(module),
                )
            }
        }
        generatedModules.addAll(pendingPackageNames.map { "${module.name}#$it" })
        return CompileResult(task, emptyList(), destRDexFiles)
    }
}
