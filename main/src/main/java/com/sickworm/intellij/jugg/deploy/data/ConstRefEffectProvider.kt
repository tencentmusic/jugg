package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.compiler.constref.EffectedConstRef

interface ConstRefEffectProvider {
    fun getEffectedFiles(changedSourcePaths: Collection<String>): List<EffectedConstRef>

    companion object {
        val NO_OP: ConstRefEffectProvider = object : ConstRefEffectProvider {
            override fun getEffectedFiles(changedSourcePaths: Collection<String>): List<EffectedConstRef> {
                return emptyList()
            }
        }
    }
}
