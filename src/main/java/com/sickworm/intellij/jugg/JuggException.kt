package com.sickworm.intellij.jugg

import com.android.tools.idea.run.tasks.LaunchResult
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompiler
import com.sickworm.intellij.jugg.project.ChangedFile

/** Exception for notifying user */
class JuggException(msg: String): Exception(msg) {

    companion object {
        fun notAllCompiled(remainFiles: Collection<ChangedFile>)
            = JuggException("Can not deploy changes because not all files been successfully compiled.\nremaining files:\n$remainFiles")

        fun notSupportMultiApkOverlays()
            = JuggException("Detected multiple apks in project. Currently Jugg don't support multi-apk overlay")

        fun applyChangesFailed(launchResult: LaunchResult)
                = JuggException("Apply changes failed, cause: ${launchResult.errorId}, ${launchResult.consoleError}")
    }
}

/** Exception for plugin internal error, which should not happened */
class JuggInternalException(msg: String): Exception(msg) {

    companion object {
        fun combineTaskFailed() =
            JuggInternalException("output dir not matched when combining CompileTask")

        fun compilerNotSupported(compiler: ICompiler, supportedTypes: List<CompileFile.Type>, invalidFiles: List<CompileFile>) =
            JuggInternalException("Compiler ${compiler::class.java.simpleName} can not compile files.\nsupportedTypes: $supportedTypes\nremaining files:\n$invalidFiles")

        fun compileOutputDirNotEmpty() =
            JuggInternalException("CompileTask.outputDir is not empty directory, abort. We need empty outputDir to determine output files.")

        fun resValuesNotSupported() =
            JuggInternalException("Currently JUGG don't support deploy values/*.xml")

        fun startAapt2DaemonFailed() =
            JuggInternalException("Start aapt2 damon failed")

        fun contextInvalidToCompileArsc()
                = JuggException("Can not compile resource yet due to apk file or android jar not found")
    }
}