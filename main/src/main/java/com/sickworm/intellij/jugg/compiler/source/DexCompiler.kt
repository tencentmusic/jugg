package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.io.File
import java.util.zip.ZipFile
import kotlin.system.measureTimeMillis

/**
 * DexCompiler converts changed class/jar inputs into dex artifacts and performs jar-level class diffing before dexing.
 */
class DexCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Class)

    override val isNeedPrintProgress: Boolean = true

    private val dexFileMaker = DexFileMaker(logger)

    override val beforeCompileOrderRange: IntRange = CompileOrder.beforeDex
    override val afterCompileOrderRange: IntRange = CompileOrder.afterDex

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        if (task.files.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }

        val minApi = run {
            // use min(applicationModule.minSdkVersion) as DEX min API
            val applicationMinApi = context.applicationModule?.minSdkVersion?.toIntOrNull()
            val isEnableDesugared = context.isEnableDesugared
            val finalMinApi = when {
                // context shows that project is enabled desugar,
                // but other module's minSdkVersion >= 26 (disable desugar).
                // use 21 to enable desugar
                (isEnableDesugared && applicationMinApi != null && applicationMinApi >= 26) -> 21
                // use other module's minSdkVersion as DEX min API
                (applicationMinApi != null && applicationMinApi > 0) -> applicationMinApi
                isEnableDesugared -> 21 // use 21 to enable desugar
                else -> 31 // use 31 to disable desugar
            }
            logger.debug("get minSdkVersion applicationModule=${context.applicationModule?.name}), isEnableDesugared = $isEnableDesugared" +
                    ", use $finalMinApi as DEX min API.")
            finalMinApi
        }

        return try {
            val classFiles = task.files.filter { it.file.extension == "class" }
            var classResult = CompileResult(task, emptyList(), emptyList())
            if (classFiles.isNotEmpty()) {
                classResult = doDex(task, classFiles, classFiles, minApi, true, "", module)
            }

            val jarFiles = task.files.filter { it.file.extension != "class" }
            val jarResults = jarFiles.map {
                val changedClasses = diffJar(it)
                if (changedClasses.isEmpty()) {
                    logger.debug("no class changed, skip dex")
                    return@map CompileResult(task, listOf(Result.success(it)), emptyList())
                }
                doDex(task, listOf(it), changedClasses, minApi, false, it.jarDexFileName, module)
            }
            CompileResult(
                task,
                classResult.details + jarResults.flatMap { it.details },
                classResult.outputs + jarResults.flatMap { it.outputs },
            )
        } catch (e: Exception) {
            logger.debug("got unexpected error", e)
            val details:List<Result<CompileFile, CompileError>> = task.files.map {
                Result.failure(CompileError(it, listOf(-1L to (e.message?: ""))))
            }
            CompileResult(task, details, emptyList())
        }
    }

    private fun diffJar(compileFile: CompileFile): List<CompileFile> {
        val oldJar = compileFile.oldJar
        if (oldJar == null || !oldJar.exists()) {
            logger.debug("oldJar not exists, doDex all")
            return listOf(compileFile)
        }

        logger.debug("oldJar exists ${oldJar}, doDex diff")
        val tmpClassesDir = File(context.tempCompileDir, "tmp_jar_classes")
        tmpClassesDir.deleteRecursively()
        tmpClassesDir.mkdirs()

        val changedClasses = mutableListOf<CompileFile>()
        val jarFile = compileFile.file
        val oldJarEntryMap = mutableMapOf<String, Long>()
        ZipFile(oldJar).use { zipFile ->
            zipFile.entries().asSequence().forEach {
                oldJarEntryMap[it.name] = it.crc
            }
        }
        var totalClasses = 0
        ZipFile(jarFile).use { zipFile ->
            zipFile.entries().asSequence().forEach {
                if (it.name.startsWith("META-INF/")) {
                    return@forEach
                }
                if (!it.name.endsWith(".class")) {
                    return@forEach
                }
                totalClasses++
                val oldCrc = oldJarEntryMap[it.name]
                if (oldCrc == null || oldCrc != it.crc) {
                    val classFile = File(tmpClassesDir, it.name)
                    classFile.parentFile.mkdirs()
                    classFile.writeBytes(zipFile.getInputStream(it).readBytes())
                    changedClasses.add(CompileFile(
                        CompileFile.Type.Class,
                        classFile,
                        tmpClassesDir,
                        compileFile.module,
                    ))
                }
            }
        }
        logger.debug("jar diff result: classes size = $totalClasses, real changedClasses size=${changedClasses.size}")

        return changedClasses
    }

    private fun doDex(
        task: CompileTask,
        inputFiles: List<CompileFile>,
        files: List<CompileFile>,
        minApi: Int,
        isFilePerClass: Boolean,
        outputDexName: String,
        module: ModuleInfo,
    ): CompileResult {
        val tempOutput = File(context.tempCompileDir, "output")
        tempOutput.clearDir()

        // must call first to extract classpath to classpathDir
        val classpathDir = File(context.tempCompileDir, "classpath")
        classpathDir.mkdirs()
        classpathDir.clearDir()
        val desugarInfo = context.getDesugarInfo(files, module, classpathDir)
        logger.debug("desugarInfo = $desugarInfo")

        dexFileMaker.dex(tempOutput, files.map { it.file }, listOf(classpathDir.absolutePath),
            context.androidJar, minApi, isFilePerClass, desugarInfo.desugaredLibraryConfiguration,
            context.agpR8Classpath)
        val dexFiles: List<File>
        if (isFilePerClass) {
            dexFiles = tempOutput.listFilesRecursively()
        } else {
            val dexFile = File(tempOutput, "classes.dex")
            if (!dexFile.exists()) {
                // no exception, and no classes.dex, which means jar has no classes inside. e.g. kotlin-stdlib-common-1.3.71.jar
                dexFiles = emptyList()
            } else {
                val renameDexFile = File(tempOutput, outputDexName)
                if (!dexFile.renameTo(renameDexFile)) {
                    throw IllegalStateException("rename dex file failed: ${dexFile.absolutePath} -> ${renameDexFile.absolutePath}")
                }
                dexFiles = listOf(renameDexFile)
            }
        }

        val details: List<Result<CompileFile, CompileError>> = inputFiles.map {
            Result.success(it)
        }
        val outputs: List<CompileOutput> = dexFiles.map {
            // Dex outputs can fan out to multiple APKs.
            // Keep the legacy apkPath as the primary anchor and carry every target APK explicitly.
            CompileOutput(
                CompileOutput.Type.Dex,
                it,
                tempOutput,
                apkPath = context.getBelongsApkPath(module),
                targetApkPaths = context.getAllBelongsApkPaths(module),
            )
        }

        val finalOutputs = outputs.map {
            val outputFile = it.file.changeBaseDir(it.baseDir, task.outputDir)
            outputFile.parentFile.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }
            it.file.renameTo(outputFile)
            CompileOutput(
                CompileOutput.Type.Dex,
                outputFile,
                task.outputDir,
                apkPath = it.apkPath,
                targetApkPaths = it.targetApkPaths,
            )
        }

        return CompileResult(task, details, finalOutputs)
    }

}
