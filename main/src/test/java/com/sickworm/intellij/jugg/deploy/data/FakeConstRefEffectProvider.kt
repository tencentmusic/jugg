package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.compiler.constref.EffectedConstRef

class FakeConstRefEffectProvider : ConstRefEffectProvider {
    var readiness: ConstRefReadiness = ConstRefReadiness.READY
    var effectedFiles: List<EffectedConstRef> = emptyList()
    var throwOnEnsure: Throwable? = null
    var throwOnGetEffectedFiles: Throwable? = null

    var ensureCallCount = 0
        private set
    var getEffectedFilesCallCount = 0
        private set

    override fun ensureReadyForRecompile(changedSourcePaths: Collection<String>, timeoutMs: Long): ConstRefReadiness {
        ensureCallCount++
        throwOnEnsure?.let { throw it }
        return readiness
    }

    override fun getEffectedFiles(changedSourcePaths: Collection<String>): List<EffectedConstRef> {
        getEffectedFilesCallCount++
        throwOnGetEffectedFiles?.let { throw it }
        return effectedFiles
    }
}
