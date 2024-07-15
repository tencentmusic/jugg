package com.sickworm.intellij.jugg.manager

import com.googlecode.d2j.MethodHandle
import com.googlecode.d2j.node.insn.*

fun DexStmtNode.toArgString(): String {
    val nodeName = this::class.simpleName
    return when (this) {
        is BaseSwitchStmtNode -> "${nodeName}: $op $a"
        is ConstStmtNode -> {
            // const-string
            "$op v$a, $value"
        }
        is DexLabelStmtNode -> "$label:"
        is FieldStmtNode -> {
            // sput-object
            when (op.displayName) {
                "sput-object" -> "$op v$a, $field"
                else -> "${nodeName}: $op $a $b $field"
            }
        }
        is FillArrayDataStmtNode -> "${nodeName}: $op $ra $array"
        is FilledNewArrayStmtNode -> "${nodeName}: $op $type ${args.toArgString()}"
        is JumpStmtNode -> "${nodeName}: $op $a, $b, $label"
        is MethodCustomStmtNode -> "${nodeName}: $op $name $proto ${bsm.toArgString()}"
        is MethodPolymorphicStmtNode -> "${nodeName}: $op "
        is MethodStmtNode -> {
            // invoke-direct, invoke-virtual
            "$op ${args.toRegRefString()}, $method"
        }
        is Stmt0RNode -> {
            // none reg insc.
            "$op"
        }
        is Stmt1RNode -> {
            // single reg insc. move-result-object, etc.
            "$op v$a"
        }
        is Stmt2R1NNode -> "${nodeName}: $op $distReg, $srcReg, $content"
        is Stmt2RNode -> "${nodeName}: $op $a, $b"
        is Stmt3RNode -> "${nodeName}: $op $a, $b, $c"
        is TypeStmtNode -> {
            // new-instance
            when (op.displayName) {
                "new-instance" -> "$op v$a, <t: $type>"
                else -> "${nodeName}: $op v$a, $b, $type"
            }
        }
        else -> throw IllegalArgumentException("unrecognized DexStmtNode type ${this::class.java}")
    }
}

private fun IntArray.toRegString(): String {
    return joinToString(", ") { "v$it" }
}

private fun IntArray.toRegRefString(): String {
    return joinToString(", ") { "{v$it}" }
}

private fun IntArray.toArgString(): String {
    return joinToString(", ") { "v$it" }
}

private fun MethodHandle.toArgString(): String {
    return "<$type ${method?:""}${field?: ""}>"
}