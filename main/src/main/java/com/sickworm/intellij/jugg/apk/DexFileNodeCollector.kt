package com.sickworm.intellij.jugg.apk

import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.sickworm.intellij.jugg.compiler.ClassNode

class DexFileNodeCollector : DexFileVisitor() {

    private var classes = mutableMapOf<String, ClassNode>()

    override fun visit(
        accessFlags: Int,
        className: String,
        superClass: String,
        interfaceNames: Array<String>
    ): DexClassVisitor {
        val cn = DexClassNode(accessFlags, className, superClass, interfaceNames)
        return object : DexClassVisitor(cn) {
            override fun visitEnd() {
                classes[cn.className] = ClassNode(cn)
            }
        }
    }

    fun getClasses(): Map<String, ClassNode> {
        return classes
    }

}