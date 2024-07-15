package com.sickworm.intellij.jugg.compile

import com.googlecode.d2j.Field
import com.googlecode.d2j.Method
import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.reader.Op
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexCodeVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.googlecode.d2j.visitors.DexMethodVisitor
import com.sickworm.intellij.jugg.deploy.classSigName

class DexFileOwnerChecker(
    private val originClassName: String,
) : DexFileVisitor() {

    override fun visit(
        accessFlags: Int,
        className: String,
        superClass: String,
        interfaceNames: Array<String>
    ): DexClassVisitor {
        val cn = DexClassNode(accessFlags, className, superClass, interfaceNames)

        return object : DexClassVisitor(cn) {

            override fun visitMethod(accessFlags: Int, method: Method?): DexMethodVisitor {
                super.visitMethod(accessFlags, method)
                return object : DexMethodVisitor() {
                    override fun visitCode(): DexCodeVisitor {
                        return object : DexCodeVisitor() {
                            override fun visitFieldStmt(op: Op?, a: Int, b: Int, field: Field) {
                                if (field.owner == originClassName) {
                                    throw IllegalArgumentException("Field ${field.name} is owned by ${field.owner}, which is illegal.")
                                }
                            }

                            override fun visitMethodStmt(op: Op?, args: IntArray?, method: Method) {
                                if (method.owner == originClassName) {
                                    throw IllegalArgumentException("Method ${method.name} is owned by ${method.owner}, which is illegal.")
                                }
                            }

                        }
                    }
                }
            }
        }
    }

}