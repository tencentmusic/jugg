package com.sickworm.intellij.aidp

import com.android.tools.idea.run.tasks.LaunchResult
import com.sickworm.intellij.aidp.compiler.CompileFile
import com.sickworm.intellij.aidp.compiler.ICompiler

/** Exception for notifying user */
class AidpException(msg: String): Exception(msg) {

    companion object {
        fun notAllCompiled(remainFiles: Collection<ChangedFile>)
            = AidpException("Can not deploy changes because not all files been successfully compiled.\nremaining files:\n$remainFiles")

        fun notSupportMultiApkOverlays()
            = AidpException("Detected multiple apks in project. Currently Aidp don't support multi-apk overlay")

        fun applyChangesFailed(launchResult: LaunchResult)
                = AidpException("Apply changes failed, cause: ${launchResult.errorId}, ${launchResult.consoleError}")

        fun compileResApkFailed()
                = AidpException("compile resource failed due to compile arsc failed")
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

        fun arscCompileFileNotDirectory() =
            AidpInternalException("Arsc compile only supports one single directory that contains .flat")

        fun resValuesNotSupported() =
            AidpInternalException("Currently AIDP don't support deploy values/*.xml")
    }
}