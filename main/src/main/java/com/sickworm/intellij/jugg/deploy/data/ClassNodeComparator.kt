package com.sickworm.intellij.jugg.deploy.data

import com.googlecode.d2j.DexConstants
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.lang.StringBuilder
import java.util.*

/**
 * ClassNodeComparator computes class differences.
 */
class ClassNodeComparator(
    private val oldClassNode: ClassNode,
    private val newClassNode: ClassNode,
) {

    fun compare(): ClassNodeDiffResult {
        if (oldClassNode.className != newClassNode.className) {
            throw JuggInternalException.compareWithDifferentClass(oldClassNode.className, newClassNode.className)
        }

        // compare super class
        val modifiedParentClass = mutableListOf<Pair<String?, String?>>()
        if (oldClassNode.superClass != newClassNode.superClass) {
            modifiedParentClass.add(oldClassNode.superClass to newClassNode.superClass)
        }

        val modifiedGenericSignature = if (oldClassNode.genericSignature != newClassNode.genericSignature) {
            oldClassNode.genericSignature to newClassNode.genericSignature
        } else {
            null
        }
        // Member equality excludes generic signatures and represents the erased DEX identity.
        val newMethodsByIdentity = newClassNode.methods.associateBy { it }
        val modifiedGenericSignatureMethods = oldClassNode.methods.filter { oldMethod ->
            val newMethod = newMethodsByIdentity[oldMethod]
            newMethod != null && oldMethod.genericSignature != newMethod.genericSignature
        }
        val newFieldsByIdentity = newClassNode.fields.associateBy { it }
        val modifiedGenericSignatureFields = oldClassNode.fields.filter { oldField ->
            val newField = newFieldsByIdentity[oldField]
            newField != null && oldField.genericSignature != newField.genericSignature
        }

        // here, we don't use map or set to diff result, because in most cases,
        // the order of these data is basically the same

        // compare interface
        val addedInterfaces = LinkedList(newClassNode.interfaceNames)
        val deletedInterfaces = LinkedList(oldClassNode.interfaceNames)
        removeUnion(addedInterfaces, deletedInterfaces)

        // compare field
        val addedFields = LinkedList(newClassNode.fields)
        val deletedFields = LinkedList(oldClassNode.fields)
        removeUnion(addedFields, deletedFields)

        // compare methods
        val addedMethods = LinkedList(newClassNode.methods)
        val deletedMethods = LinkedList(oldClassNode.methods)
        removeUnion(addedMethods, deletedMethods)

        val addedEffectedMethods = LinkedList(newClassNode.methods)
        val deletedEffectedMethods = LinkedList(oldClassNode.methods)
        removeUnionExceptEffected(addedEffectedMethods, deletedEffectedMethods)

        val isAddedAbstractMethodForNonAbstractClass = newClassNode.isAbstract && addedMethods.any { it.isAbstract }

        return ClassNodeDiffResult(
            oldClassNode.className,
            modifiedParentClass,
            modifiedGenericSignature,
            modifiedGenericSignatureMethods,
            modifiedGenericSignatureFields,
            addedInterfaces,
            deletedInterfaces,
            addedFields,
            deletedFields,
            addedMethods,
            deletedMethods,
            deletedEffectedMethods,
            isAddedAbstractMethodForNonAbstractClass,
        )
    }

    companion object {

        /**
         * Remove elements that exist in both lists.
         * Limit input to LinkedList for better performance.
         */
        private fun <T> removeUnion(list1: LinkedList<T>, list2: LinkedList<T>) {
            list1.iterator().let { iterator ->
                while (iterator.hasNext()) {
                    val newInterface = iterator.next()
                    val oldInterface = list2.find { it == newInterface }
                    if (oldInterface != null) {
                        iterator.remove()
                        list2.remove(oldInterface)
                    }
                }
            }
        }

        private fun removeUnionExceptEffected(list1: LinkedList<MethodNode>, list2: LinkedList<MethodNode>) {
            list1.iterator().let { iterator ->
                while (iterator.hasNext()) {
                    val newInterface = iterator.next()
                    val oldInterface = list2.find { it.isEffectedChanged(newInterface) }
                    if (oldInterface != null) {
                        iterator.remove()
                        list2.remove(oldInterface)
                    }
                }
            }
        }
    }
}

/**
 * ClassNodeDiffResult carries className, modifiedParentClass, addedInterfaces, and deletedInterfaces.
 */
