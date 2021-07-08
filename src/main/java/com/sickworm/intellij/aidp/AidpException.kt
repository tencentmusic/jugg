package com.sickworm.intellij.aidp

class AidpException(msg: String): Exception(msg) {

    companion object {
        fun notAllCompiled(remainFiles: Collection<ChangedFile>): AidpException {
            return AidpException("Can not deploy changes because not all files has been compiled.\nremaining files:\n$remainFiles")
        }
    }
}