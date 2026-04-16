package com.phhy.rpc.common.exception;

public class RpcTimeoutException extends RpcException {

    public RpcTimeoutException(String message) {
        super(message);
    }

    public RpcTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
