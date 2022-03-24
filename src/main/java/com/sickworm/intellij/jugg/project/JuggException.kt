package com.sickworm.intellij.jugg.project

import com.android.tools.idea.run.tasks.LaunchResult
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompiler
import java.io.File

// TODO exception unit test
/** Exception for notifying user */
class JuggException(msg: String): Exception(msg) {

    companion object {
        fun notAllCompiled(remainFiles: Collection<ChangedFile>) =
            JuggException("Can not deploy changes because not all files been successfully compiled.\nremaining files:\n$remainFiles")

        fun notSupportMultiApkOverlays() =
            JuggException("Detected multiple apks in project. Currently Jugg don't support multi-apk overlay")

        fun applyChangesFailed(launchResult: LaunchResult) =
            JuggException("Apply changes failed, cause: ${launchResult.errorId}, ${launchResult.consoleError}")

        fun buildToolsNotFound(details: String) =
            JuggException("Can not found build tools, can not compile. details: $details")

        fun androidJarNotFound(details: String) =
            JuggException("Can not found android.jar, can not compile. details: $details")

        fun androidHomeNotFound() =
            JuggException("Can not found android sdk home, exit init.")

        fun deviceNotFound() =
            JuggException("Can not found any device to deploy.")

        fun multipleDeviceFound() =
            JuggException("More than one device found via adb, please make sure there is only one device to be deployed.")
    }
}

// TODO exception unit test
/** Exception for plugin internal error, which should not happened */
class JuggInternalException(msg: String): Exception(msg) {

    companion object {
        fun combineTaskFailed() =
            JuggInternalException("Output dir not matched when combining CompileTask.")

        fun compilerNotInit() =
            JuggInternalException("Compiler not init, which should not happened in logic.")

        fun compilerContextNotInit() =
            JuggInternalException("Compiler context not init, which should not happened in logic.")

        fun apkEntryNotFound(apk: File, path: String) =
            JuggInternalException("Entry{${path}} not found in apk{${apk.absolutePath}}.")

        fun compilerNotSupported(compiler: ICompiler, supportedTypes: List<CompileFile.Type>, invalidFiles: List<CompileFile>) =
            JuggInternalException("Compiler ${compiler::class.java.simpleName} can not compile files.\nsupportedTypes: $supportedTypes\nremaining files:\n$invalidFiles.")

        fun compileOutputDirNotEmpty() =
            JuggInternalException("CompileTask.outputDir is not empty directory, abort. We need empty outputDir to determine output files.")

        fun resValuesNotSupported() =
            JuggInternalException("Currently Jugg don't support deploy values/*.xml.")

        fun startAapt2DaemonFailed() =
            JuggInternalException("Start aapt2 damon failed.")

        fun contextInvalidToCompileArsc() =
            JuggInternalException("Can not compile resource yet due to apk file or android jar not found.")

        fun dexFileNotContainsOnlyOneClass(size: Int) =
            JuggInternalException("Dex file doesn't contains only one class (actually $size), which is not allow for apply changes.")

        fun compareWithDifferentClass(oldClassName: String, newClassName: String) =
            JuggInternalException("ClassNodeComparator receive different class name which is meaningless, old class: $oldClassName, new class: $newClassName")
    }
}