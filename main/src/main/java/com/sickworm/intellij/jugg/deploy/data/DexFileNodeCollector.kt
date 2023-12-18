package com.sickworm.intellij.jugg.deploy.data

import com.googlecode.d2j.Field
import com.googlecode.d2j.Method
import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.reader.Op
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexCodeVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.googlecode.d2j.visitors.DexMethodVisitor
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode
import com.sickworm.intellij.jugg.deploy.desugarDefaultInterfaceSuffix
import com.sickworm.intellij.jugg.deploy.interfaceNameFromDesugaredDefaultMethodClass
import com.sickworm.intellij.jugg.deploy.isOfficialClass
import java.util.*
import java.util.concurrent.ConcurrentHashMap


class DexFileNodeCollector(
    private val dexFileName: String,
    private val classes: ConcurrentHashMap<String, ClassNode>,
    private val methodRefs: ConcurrentHashMap<MethodNode, MutableList<String>>,
    private val fieldRefs: ConcurrentHashMap<FieldNode, MutableList<String>>,
    private val subclassRefs: ConcurrentHashMap<String, MutableList<String>>,
    private val defaultMethodInvokeRefs: ConcurrentHashMap<String, MutableList<String>>,
) : DexFileVisitor() {


    override fun visit(
        accessFlags: Int,
        className: String,
        superClass: String,
        interfaceNames: Array<String>
    ): DexClassVisitor {
        val cn = DexClassNode(accessFlags, className, superClass, interfaceNames)
        val classOuterClassPrefix = className.substringBefore('$')
        val classInnerClassPrefix = "$className$"
        val classNewArray = "[$className"

        val classMethodRefs = mutableSetOf<MethodNode>()
        val classFieldRefs = mutableSetOf<FieldNode>()
        val classDefaultMethodInvokeRefs = mutableSetOf<String>()

        return object : DexClassVisitor(cn) {

            override fun visitMethod(accessFlags: Int, method: Method?): DexMethodVisitor {
                super.visitMethod(accessFlags, method)
                return object : DexMethodVisitor() {
                    override fun visitCode(): DexCodeVisitor {
                        return object : DexCodeVisitor() {
                            override fun visitFieldStmt(op: Op?, a: Int, b: Int, field: Field?) {
                                if (field == null) {
                                    return
                                }
                                val owner = field.owner
                                if (className == owner) {
                                    return
                                }
                                if (owner.startsWith(classOuterClassPrefix)) {
                                    return
                                }
                                if (owner.startsWith(classInnerClassPrefix)) {
                                    return
                                }
                                if (owner.isOfficialClass) {
                                    return
                                }
                                classFieldRefs.add(FieldNode(field))
                            }

                            override fun visitMethodStmt(op: Op?, args: IntArray?, method: Method?) {
                                if (method == null) {
                                    return
                                }
                                val owner = method.owner
                                if (className == owner) {
                                    return
                                }
                                if (owner.startsWith(classOuterClassPrefix)) {
                                    return
                                }
                                if (owner.startsWith(classInnerClassPrefix)) {
                                    return
                                }
                                if (owner.startsWith(classNewArray)) {
                                    return
                                }

                                // find before isOfficialClass，because we may redex androidx classes
                                if (owner.endsWith(desugarDefaultInterfaceSuffix)) {
                                    val realOwner = owner.interfaceNameFromDesugaredDefaultMethodClass
                                    classDefaultMethodInvokeRefs.add(realOwner)
                                }

                                if (owner.isOfficialClass) {
                                    return
                                }

                                classMethodRefs.add(MethodNode(method))
                            }
                        }
                    }
                }
            }

            override fun visitEnd() {
                val classNode = ClassNode(dexFileName, cn)
                classes[classNode.className] = classNode

                if (!superClass.isOfficialClass) {
                    subclassRefs.getOrPut(superClass) { Collections.synchronizedList(ArrayList()) }
                        .add(className)
                }
                classNode.interfaceNames.forEach {
                    if (!it.isOfficialClass) {
                        subclassRefs.getOrPut(it) { Collections.synchronizedList(ArrayList()) }
                            .add(className)
                    }
                }

                classFieldRefs.forEach {
                    fieldRefs.getOrPut(it) { Collections.synchronizedList(ArrayList()) }
                        .add(className)
                }

                classMethodRefs.forEach {
                    methodRefs.getOrPut(it) { Collections.synchronizedList(ArrayList()) }
                        .add(className)
                }

                classDefaultMethodInvokeRefs.forEach {
                    defaultMethodInvokeRefs.getOrPut(it) { Collections.synchronizedList(ArrayList()) }
                        .add(className)
                }
            }
        }
    }
}