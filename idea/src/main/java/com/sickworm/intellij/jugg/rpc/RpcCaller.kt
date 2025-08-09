package com.sickworm.intellij.jugg.rpc

import com.google.gson.Gson

object RpcCaller {

    fun call(rpcRequest: RpcRequest): RpcResponse {
        return when (rpcRequest.cmd) {
            RpcCommand.ECHO -> {
                RpcResponse(
                    status = RpcResult.OK,
                    result = Gson().toJson(rpcRequest)
                )
            }
            else -> {
                return RpcResponse(RpcResult.ErrorMethodNotAllowed, "Command not supported: ${rpcRequest.cmd}.")
            }
        }
    }
}