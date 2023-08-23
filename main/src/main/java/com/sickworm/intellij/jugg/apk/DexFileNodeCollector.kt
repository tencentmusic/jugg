package com.sickworm.intellij.jugg.apk

import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.sickworm.intellij.jugg.compiler.ClassNode

class DexFileNodeCollector(
    private val dexFileName: String,
    private val classes: MutableMap<String, ClassNode> = mutableMapOf(),
) : DexFileVisitor() {


    override fun visit(
        accessFlags: Int,
        className: String,
        superClass: String,
        interfaceNames: Array<String>
    ): DexClassVisitor {
        val cn = DexClassNode(accessFlags, className, superClass, interfaceNames)
        return object : DexClassVisitor(cn) {
            override fun visitEnd() {
                val classNode = ClassNode(dexFileName, cn)
                classes[classNode.className] = classNode
            }
        }
    }

    fun getClasses(): Map<String, ClassNode> {
        return classes
    }

}