class ClassNodeDiffResult(
    val className: String,

    val modifiedParentClass: List<Pair<String?, String?>>, // Pair<old, new>
    val modifiedGenericSignature: Pair<String?, String?>?,
    val modifiedGenericSignatureMethods: List<MethodNode>,
    val modifiedGenericSignatureFields: List<FieldNode>,

    val addedInterfaces: List<String>,
    val deletedInterfaces: List<String>,

    val addedFields: List<FieldNode>,
    val deletedFields: List<FieldNode>,

    val addedMethods: List<MethodNode>,
    val deletedMethods: List<MethodNode>,

    val effectMethods: List<MethodNode>,

    val isAddedAbstractMethodForNonAbstractClass: Boolean,
) {

    val isSameStructure
        get() = modifiedParentClass.isEmpty() &&
                modifiedGenericSignature == null &&
                modifiedGenericSignatureMethods.isEmpty() &&
                modifiedGenericSignatureFields.isEmpty() &&
                addedInterfaces.isEmpty() &&
                deletedInterfaces.isEmpty() &&
                addedFields.isEmpty() &&
                deletedFields.isEmpty() &&
                addedMethods.isEmpty() &&
                deletedMethods.isEmpty()

    @Suppress("RedundantIf")
    val isCanHotReload
        get() = modifiedParentClass.isEmpty() &&
                modifiedGenericSignature == null &&
                modifiedGenericSignatureMethods.isEmpty() &&
                modifiedGenericSignatureFields.isEmpty() &&
                addedInterfaces.isEmpty() &&
                deletedInterfaces.isEmpty() &&
                deletedFields.isEmpty() &&
                deletedMethods.isEmpty() &&
                addedFields.filter {
                    val isStatic = (it.access and DexConstants.ACC_STATIC) != 0
                    if (!isStatic) {
                        return@filter false
                    }
                    val isPrimitive = it.type == "Z" || it.type == "B" || it.type == "C" || it.type == "S" || it.type == "I" || it.type == "J" || it.type == "F" || it.type == "D"
                    if (isPrimitive) {
                        return@filter false
                    }
                    // non-static primitive fields can't hot reload
                    return@filter true
                }.isEmpty()

    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("class $className isSameStructure: $isSameStructure, isCanHotReload: $isCanHotReload")
        if (!isSameStructure) {
            builder.append(", diff result: ")
        }
        if (modifiedParentClass.isNotEmpty()) {
            builder.append("\nmodifiedParentClass: $modifiedParentClass")
        }
        if (modifiedGenericSignature != null) {
            builder.append("\nmodifiedGenericSignature: $modifiedGenericSignature")
        }
        if (modifiedGenericSignatureMethods.isNotEmpty()) {
            builder.append("\nmodifiedGenericSignatureMethods: ${modifiedGenericSignatureMethods.toMethodsString()}")
        }
        if (modifiedGenericSignatureFields.isNotEmpty()) {
            builder.append("\nmodifiedGenericSignatureFields: ${modifiedGenericSignatureFields.toFieldsString()}")
        }
        if (addedInterfaces.isNotEmpty()) {
            builder.append("\naddedInterfaces: $addedInterfaces")
        }
        if (deletedInterfaces.isNotEmpty()) {
            builder.append("\ndeletedInterfaces: $deletedInterfaces")
        }
        if (addedFields.isNotEmpty()) {
            builder.append("\naddedFields: ${addedFields.toFieldsString()}")
        }
        if (deletedFields.isNotEmpty()) {
            builder.append("\ndeletedFields: ${deletedFields.toFieldsString()}")
        }
        if (addedMethods.isNotEmpty()) {
            builder.append("\naddedMethods: ${addedMethods.toMethodsString()}")
        }
        if (deletedMethods.isNotEmpty()) {
            builder.append("\ndeletedMethods: ${deletedMethods.toMethodsString()}")
        }

        return builder.toString()
    }

    private fun List<FieldNode>.toFieldsString(): String {
        if (this.size > 100) {
            return "(total ${this.size}, only print first 100) ${subList(0, 100).toFieldsString()} ..."
        }
        return this.map { it.toStringWithoutOwner() }.toString()
    }

    private fun List<MethodNode>.toMethodsString(): String {
        if (this.size > 100) {
            return "(total ${this.size}, only print first 100) ${subList(0, 100).toMethodsString()} ..."
        }
        return this.map { it.toStringWithoutOwner() }.toString()
    }
}
