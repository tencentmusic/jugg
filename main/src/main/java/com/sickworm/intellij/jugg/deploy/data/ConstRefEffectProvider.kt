package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.compiler.constref.EffectedConstRef

interface ConstRefEffectProvider {
    fun ensureReadyForRecompile(changedSourcePaths: Collection<String>, timeoutMs: Long = 5000L): ConstRefReadiness
    fun getEffectedFiles(changedSourcePaths: Collection<String>): List<EffectedConstRef>

    companion object {
        val NO_OP: ConstRefEffectProvider = object : ConstRefEffectProvider {
            override fun ensureReadyForRecompile(changedSourcePaths: Collection<String>, timeoutMs: Long): ConstRefReadiness {
                return ConstRefReadiness.READY
            }

            override fun getEffectedFiles(changedSourcePaths: Collection<String>): List<EffectedConstRef> {
                return emptyList()
            }
        }
    }
}

data class ConstRefReadiness(
    val isReady: Boolean,
    val unreadyPaths: List<String> = emptyList(),
    val pendingSourceDirs: List<String> = emptyList(),
) {
    companion object {
        val READY = ConstRefReadiness(isReady = true)
    }
}
