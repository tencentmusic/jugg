package com.sickworm.intellij.jugg.manager

import com.googlecode.d2j.DexType
import com.googlecode.d2j.Visibility
import com.googlecode.d2j.node.*
import org.junit.Assert
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DexClassNodeComparator(
    private val except: DexClassNode?,
    private val actual: DexClassNode?
) {

    fun compare() {
        if (except == null) {
            // not exists in apk, it's ok
            return
        }
        assertNotNull(actual)

        assertEquals(except.className, actual.className)
        assertEquals(except.superClass, actual.superClass)
        assertEquals(except.access, actual.access)
        assertEquals(except.source, actual.source)
        assertArrayEquals(except.interfaceNames, actual.interfaceNames)

        assertListEquals(except.methods, actual.methods) { exceptMethod, actualMethod ->
            compareMethod(exceptMethod, actualMethod)
        }
        assertListEquals(except.fields, actual.fields) { exceptField, actualField ->
            compareFields(exceptField, actualField)
        }

        compareAnnotations(except.anns, actual.anns)
    }

    private fun compareMethod(except: DexMethodNode, actual: DexMethodNode) {
        // description
        assertEquals(except.access, actual.access)
        assertEquals(except.method.desc, actual.method.desc)
        assertEquals(except.method.name, actual.method.name)
        assertEquals(except.method.returnType, actual.method.returnType)
        assertEquals(except.method.owner, actual.method.owner)
        assertEquals(except.method.proto, actual.method.proto)
        assertArrayEquals(except.method.parameterTypes, actual.method.parameterTypes)

        // annotations
        compareAnnotations(except.anns, actual.anns)
        assertArrayEquals(except.parameterAnns, actual.parameterAnns) { exceptAnns, actualAnns ->
            // TODO check build annotation (not ready for such strict inspection) (e.g. Landroidx/annotation/Nullable;)
            if (actualAnns.all { it.visibility == Visibility.BUILD }) {
                return@assertArrayEquals
            }

            compareAnnotations(exceptAnns, actualAnns)
        }

        compareCodeNode(except.codeNode, actual.codeNode, except.method.toString())
    }

    private fun compareCodeNode(except: DexCodeNode?, actual: DexCodeNode?, methodName: String) {
        if (except == null) {
            assertNull(actual)
            return
        }
        assertNotNull(actual)

        // TODO check debug and register (not ready for such strict inspection)
//        assertEquals(except.totalRegister, actual.totalRegister, methodName)
//        if (except.debugNode != null && actual.debugNode != null) {
//            assertEquals(except.debugNode.fineName, actual.debugNode.fineName)
//            assertListEquals(except.debugNode.parameterNames, actual.debugNode.parameterNames)
//            assertListEquals(except.debugNode.debugNodes, actual.debugNode.debugNodes) { exceptNode, actualNode ->
//                assertEquals(exceptNode.label.toString(), actualNode.label.toString())
//            }
//        } else {
//            assertTrue(except.debugNode == null && actual.debugNode == null)
//        }

        try {
            assertListEquals(except.stmts, actual.stmts, methodName) { exceptStmt, actualStmt ->
                // TODO check stmt sub class fields (not ready for such strict inspection)
                assertEquals(exceptStmt.op, actualStmt.op, methodName)
                assertEquals(exceptStmt.__index, actualStmt.__index, methodName)
            }
        } catch (e: AssertionError) {
            val expectStmts = except.stmts?.joinToString("\n") { it.toArgString() }
            val actualStmts = actual.stmts?.joinToString("\n") { it.toArgString() }
            System.err.println("expectStmts:\n$expectStmts")

            System.err.println("actualStmts:\n$actualStmts")
            throw e
        }

        assertListEquals(except.tryStmts, actual.tryStmts, methodName) { exceptStmt, actualStmt ->
            assertArrayEquals(exceptStmt.type, actualStmt.type, methodName)
            assertEquals(exceptStmt.start.toString(), actualStmt.start.toString(), methodName)
            assertEquals(exceptStmt.end.toString(), actualStmt.end.toString(), methodName)
        }

    }

    private fun compareFields(except: DexFieldNode, actual: DexFieldNode) {
        assertEquals(except.access, actual.access)
        assertEquals(except.cst, actual.cst)
        assertEquals(except.field.name, actual.field.name)
        assertEquals(except.field.owner, actual.field.owner)
        assertEquals(except.field.type, actual.field.type)

        compareAnnotations(except.anns, actual.anns)
    }

    private fun compareAnnotations(except: List<DexAnnotationNode>?, actual: List<DexAnnotationNode>?) {
        assertListEquals(except, actual) { exceptAnn, actualAnn ->
            assertEquals(exceptAnn.type, actualAnn.type)
            // TODO check Metadata (not ready for such strict inspection)
            if (exceptAnn.type == "Lkotlin/Metadata;") {
                return@assertListEquals
            }

            assertEquals(exceptAnn.visibility, actualAnn.visibility)
            assertListEquals(exceptAnn.items, actualAnn.items) { exceptItem, actualItem ->
                compareAnnotationNode(exceptItem, actualItem)
            }
        }
    }

    private fun compareAnnotationNode(except: DexAnnotationNode.Item, actual: DexAnnotationNode.Item) {
        assertEquals(except.name, actual.name)
        when (except.value) {
            is Array<*> -> {
                assertTrue(actual.value is Array<*>)
                @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                assertArrayEquals(except.value as Array<Object>, actual.value as Array<Object>)
            }
            is DexType -> {
                assertTrue(actual.value is DexType)
                assertEquals((except.value as DexType).desc, (actual.value as DexType).desc)
            }
            else -> {
                assertEquals(except.value, actual.value)
            }
        }
    }
}

private inline fun <T> assertArrayEquals(
    except: Array<T>?,
    actual: Array<T>?,
    errorMessage: String? = null,
    block: ((T, T) -> Unit) = { a, b -> assertEquals(a, b, errorMessage) }
) {
    assertListEquals(except?.toList(), actual?.toList(), errorMessage, block)
}

private inline fun <T> assertListEquals(
    except: List<T>?,
    actual: List<T>?,
    errorMessage: String? = null,
    block: ((T, T) -> Unit) = { a, b -> assertEquals(a, b, errorMessage) }
) {
    if (except == null && actual == null) {
        return
    }
    if (except == null || actual == null) {
        Assert.fail("except null ${except == null}, actual null ${actual == null}")
        return
    }

    assertEquals(except.size, actual.size, errorMessage)
    for (index in except.indices) {
        block(except[index], actual[index])
    }
}
