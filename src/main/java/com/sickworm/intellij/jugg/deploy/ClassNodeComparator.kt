package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.MethodNode

class ClassNodeComparator(
    private val oldClassNode: ClassNode,
    private val newClassNode: ClassNode
) {

    fun compare(): Result {
        if (oldClassNode.superClass != newClassNode.superClass) {
            return Result(Result.SIGNATURE_CHANGED)
        }

        if (!equals(oldClassNode.interfaceNames, newClassNode.interfaceNames)) {
            return Result(Result.SIGNATURE_CHANGED)
        }

        if (!equals(oldClassNode.methods, newClassNode.methods)) {
            return Result(Result.METHOD_CHANGED)
        }

        return Result(Result.SAME_STRUCTURE)
    }

    companion object {

        private fun equals(oldInterfaceNames: Array<String>, newInterfaceNames: Array<String>): Boolean {
            return oldInterfaceNames.contentEquals(newInterfaceNames)
        }

        private fun equals(oldMethods: List<MethodNode>, newMethods: List<MethodNode>): Boolean {
            if (oldMethods.size != newMethods.size) {
                return false
            }

            oldMethods.forEachIndexed { index, oldMethod ->
                val newMethod = newMethods[index]
                if (!oldMethod.isSignatureEquals(newMethod)) {
                    return false
                }
            }

            return true
        }

    }

    class Result(val code: Int) {

        val isSameStructure = code == SAME_STRUCTURE

        companion object {
            const val SAME_STRUCTURE = 0
            const val SIGNATURE_CHANGED = 1 shl 0
            const val METHOD_CHANGED = 1 shl 1
        }
    }
}