package com.sickworm.intellij.jugg.cmdline

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.constref.ConstRefAnalyzer
import com.sickworm.intellij.jugg.compiler.constref.ConstRefEngine
import com.sickworm.intellij.jugg.compiler.constref.ConstRefRepoPathResolver
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.IBackgroundTaskRunner
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File

internal val JuggPathManager.baselineConstRefDbFile: File
    get() = File(databaseDir, "const_ref/const_ref_shared.db")

internal fun JuggPathManager.createBaselineConstRefEngine(
    backgroundTaskRunner: IBackgroundTaskRunner,
    logger: Logger,
    dbFile: File = baselineConstRefDbFile,
): ConstRefEngine = ConstRefEngine(
    analyzer = ConstRefAnalyzer(logger.getInstance("ConstRefAnalyzer")),
    dbFile = dbFile,
    logger = logger.getInstance("ConstRefEngine"),
    backgroundTaskRunner = backgroundTaskRunner,
    pathResolver = ConstRefRepoPathResolver(projectDir),
)
