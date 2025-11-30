package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompiler
import com.sickworm.intellij.jugg.deploy.run.LaunchResult
import java.io.File

/** Exception for notifying user */
class JuggException(msg: String): Exception(msg) {

    companion object {

        fun applyChangesFailed(launchResult: LaunchResult) =
            JuggException("Apply changes failed, errorId: ${launchResult.errorId}, reason: ${launchResult.consoleError}")

        fun androidJarNotFound(details: String) =
            JuggException("Can not found android.jar, can not compile. details: $details")

        fun androidHomeNotFound() =
            JuggException("Can not found android sdk home, exit init.")

        fun loginToRemoteFailed(message: String) =
            JuggException("Login to remote ssh failed. $message")

        fun getJavaCompilerFailed() =
            JuggException("Get JavaCompiler failed, please check your environment.")

        fun runConfigInvalid(details: String) =
            JuggException("Run configuration is invalid:\n$details\nPlease check your run configuration.")

        fun unsupportedOs() =
            JuggException("Unsupported OS, currently only support Windows, Linux and Mac.")

        fun rSyncNotSupportsWindows() =
            JuggException("rSync not supports Windows, please use Linux or Mac.")

        fun databaseNotFound(apkFile: File, databaseName: String) =
            JuggException("Can not found database $databaseName for apk: ${apkFile.path}. Is file deleted in build/jugg/database/apk?")

        fun baseApkNotFound(packageName: String?, apkInfos: List<ApkInfo>) =
            JuggException("Can not found apk for package name: $packageName, apkInfos: $apkInfos")

        fun apkDbNotFound(apkInfoKey: String) =
            JuggException("Can not found apk database for apkInfoKey: $apkInfoKey")
    }
}

/** Exception for plugin internal error, which should not happened */
class JuggInternalException private constructor(msg: String): Exception(msg) {

    companion object {

        fun combineTaskFailed(reason: String) =
            JuggInternalException("Combining CompileTask failed: $reason")

        fun apkNotFound(data: JuggDeployData) =
            JuggInternalException("Apk files not found in: $data.")

        fun apkEntryNotFound(apk: File, path: String) =
            JuggInternalException("Entry{${path}} not found in apk{${apk.absolutePath}}.")

        fun compilerNotSupported(compiler: ICompiler, supportedTypes: List<CompileFile.Type>, invalidFiles: List<CompileFile>) =
            JuggInternalException("Compiler ${compiler::class.java.simpleName} can not compile files.\nsupportedTypes: $supportedTypes\nremaining files:\n$invalidFiles.")

        fun compileOutputDirNotEmpty() =
            JuggInternalException("CompileTask.outputDir is not empty directory, abort. We need empty outputDir to determine output files.")

        fun startAapt2DaemonFailed() =
            JuggInternalException("Start aapt2 damon failed.")

        fun contextInvalidToCompileArsc() =
            JuggInternalException("Can not compile resource yet due to apk file or android jar not found.")

        fun dexFileNotContainsOnlyOneClass(size: Int) =
            JuggInternalException("Dex file doesn't contains only one class (actually $size), which is not allow for apply changes.")

        fun compareWithDifferentClass(oldClassName: String, newClassName: String) =
            JuggInternalException("ClassNodeComparator receive different class name which is meaningless, old class: $oldClassName, new class: $newClassName")

        fun getPackageNameFailedApkNotFound() =
            JuggInternalException("Can not get package name, because can not found apk by ApkProvider.")

        fun initKotlinCompilerFailed(missingClassPaths: List<String>) =
            JuggInternalException("Init kotlin compiler failed, missing classpath: $missingClassPaths.")

        fun notLoginYet() =
            JuggException("Not login yet. Please Login first")

        fun findModuleCompileOrderFailed() =
            JuggInternalException("Find module compile order failed, please report issues.")

        fun unrecognizedType(type: String) =
            JuggInternalException("Unrecognized type: $type")

        fun flagApkPathNotAllowed(items: String) =
            JuggInternalException("Flag apk path not allowed for items: $items")

        fun outputDidNotSpecificApkPath(output: String) =
            JuggInternalException("Output did not specific apk path: $output")

        fun methodNotImplemented(method: String) =
            JuggInternalException("Method not implemented: $method")
    }
}