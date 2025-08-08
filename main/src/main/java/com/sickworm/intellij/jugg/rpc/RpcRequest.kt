package com.sickworm.intellij.jugg.rpc


enum class RpcCommand {
    // curl -s -X POST -H "Content-Type: application/json" -d '{"cmd": "ECHO"}' http://localhost:12304/
    ECHO, // just echo back the request body
    ;
}

enum class RpcResult {
    OK,
    ErrorInvalidJsonFormat,
    ErrorEmptyRequestBody,
    ErrorMethodNotAllowed,
    ErrorInternalServerError,
    ;
}

data class RpcRequest(
    val cmd: RpcCommand,
)

data class RpcResponse(
    val status: RpcResult,
    val result: String,
)
