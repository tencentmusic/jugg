package com.sickworm.intellij.jugg.rpc


object RpcCommand {
    // curl -s -X POST -H "Content-Type: application/json" -d '{"cmd": "ECHO"}' http://localhost:12310/
    const val ECHO = "ECHO" // just echo back the request body
    const val RUN = "RUN"
}

@Suppress("ConstPropertyName")
object RpcResult {
    const val OK = "OK"
    const val ErrorInvalidJsonFormat = "ErrorInvalidJsonFormat"
    const val ErrorEmptyRequestBody = "ErrorEmptyRequestBody"
    const val ErrorMethodNotAllowed = "ErrorMethodNotAllowed"
    const val ErrorInternalServerError = "ErrorInternalServerError"
    const val ErrorInvalidProjectDir = "ErrorInvalidProjectDir"
}

// do not modify, it can not hot update
open class RpcRequest(
    val cmd: String,
    val projectDir: String? = null,
    val args: Map<String, Any>? = null,
)

// do not modify, it can not hot update
open class RpcResponse(
    val status: String,
    val result: Any?,
)
