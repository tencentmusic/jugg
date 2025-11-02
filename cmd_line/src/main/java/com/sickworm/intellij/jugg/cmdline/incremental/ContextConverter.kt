package com.sickworm.intellij.jugg.cmdline.incremental

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.changeBaseDir
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployHistoryManager
import com.sickworm.intellij.jugg.project.BaseCompileContext
import com.sickworm.intellij.jugg.project.FileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Convert base build context to source compile context
 */
class ContextConverter(
    baseContext: ICompileContext,
    private val baseProjectDir: File,
    private val sourceProjectDir: File,
    coroutineScope: CoroutineScope,
    logger: Logger,
) {

    private val sourcePathManager = JuggPathManager(sourceProjectDir)

    val fileChangesHandler = FileChangesHandler(
        sourcePathManager.projectDir,
        sourcePathManager.juggRootDir,
        logger,
    )

    val sourceContext = BaseCompileContext(
        logger = baseContext.logger,
        androidHome = baseContext.androidHome,
        tempCompileDir = File(sourcePathManager.compileRootDir, "compiled"),
        tempModuleDir = File(sourcePathManager.compileRootDir, "temp_module"),
        // map modules to source project dir
        modules = baseContext.modules.mapValues { (_, baseModule) ->
            baseModule.copy(
                moduleRootDir = baseModule.moduleRootDir.baseToSource(),
                projectRootDir = baseModule.projectRootDir.baseToSource(),
                sourceDirs = baseModule.sourceDirs.map { it.baseToSource() },
                resourceDirs = baseModule.resourceDirs.map { it.baseToSource() },
                assetsDirs = baseModule.assetsDirs.map { it.baseToSource() },
                manifestFile = baseModule.manifestFile?.baseToSource(),
                buildPathInfo = baseModule.buildPathInfo, // use base directly
                signingConfigs = baseModule.signingConfigs?.map {
                    it.copy(
                        keystore = it.keystore?.baseToSource(),
                    )
                },
            )
        },
        projectDir = sourcePathManager.projectDir,
        deployFileManager = DeployFileManager(
            logger,
            sourcePathManager.tmpDir,
            sourcePathManager.databaseDir,
            coroutineScope,
        ),
        deployHistoryManager = DeployHistoryManager(
            sourcePathManager,
            fileChangesHandler,
            logger,
        ),
        incrementalDataDir = File(sourcePathManager.compileRootDir, "incremental"),
        cmdCompileEnv = baseContext.cmdCompileEnv,
        scene = baseContext.scene,
    )

    init {
        fileChangesHandler.init(sourceContext)
    }

    private fun File.baseToSource(): File {
        if (baseProjectDir == sourceProjectDir) {
            return this
        }
        return changeBaseDir(baseProjectDir, sourceProjectDir)
    }
}