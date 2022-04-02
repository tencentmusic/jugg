package com.sickworm.intellij.jugg.manager

import com.googlecode.d2j.node.*
import org.junit.Assert
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
        compareFields(except.fields, actual.fields)
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
            compareAnnotations(exceptAnns, actualAnns)
        }

        compareCodeNode(except.codeNode, actual.codeNode, except.method.toString())
    }

    private fun compareCodeNode(except: DexCodeNode, actual: DexCodeNode, methodName: String) {

        // TODO not ready for such strict inspection
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

        assertListEquals(except.stmts, actual.stmts) { exceptStmt, actualStmt ->
            // TODO check stmt sub class fields (not ready for such strict inspection)
            assertEquals(exceptStmt.op, actualStmt.op, methodName)
            assertEquals(exceptStmt.__index, actualStmt.__index, methodName)
        }

        assertListEquals(except.tryStmts, actual.tryStmts) { exceptStmt, actualStmt ->
            assertArrayEquals(exceptStmt.type, actualStmt.type, methodName)
            assertEquals(exceptStmt.start.toString(), actualStmt.start.toString(), methodName)
            assertEquals(exceptStmt.end.toString(), actualStmt.end.toString(), methodName)
        }

    }

    private fun compareFields(exceptField: List<DexFieldNode>?, actualField: List<DexFieldNode>?) {
        // TODO
    }

    private fun compareAnnotations(exceptField: List<DexAnnotationNode>?, actualField: List<DexAnnotationNode>?) {
        // TODO
    }
}

private fun <T> assertArrayEquals(except: Array<T>?, actual: Array<T>?, errorMessage: String? = null, block: ((T, T) -> Unit)? = null) {
    assertListEquals(except?.toList(), actual?.toList(), errorMessage, block)
}

private fun <T> assertListEquals(except: List<T>?, actual: List<T>?, errorMessage: String? = null, block: ((T, T) -> Unit)? = null) {
    if (except == null && actual == null) {
        return
    }
    if (except == null || actual == null) {
        Assert.fail("except null ${except == null}, actual null ${actual == null}")
        return
    }

    assertEquals(except.size, actual.size)
    for (index in except.indices) {
        if (block != null) {
            block(except[index], actual[index])
        } else {
            assertEquals(except[index], actual[index], errorMessage)
        }
    }
}
