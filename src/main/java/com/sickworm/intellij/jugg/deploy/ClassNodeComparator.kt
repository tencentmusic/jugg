package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.DexClassNodeWrapper

class ClassNodeComparator(
    private val oldClassNode: DexClassNodeWrapper,
    private val newClassNode: DexClassNodeWrapper
) {

    fun compare(): Result {
        if (oldClassNode.superClass != newClassNode.superClass) {
            return Result(Result.SIGNATURE_CHANGED)
        }

        if (!oldClassNode.interfaceNames.sortedArray().contentEquals(
                newClassNode.interfaceNames.sortedArray())) {
            return Result(Result.SIGNATURE_CHANGED)
        }

        return Result(Result.SAME_STRUCTURE)
    }

    class Result(val code: Int) {

        val isSameStructure = code == SAME_STRUCTURE
        val isSignatureChanged = code and SIGNATURE_CHANGED > 0

        companion object {
            const val SAME_STRUCTURE = 1 shr 0
            const val SIGNATURE_CHANGED = 1 shr 1
        }
    }
}