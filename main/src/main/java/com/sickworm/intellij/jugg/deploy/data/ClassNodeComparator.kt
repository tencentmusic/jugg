package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.lang.StringBuilder
import java.util.*

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

        return ClassNodeDiffResult(
            oldClassNode.className,
            modifiedParentClass,
            addedInterfaces,
            deletedInterfaces,
            addedFields,
            deletedFields,
            addedMethods,
            deletedMethods,
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
    }
}

class ClassNodeDiffResult(
    val className: String,

    val modifiedParentClass: List<Pair<String?, String?>>, // Pair<old, new>

    val addedInterfaces: List<String>,
    val deletedInterfaces: List<String>,

    val addedFields: List<FieldNode>,
    val deletedFields: List<FieldNode>,

    val addedMethods: List<MethodNode>,
    val deletedMethods: List<MethodNode>,
) {

    val isSameStructure
        get() = modifiedParentClass.isEmpty() &&
                addedInterfaces.isEmpty() &&
                deletedInterfaces.isEmpty() &&
                addedFields.isEmpty() &&
                deletedFields.isEmpty() &&
                addedMethods.isEmpty() &&
                deletedMethods.isEmpty()

    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("class $className isSameStructure: $isSameStructure")
        if (!isSameStructure) {
            builder.append(", diff result: ")
        }
        if (modifiedParentClass.isNotEmpty()) {
            builder.append("\nmodifiedParentClass: $modifiedParentClass")
        }
        if (addedInterfaces.isNotEmpty()) {
            builder.append("\naddedInterfaces: $addedInterfaces")
        }
        if (deletedInterfaces.isNotEmpty()) {
            builder.append("\ndeletedInterfaces: $deletedInterfaces")
        }
        if (addedFields.isNotEmpty()) {
            builder.append("\naddedFields: $addedFields")
        }
        if (deletedFields.isNotEmpty()) {
            builder.append("\ndeletedFields: $deletedFields")
        }
        if (addedMethods.isNotEmpty()) {
            builder.append("\naddedMethods: $addedMethods")
        }
        if (deletedMethods.isNotEmpty()) {
            builder.append("\ndeletedMethods: $deletedMethods")
        }

        return builder.toString()
    }
}