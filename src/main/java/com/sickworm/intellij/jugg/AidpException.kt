package com.sickworm.intellij.jugg

import com.android.tools.idea.run.tasks.LaunchResult
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompiler
import com.sickworm.intellij.jugg.project.ChangedFile

/** Exception for notifying user */
class AidpException(msg: String): Exception(msg) {

    companion object {
        fun notAllCompiled(remainFiles: Collection<ChangedFile>)
            = AidpException("Can not deploy changes because not all files been successfully compiled.\nremaining files:\n$remainFiles")

        fun notSupportMultiApkOverlays()
            = AidpException("Detected multiple apks in project. Currently Aidp don't support multi-apk overlay")

        fun applyChangesFailed(launchResult: LaunchResult)
                = AidpException("Apply changes failed, cause: ${launchResult.errorId}, ${launchResult.consoleError}")
    }
}

/** Exception for plugin internal error, which should not happened */
class AidpInternalException(msg: String): Exception(msg) {

    companion object {
        fun combineTaskFailed() =
            AidpInternalException("output dir not matched when combining CompileTask")

        fun compilerNotSupported(compiler: ICompiler, supportedTypes: List<CompileFile.Type>, invalidFiles: List<CompileFile>) =
            AidpInternalException("Compiler ${compiler::class.java.simpleName} can not compile files.\nsupportedTypes: $supportedTypes\nremaining files:\n$invalidFiles")

        fun compileOutputDirNotEmpty() =
            AidpInternalException("CompileTask.outputDir is not empty directory, abort. We need empty outputDir to determine output files.")

        fun resValuesNotSupported() =
            AidpInternalException("Currently AIDP don't support deploy values/*.xml")

        fun startAapt2DaemonFailed() =
            AidpInternalException("Start aapt2 damon failed")

        fun contextInvalidToCompileArsc()
                = AidpException("Can not compile resource yet due to apk file or android jar not found")
    }
}