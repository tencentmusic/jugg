package com.sickworm.intellij.jugg.rpc


enum class RpcCommand {
    // curl -s -X POST -H "Content-Type: application/json" -d '{"cmd": "ECHO"}' http://localhost:12310/
    ECHO, // just echo back the request body
    RUN,
    ;
}

enum class RpcResult {
    OK,
    ErrorInvalidJsonFormat,
    ErrorEmptyRequestBody,
    ErrorMethodNotAllowed,
    ErrorInternalServerError,
    ErrorInvalidProjectDir,
    ;
}

data class RpcRequest(
    val cmd: RpcCommand,
    val projectDir: String? = null,
)

data class RpcResponse(
    val status: RpcResult,
    val result: Any?,
)
