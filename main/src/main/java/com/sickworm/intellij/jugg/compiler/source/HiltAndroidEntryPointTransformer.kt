/*
 * Copyright (C) 2022 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.deploy.classSigName
import com.sickworm.intellij.jugg.deploy.data.ClassAnalysis
import com.sickworm.intellij.jugg.org.objectweb.asm.AnnotationVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.FieldVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.MethodVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes

internal data class HiltTransformResult(
    val bytes: ByteArray,
    val analysis: ClassAnalysis,
)

/** Performs the Hilt Android entry point bytecode rewrite before D8. */
internal class HiltAndroidEntryPointTransformer {

    fun isEntryPoint(analysis: ClassAnalysis): Boolean {
        return analysis.annotationDescriptors.any(ENTRY_POINT_ANNOTATIONS::contains)
    }

    fun generatedSuperclass(className: String): String {
        val internalName = className.toInternalName()
        val packageName = internalName.substringBeforeLast('/', "")
        val simpleName = internalName.substringAfterLast('/').replace('$', '_')
        val generatedName = "Hilt_$simpleName"
        return if (packageName.isEmpty()) generatedName else "$packageName/$generatedName"
    }

    fun transform(
        bytes: ByteArray,
        analysis: ClassAnalysis,
        generatedBaseBytes: ByteArray,
    ): HiltTransformResult {
        val oldSuperclass = analysis.superClass?.toInternalName()
            ?: throw IllegalStateException("Superclass of ${analysis.className} is null")
        val newSuperclass = generatedSuperclass(analysis.className)
        if (oldSuperclass == newSuperclass) {
            return HiltTransformResult(bytes, analysis)
        }
        val generatedBase = analyzeGeneratedBase(generatedBaseBytes)
        if (generatedBase.className != newSuperclass) {
            throw IllegalStateException("Unexpected generated Hilt base ${generatedBase.className}")
        }

        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        reader.accept(
            EntryPointClassVisitor(
                nextVisitor = writer,
                oldSuperclass = oldSuperclass,
                newSuperclass = newSuperclass,
                injectOnReceive = generatedBase.hasOnReceiveMarker,
            ),
            0,
        )
        return HiltTransformResult(
            bytes = writer.toByteArray(),
            analysis = analysis.copy(superClass = newSuperclass.classSigName),
        )
    }

    private fun analyzeGeneratedBase(bytes: ByteArray): GeneratedBaseInfo {
        lateinit var className: String
        var hasOnReceiveMarker = false
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                className = name
            }

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                if (descriptor == ON_RECEIVE_MARKER) {
                    hasOnReceiveMarker = true
                }
                return null
            }

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                if (name == LEGACY_ON_RECEIVE_MARKER && descriptor == BOOLEAN_DESCRIPTOR) {
                    hasOnReceiveMarker = true
                }
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return GeneratedBaseInfo(className, hasOnReceiveMarker)
    }

    private class EntryPointClassVisitor(
        nextVisitor: ClassVisitor,
        private val oldSuperclass: String,
        private val newSuperclass: String,
        private val injectOnReceive: Boolean,
    ) : ClassVisitor(Opcodes.ASM9, nextVisitor) {

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            super.visit(
                version,
                access,
                name,
                signature?.replaceFirst(oldSuperclass, newSuperclass),
                newSuperclass,
                interfaces,
            )
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val methodVisitor = InvokeSpecialAdapter(
                super.visitMethod(access, name, descriptor, signature, exceptions),
                oldSuperclass,
                newSuperclass,
                name == "<init>",
            )
            return if (injectOnReceive && name == ON_RECEIVE_METHOD && descriptor == ON_RECEIVE_DESCRIPTOR) {
                OnReceiveAdapter(methodVisitor, newSuperclass)
            } else {
                methodVisitor
            }
        }
    }

    private class InvokeSpecialAdapter(
        methodVisitor: MethodVisitor,
        private val oldSuperclass: String,
        private val newSuperclass: String,
        private val isConstructor: Boolean,
    ) : MethodVisitor(Opcodes.ASM9, methodVisitor) {
        private var visitedSuperConstructor = false

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            val adaptedOwner = if (opcode == Opcodes.INVOKESPECIAL && owner == oldSuperclass) {
                adaptedOwner(name)
            } else {
                null
            }
            super.visitMethodInsn(opcode, adaptedOwner ?: owner, name, descriptor, isInterface)
        }

        private fun adaptedOwner(methodName: String): String? {
            if (methodName == "<init>" && isConstructor && !visitedSuperConstructor) {
                visitedSuperConstructor = true
                return newSuperclass
            }
            return newSuperclass.takeIf { methodName != "<init>" }
        }
    }

    private class OnReceiveAdapter(
        methodVisitor: MethodVisitor,
        private val newSuperclass: String,
    ) : MethodVisitor(Opcodes.ASM9, methodVisitor) {
        override fun visitCode() {
            super.visitCode()
            super.visitVarInsn(Opcodes.ALOAD, 0)
            super.visitVarInsn(Opcodes.ALOAD, 1)
            super.visitVarInsn(Opcodes.ALOAD, 2)
            super.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                newSuperclass,
                ON_RECEIVE_METHOD,
                ON_RECEIVE_DESCRIPTOR,
                false,
            )
        }
    }

    private fun String.toInternalName(): String {
        return if (startsWith('L') && endsWith(';')) substring(1, length - 1) else replace('.', '/')
    }

    private data class GeneratedBaseInfo(
        val className: String,
        val hasOnReceiveMarker: Boolean,
    )

    companion object {
        private val ENTRY_POINT_ANNOTATIONS = setOf(
            "Ldagger/hilt/android/AndroidEntryPoint;",
            "Ldagger/hilt/android/HiltAndroidApp;",
        )
        private const val ON_RECEIVE_METHOD = "onReceive"
        private const val ON_RECEIVE_DESCRIPTOR = "(Landroid/content/Context;Landroid/content/Intent;)V"
        private const val ON_RECEIVE_MARKER =
            "Ldagger/hilt/android/internal/OnReceiveBytecodeInjectionMarker;"
        private const val LEGACY_ON_RECEIVE_MARKER = "onReceiveBytecodeInjectionMarker"
        private const val BOOLEAN_DESCRIPTOR = "Z"
    }
}
