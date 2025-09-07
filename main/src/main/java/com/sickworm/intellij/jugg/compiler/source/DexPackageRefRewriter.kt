@file:Suppress("NOTHING_TO_INLINE")

package com.sickworm.intellij.jugg.compiler.source

import com.googlecode.d2j.*
import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.Op
import com.googlecode.d2j.visitors.*
import java.io.File

/**
 * Rewrite call package by asm
 */
class DexPackageRefRewriter(
    private val dexFile: File,
    private val converter: (String?) -> String?,
) {

    fun rewrite() {
        val reader = DexFileReader(dexFile.readBytes())
        val writer = ChangePackageWriter(converter)
        reader.accept(writer, 0)
        dexFile.writeBytes(writer.toDexByteArray())
    }
}

private class ChangePackageWriter(
    private val converter: (String?) -> String?,
    private val writer: DexFileWriter = DexFileWriter()
) : DexFileVisitor(writer) {

    override fun visit(
        accessFlags: Int,
        className: String,
        superClass: String?,
        interfaceNames: Array<out String>?
    ): DexClassVisitor {
        val writerClassVisitor = writer.visit(accessFlags, className, superClass, interfaceNames)
        return object : DexClassVisitor(writerClassVisitor) {
            override fun visitAnnotation(name: String, visibility: Visibility): DexAnnotationVisitor {
                return getDexAnnotationVisitor(super.visitAnnotation(convert(name), visibility))
            }

            override fun visitMethod(accessFlags: Int, method: Method): DexMethodVisitor {
                val superVisitor = super.visitMethod(accessFlags, method.convert())
                return object : DexMethodVisitor(superVisitor) {

                    override fun visitAnnotation(name: String?, visibility: Visibility?): DexAnnotationVisitor {
                        return getDexAnnotationVisitor(super.visitAnnotation(convert(name), visibility))
                    }

                    override fun visitCode(): DexCodeVisitor {
                        return object : DexCodeVisitor(super.visitCode()) {
                            override fun visitMethodStmt(op: Op?, args: IntArray?, method: Method?) {
                                super.visitMethodStmt(op, args, method.convert())
                            }

                            override fun visitMethodStmt(op: Op?, args: IntArray?, name: String?,
                                                         proto: Proto?, bsm: MethodHandle?, vararg bsmArgs: Any?) {
                                val newBsmArgs = bsmArgs.map {
                                    when (it) {
                                        is Method -> it.convert()
                                        is Field -> it.convert()
                                        else -> it
                                    }
                                }.toTypedArray()
                                super.visitMethodStmt(
                                    op,
                                    args,
                                    name,
                                    proto.convert(),
                                    bsm.convert(),
                                    *newBsmArgs
                                )
                            }

                            override fun visitMethodStmt(op: Op?, args: IntArray?, bsm: Method?, proto: Proto?) {
                                super.visitMethodStmt(op, args, bsm.convert(), proto.convert())
                            }

                            override fun visitFieldStmt(op: Op?, a: Int, b: Int, field: Field?) {
                                super.visitFieldStmt(op, a, b, field.convert())
                            }

                            override fun visitTypeStmt(op: Op?, a: Int, b: Int, type: String?) {
                                super.visitTypeStmt(op, a, b, convert(type))
                            }

                            override fun visitDebug(): DexDebugVisitor {
                                return object : DexDebugVisitor(super.visitDebug()) {
                                    override fun visitStartLocal(reg: Int, label: DexLabel?, name: String?, type: String?, signature: String?) {
                                        // e.g.
                                        // (maybe null) signature = "Ljava/util/concurrent/ConcurrentHashMap<Ljava/lang/String;Ljava/lang/String;>;"
                                        // type = "Ljava/util/concurrent/ConcurrentHashMap;"
                                        // won't handle signature just like r8
                                        super.visitStartLocal(reg, label, name, convert(type), signature)
                                    }
                                }
                            }

                            override fun visitFillArrayDataStmt(op: Op?, ra: Int, array: Any?) {
                                // no idea what it is
                                super.visitFillArrayDataStmt(op, ra, array)
                            }

                            override fun visitFilledNewArrayStmt(op: Op?, args: IntArray?, type: String?) {
                                // no idea what it is
                                super.visitFilledNewArrayStmt(op, args, convert(type))
                            }

                        }
                    }
                }
            }

            override fun visitField(accessFlags: Int, field: Field, value: Any?): DexFieldVisitor {
                val superVisitor = super.visitField(accessFlags, field.convert(), value)
                return object : DexFieldVisitor(superVisitor) {
                    override fun visitAnnotation(name: String, visibility: Visibility): DexAnnotationVisitor {
                        return getDexAnnotationVisitor(super.visitAnnotation(convert(name), visibility))
                    }
                }
            }
        }
    }

    private inline fun Field?.convert(): Field? {
        this ?: return null
        return Field(
            convert(owner),
            name,
            convert(type),
        )
    }

    private inline fun Method?.convert(): Method? {
        this ?: return null
        return Method(
            convert(owner),
            name,
            proto.convert(),
        )
    }

    private inline fun Proto?.convert(): Proto? {
        this ?: return null
        return Proto(
            parameterTypes?.map { convert(it) }?.toTypedArray(),
            convert(returnType)
        )
    }

    private inline fun MethodHandle?.convert(): MethodHandle? {
        this ?: return null
        return MethodHandle(type, field.convert(), method.convert())
    }

    private inline fun convert(type: String?): String? {
        type ?: return null
        if (type.startsWith("[")) {
            return "[" + converter(type.substring(1))
        }
        return converter(type)
    }

    private fun getDexAnnotationVisitor(writer: DexAnnotationVisitor): DexAnnotationVisitor {
        return object : DexAnnotationVisitor(writer) {
            override fun visit(name: String?, value: Any?) {
                // e.g.
                // .annotation system Ldalvik/annotation/Signature;
                //    value = {
                //        "Ljava/util/concurrent/ConcurrentHashMap<",
                //        "Ljava/lang/String;",
                //        "Ljava/lang/String;",
                //        ">;"
                //    }
                // .end annotation
                if (value is String) {
                    if (value.endsWith("<")) {
                        // if not match, newClassWithFormat == value, because newClass == originClass
                        val originClass = value.substring(0, value.length - 1) + ";"
                        val newClass = convert(originClass)
                        val newClassWithFormat = newClass?.substring(0, newClass.length - 1)?.let { "$it<" }
                        super.visit(name, newClassWithFormat)
                        return
                    }

                    super.visit(name, convert(value))
                    return
                }
                super.visit(name, value)
            }

            override fun visitAnnotation(name: String?, desc: String?): DexAnnotationVisitor {
                return getDexAnnotationVisitor(super.visitAnnotation(convert(name), desc))
            }

            override fun visitArray(name: String?): DexAnnotationVisitor {
                return getDexAnnotationVisitor(super.visitArray(convert(name)))
            }

            override fun visitEnum(name: String?, desc: String?, value: String?) {
                super.visitEnum(convert(name), desc, value)
            }
        }
    }

    fun toDexByteArray(): ByteArray {
        return writer.toByteArray()
    }
}