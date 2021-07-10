package com.sickworm.intellij.aidp

/** Exception for notifying user */
class AidpException(msg: String): Exception(msg) {

    companion object {
        fun notAllCompiled(remainFiles: Collection<ChangedFile>)
            = AidpException("Can not deploy changes because not all files been successfully compiled.\nremaining files:\n$remainFiles")

        fun notSupportMultiApkOverlays()
            = AidpException("Detected multiple apks in project. Currently Aidp don't support multi-apk overlay")
    }
}

/** Exception for plugin internal error, which should not happened */
class AidpInternalException(msg: String): Exception(msg) {

    companion object {
        fun outputDirNotEmpty() =
            AidpInternalException("output dir not matched when combining CompileTask")

        fun compilerNotSupported(compiler: ICompiler, supportedTypes: List<CompileFile.Type>, invalidFiles: List<CompileFile>) =
            AidpInternalException("Compiler ${compiler::class.java.simpleName} can not compile files.\nsupportedTypes: $supportedTypes\nremaining files:\n$invalidFiles")

        fun compileOutputDirNotEmpty() =
            AidpInternalException("CompileTask.outputDir is not empty directory, abort. We need empty outputDir to determine output files.")

        fun arscCompileFileNotDirectory() =
            AidpInternalException("Arsc compile only supports one single directory that contains .flat")

    }
}